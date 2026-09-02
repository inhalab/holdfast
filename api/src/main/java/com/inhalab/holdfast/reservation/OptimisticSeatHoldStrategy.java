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
 *
 * <p><b>{@code rowsAffected = 0}이 "졌다"는 뜻인 자리는 재고 인수 하나뿐이다.</b>
 * 한 트랜잭션 안에 조건부 UPDATE가 세 번 나오는데 판정이 서로 다르다 — 재고
 * 인수는 재시도, <b>만료 홀드 정리는 그대로 진행</b>(재시도하면 자기가 방금 쓴
 * 커밋 전 행을 다시 읽어 자기 자신을 거절한다), <b>확정은 거절</b>이다. 각
 * 메서드에 이유를 적었다.
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
 * <h2>재시도 상한 3회 — 관측하고 그대로 확정했다</h2>
 *
 * <p>4.3은 이 값을 "설계값이 아니라 시작값"으로 잡고 고경합 분포를 관측한 뒤
 * 조정하라고 적었다. <b>관측했고, 조정하지 않기로 했다.</b> 상한과 백오프는
 * 프로퍼티로 남겨 두었으므로 언제든 바꿔 다시 잴 수 있다
 * ({@code holdfast.optimistic.max-retries}, {@code .backoff-base-ms}).
 *
 * <p>고경합 3회 중앙값에서 재시도 <b>102회</b>, 소진 <b>34건</b>이 나왔다.
 * <b>102 ÷ 34 = 3.00</b> — 소진한 요청은 정확히 3회를 다 썼고, 총량에서 소진분을
 * 빼면 0이다. 즉 <b>1~2회 만에 성공한 재시도가 하나도 없다.</b> 한 번 진 요청은
 * 상한까지 계속 지므로, 상한을 4·5회로 올려도 같은 요청이 한 번 더 지는 데 시간을
 * 쓸 뿐이다.
 *
 * <p><b>구속하는 것은 시도 횟수가 아니라 백오프 총량이다.</b> 임계 구역이
 * 서브밀리초라 경합 창이 재시도 간격보다 짧다(7.7.1). 그리고 소진은 전체 요청의
 * <b>0.05%</b>이며 {@code RETRY_EXHAUSTED}로 락 포기율에 잡힐 뿐 정합성에는
 * 영향이 없다 — 초과 확정은 9회 전부 0이었다. <b>0.05%를 되찾자고 꼬리 지연을
 * 늘리지 않는다.</b>
 *
 * <p><b>재시도 횟수를 메트릭으로 노출한다</b>(4.3·7.1). 7.7.2가 "전략 고유
 * 비용이 어디서 나타나는가"를 보라고 한 자리가 여기다 — 같은 사건이
 * {@code unique}에서는 제약 위반으로, 앱 락을 쓰는 둘에서는 0으로 보인다.
 * <b>다만 "고경합에서 폭발한다"는 예상은 빗나갔다</b> — 102회는 폭발이 아니고,
 * 재시도는 응답시간을 움직이지 못했다(7.7.1).
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
 *
 * <h2>만료 홀드 인수 — 두 테이블을 같은 순서로 건드린다</h2>
 *
 * <p>재고 행이 {@code HELD}인데 만료된 좌석은 넘겨받을 수 있어야 한다. 그런데
 * <b>재고만 넘겨받으면 U-2가 INSERT를 막는다</b> — 부분 인덱스
 * {@code ux_seat_hold_active}가 {@code status='HELD'}인 {@code seat_hold} 행만
 * 보므로, 만료됐어도 {@code HELD}로 남아 있으면 자리가 차 있는 것이다.
 *
 * <p>그래서 한 트랜잭션 안에서 <b>재고 인수 → 홀드 정리 → 홀드 INSERT</b> 순으로
 * 진행한다(erd.md 4.1의 전역 락 순서와 같다).
 *
 * <pre>
 * 1. takeHeldIfVersionMatches  seat_inventory  version 일치 + (AVAILABLE 또는 만료 HELD)
 * 2. releaseExpired            seat_hold       만료 HELD → RELEASED  ← U-2 자리를 비운다
 * 3. insertHeld                seat_hold       새 HELD 행
 * </pre>
 *
 * <p><b>한쪽만 성공한 상태는 커밋되지 않는다.</b> 셋은 호출자
 * ({@link SeatHoldService#hold})의 한 트랜잭션 안에 있다. 3에서 U-2가 걸리면
 * 1의 재고 인수까지 함께 롤백되므로, "재고는 넘어갔는데 홀드는 없는" 상태가
 * 남지 않는다.
 *
 * <p><b>두 요청이 같은 만료 좌석을 동시에 노려도 1을 통과하는 것은 하나뿐이다.</b>
 * 둘 다 같은 {@code version}을 읽었다면 뒤늦은 쪽의 UPDATE는 0행이 되어 충돌로
 * 재시도한다. 그래서 2와 3은 항상 단독으로 실행된다 — 이것이 이 전략에서 U-2가
 * 발동하지 않아야 하는 이유이며, 발동한다면 앱 방어가 샌 것이다(7.6).
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
            //
            //    **이 순서가 U-2 때문에 강제된다.** 재고 행을 넘겨받아도
            //    seat_hold의 만료 행이 status='HELD'로 남아 있으면 U-2 부분 인덱스가
            //    아래 INSERT를 막는다. 정리가 그 행을 RELEASED로 빼줘야 자리가 난다.
            if (takingOverExpired) {
                int cleaned = seatHoldRepository.releaseExpired(command.sessionId(), seatId);
                if (cleaned == 0) {
                    // **여기서 재시도하지 않는다.** erd.md 4.1의 optimistic 행은
                    // rowsAffected = 0을 충돌로 보라고 하지만, 그 규칙은 정리가
                    // 유일한 직렬화 지점인 경우를 전제한다. 이 전략에서는 바로 위
                    // version 조건부 UPDATE가 이미 배타성을 확보했다 — 같은
                    // version으로 넘겨받는 데 성공한 요청은 하나뿐이다.
                    //
                    // 그리고 여기서 재시도하면 **자기 자신을 거절한다.** 재시도는
                    // 재고 행을 다시 읽는데, 그 행에는 방금 내가 쓴 hold_id와
                    // 미래의 held_until이 들어 있어(자기 트랜잭션의 쓰기는 보인다)
                    // 만료가 아닌 남의 홀드로 판정된다 → SEAT_HELD_BY_OTHER.
                    //
                    // 0이 나오는 것은 정리할 만료 행이 애초에 없었다는 뜻이므로
                    // U-2도 비어 있다. 그대로 INSERT한다.
                    log.debug("만료 홀드 정리 rowsAffected=0 — 정리할 행이 없다. "
                            + "version 조건부 UPDATE가 배타성을 확보했으므로 진행한다. "
                            + "sessionId={} seatId={}", command.sessionId(), seatId);
                }
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
