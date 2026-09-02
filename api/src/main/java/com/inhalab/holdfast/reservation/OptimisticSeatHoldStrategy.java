package com.inhalab.holdfast.reservation;

import com.inhalab.holdfast.api.ErrorCode;
import com.inhalab.holdfast.api.SeatConflict;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@code holdfast.strategy=optimistic} — 낙관적 락. concurrency-spec.md 4.3절.
 *
 * <p><b>락을 잡지 않는다.</b> 읽은 시점의 {@code version}을 UPDATE의 WHERE에
 * 넣어, 그 사이에 아무도 행을 바꾸지 않았을 때만 성공한다.
 * {@code rowsAffected = 0}이면 졌다는 뜻이고 다시 시도한다.
 *
 * <h2>JPA {@code @Version}을 쓰지 않는다</h2>
 *
 * <p>4.3이 못박은 사항이다. {@code @Version}은 버전 증가가 flush에 묶이고
 * {@code OptimisticLockException}이 <b>커밋 시점</b>, 즉 {@code @Transactional}
 * 메서드 바깥에서 터진다. 그러면 <b>같은 트랜잭션 안에서 재시도할 수 없다</b> —
 * 재시도하려면 트랜잭션을 다시 열어야 하고, 그 사이에 앞서 잡은 좌석이 풀린다.
 *
 * <p>명시적 조건부 UPDATE({@link SeatInventoryRepository#takeHeldIfVersionMatches})는
 * {@code rowsAffected}가 그 자리에서 돌아오므로 트랜잭션을 유지한 채 재시도할
 * 수 있다. <b>충돌이 예외가 아니라 반환값인 것이 이 전략이 성립하는 조건이다.</b>
 *
 * <h2>재시도 상한 3회는 설계값이 아니라 시작값이다</h2>
 *
 * <p>4.3이 그렇게 적었다. 고경합에서 재시도 분포를 관측한 뒤 조정하고, 조정 전후
 * 값을 함께 기록한다. 그래서 상한과 백오프를 프로퍼티로 뺐다.
 *
 * <p><b>재시도 횟수를 메트릭으로 노출한다</b>(4.3·7.1). 고경합에서 이 값이
 * 폭발하는 것이 이 전략의 특성이고, 7.7.2가 "전략 고유 비용이 어디서
 * 나타나는가"를 보라고 한 자리가 여기다.
 *
 * <table>
 *   <caption>메트릭</caption>
 *   <tr><td>{@code holdfast.optimistic.retries}</td><td>누적 재시도 <b>횟수</b></td></tr>
 *   <tr><td>{@code holdfast.optimistic.retry-exhausted}</td><td>상한을 소진해 포기한 <b>요청</b> 수</td></tr>
 * </table>
 *
 * <p><b>두 값을 더하거나 환산하지 않는다</b>(7.6.1). 단위가 다르다 — 한 요청이
 * 3회 재시도 후 포기하면 앞에 3, 뒤에 1이 더해진다.
 *
 * <h2>포기는 {@code RETRY_EXHAUSTED}다</h2>
 *
 * <p>{@code LOCK_TIMEOUT}과 코드를 구분하되 7.6의 <b>락 포기율 열에는 함께</b>
 * 집계된다. {@link ErrorCode#RETRY_EXHAUSTED}가 {@code LOCK_GIVEUP} 범주에
 * 있어 그렇게 되며, k6는 코드별로도 따로 센다
 * ({@code giveup_retry_exhausted_total}). 시간 기반 포기와 횟수 기반 포기를
 * 전략 열로 추론하지 않고 숫자로 구분하기 위해서다(api-spec 3.2).
 *
 * <p><b>정상 거절과 섞지 않는다.</b> 좌석이 이미 팔려서 거절한 것은 409율이고,
 * 재시도를 소진한 것은 <b>좌석이 남아 있었을 수도 있는데</b> 포기한 것이다.
 */
@Component
@ConditionalOnProperty(name = "holdfast.strategy", havingValue = "optimistic")
public class OptimisticSeatHoldStrategy implements SeatHoldStrategy {

    private static final Logger log = LoggerFactory.getLogger(OptimisticSeatHoldStrategy.class);

    private static final String STATUS_AVAILABLE = "AVAILABLE";
    private static final String STATUS_HELD = "HELD";
    private static final String STATUS_SOLD = "SOLD";

    private final SeatInventoryRepository seatInventoryRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final Counter retries;
    private final Counter retryExhausted;

    @Value("${holdfast.hold-ttl-seconds:300}")
    private int holdTtlSeconds;

    /**
     * 4.3: 재시도 상한 3회. <b>시작값이며 측정 후 조정한다.</b>
     * 최초 시도는 여기 포함되지 않는다 — 3이면 최대 4번 시도한다.
     */
    @Value("${holdfast.optimistic.max-retries:3}")
    private int maxRetries;

    /** 지수 백오프의 기준 간격(ms). 임계 구역이 서브밀리초라 작게 잡는다. */
    @Value("${holdfast.optimistic.backoff-base-ms:2}")
    private int backoffBaseMs;

    public OptimisticSeatHoldStrategy(SeatInventoryRepository seatInventoryRepository,
                                      SeatHoldRepository seatHoldRepository,
                                      MeterRegistry meterRegistry) {
        this.seatInventoryRepository = seatInventoryRepository;
        this.seatHoldRepository = seatHoldRepository;
        this.retries = Counter.builder("holdfast.optimistic.retries")
                .description("낙관적 락 충돌로 다시 시도한 누적 횟수 (7.1 낙관적 재시도 횟수)")
                .register(meterRegistry);
        this.retryExhausted = Counter.builder("holdfast.optimistic.retry-exhausted")
                .description("재시도 상한을 소진해 포기한 요청 수 (7.6.1 락 포기율)")
                .register(meterRegistry);
    }

    @Override
    public HoldResult hold(HoldCommand command) {
        // 좌석 ID는 이미 오름차순으로 정렬돼 들어온다(5.1, SeatHoldService).
        List<SeatConflict> conflicts = new ArrayList<>();

        for (Long seatId : command.seatIds()) {
            ErrorCode outcome = holdOneSeatWithRetry(command, seatId);
            if (outcome != null) {
                conflicts.add(new SeatConflict(seatId, outcome));
            }
        }

        if (!conflicts.isEmpty()) {
            // 전부 아니면 전무(api-spec.md 4절). 되돌리는 것은 호출자의 트랜잭션이 한다.
            return HoldResult.conflict(conflicts.getFirst().code(), conflicts);
        }

        Instant heldUntil = seatHoldRepository.findHeldUntilByHoldId(command.holdId())
                .orElseThrow(() -> new IllegalStateException(
                        "홀드를 INSERT했는데 held_until을 읽지 못했다: holdId=" + command.holdId()));
        return HoldResult.success(heldUntil);
    }

    /**
     * 좌석 하나를 잡는다. 충돌하면 상한까지 다시 시도한다.
     *
     * <p><b>재시도 대상은 충돌뿐이다.</b> 좌석이 팔렸거나 남이 유효하게 점유
     * 중인 것은 정상 거절이므로 즉시 끝낸다 — 다시 읽어도 답이 같고, 재시도
     * 횟수만 부풀려 이 전략 고유 비용을 읽을 수 없게 만든다.
     *
     * @return 실패 사유. 성공이면 {@code null}
     */
    private ErrorCode holdOneSeatWithRetry(HoldCommand command, Long seatId) {
        for (int attempt = 0; ; attempt++) {
            Optional<SeatInventory> found =
                    seatInventoryRepository.findBySessionIdAndSeatId(command.sessionId(), seatId);
            if (found.isEmpty()) {
                return ErrorCode.SEAT_NOT_IN_SESSION;
            }
            SeatInventory inventory = found.get();

            // 1) 정상 거절 판정. 재시도 대상이 아니다.
            if (STATUS_SOLD.equals(inventory.getStatus())) {
                return ErrorCode.SEAT_ALREADY_SOLD;
            }
            boolean takingOverExpired = false;
            if (STATUS_HELD.equals(inventory.getStatus())) {
                if (!isExpired(inventory)) {
                    return ErrorCode.SEAT_HELD_BY_OTHER;
                }
                takingOverExpired = true;
            } else if (!STATUS_AVAILABLE.equals(inventory.getStatus())) {
                throw new IllegalStateException("알 수 없는 seat_inventory.status: " + inventory.getStatus());
            }

            // 2) version 조건부 UPDATE. 여기가 이 전략의 실체다.
            int taken = seatInventoryRepository.takeHeldIfVersionMatches(
                    inventory.getId(), inventory.getVersion(), command.holdId(), holdTtlSeconds);

            if (taken == 0) {
                // 읽은 뒤 UPDATE 사이에 남이 이 행을 바꿨다 = 충돌.
                ErrorCode giveUp = giveUpIfExhausted(attempt, command.sessionId(), seatId);
                if (giveUp != null) {
                    return giveUp;
                }
                continue;
            }

            // 3) 만료 홀드 정리. erd.md 4.1: 조건부 UPDATE가 성공한 <b>뒤</b>에 수행한다.
            //    seat_inventory 다음 seat_hold 순서를 지켜야 전역 락 순서(5.1)가 유지된다.
            if (takingOverExpired && seatHoldRepository.releaseExpired(command.sessionId(), seatId) == 0) {
                // 만료 행을 다른 요청이 먼저 정리했다. 그쪽 INSERT가 진행 중일 수
                // 있으므로 충돌로 보고 재시도한다(erd.md 4.1의 optimistic 행).
                //
                // pessimistic은 같은 상황에서 그대로 진행한다 — 행 락이 이미
                // 직렬화해 동시 발견이 불가능하기 때문이다. 여기는 락이 없다.
                ErrorCode giveUp = giveUpIfExhausted(attempt, command.sessionId(), seatId);
                if (giveUp != null) {
                    return giveUp;
                }
                continue;
            }

            // 4) 홀드 행 INSERT. U-2가 최후 방어선으로 걸려 있다.
            //    여기서 제약 위반이 나면 앱 레벨 방어가 샌 것이다(7.6).
            seatHoldRepository.insertHeld(
                    command.sessionId(), seatId, command.holdId(), command.userId(), holdTtlSeconds);
            return null;
        }
    }

    /**
     * 충돌 한 건을 처리한다. 상한을 넘겼으면 포기 코드를, 아직이면 백오프한 뒤
     * {@code null}을 돌려준다.
     */
    private ErrorCode giveUpIfExhausted(int attempt, long sessionId, Long seatId) {
        if (attempt >= maxRetries) {
            retryExhausted.increment();
            log.debug("재시도 상한 {}회 소진 — RETRY_EXHAUSTED. sessionId={} seatId={}",
                    maxRetries, sessionId, seatId);
            return ErrorCode.RETRY_EXHAUSTED;
        }
        retries.increment();
        backoff(attempt);
        return null;
    }

    /**
     * 확정. <b>한 줄 위임이다</b> — 4개 전략이 공유하는 lazy 검증 조건부 UPDATE를
     * 그대로 쓴다(concurrency-spec.md 3절).
     *
     * <p><b>여기서는 재시도하지 않는다.</b> 확정의 {@code rowsAffected = 0}은
     * 충돌이 아니라 "홀드가 만료됐거나 남의 홀드다"라는 <b>확정적인 답</b>이며,
     * 다시 시도해도 바뀌지 않는다. 재시도를 넣으면 이 전략의 재시도 횟수가
     * 홀드 경합이 아닌 것까지 세게 되어 7.7.2가 보려는 값이 흐려진다.
     */
    @Override
    public boolean confirmSeat(long sessionId, long seatId, String holdId) {
        return seatInventoryRepository.confirmIfStillHeld(sessionId, seatId, holdId) == 1;
    }

    private boolean isExpired(SeatInventory inventory) {
        Instant heldUntil = inventory.getHeldUntil();
        return heldUntil == null || !heldUntil.isAfter(Instant.now());
    }

    /**
     * 지수 백오프 + 지터(4.3).
     *
     * <p><b>full jitter</b>다 — {@code [0, base x 2^attempt]}에서 균등하게 뽑는다.
     * 고정 간격이면 충돌한 요청들이 같은 순간에 다시 몰려 재충돌한다.
     *
     * <p>기준 간격이 2ms로 작은 이유는 이 응용의 임계 구역이 서브밀리초이기
     * 때문이다(7.7.1). 수십 ms를 쉬면 재시도가 아니라 대기를 재게 된다.
     *
     * <p><b>여러 좌석을 잡는 중이면 앞서 잡은 좌석의 행 쓰기 락을 쥔 채 쉰다.</b>
     * 경합도 시나리오는 요청당 1석이라 문제가 없지만(7.2), 복수 좌석 홀드에서는
     * 이 대기가 다른 요청을 막는다. 재시도를 홀드 전체로 감싸려면 트랜잭션을
     * 다시 열어야 하는데, 그러면 앞서 잡은 좌석이 풀려 전부 아니면 전무가 깨진다.
     */
    private void backoff(int attempt) {
        long ceiling = (long) backoffBaseMs << Math.min(attempt, 10);
        long sleepMs = ThreadLocalRandom.current().nextLong(ceiling + 1);
        if (sleepMs == 0) {
            return;
        }
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("백오프 대기 중 인터럽트됐다", e);
        }
    }
}
