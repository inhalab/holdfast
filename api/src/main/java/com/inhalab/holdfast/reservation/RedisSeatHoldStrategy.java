package com.inhalab.holdfast.reservation;

import com.inhalab.holdfast.api.ErrorCode;
import com.inhalab.holdfast.api.SeatConflict;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * {@code holdfast.strategy=redis} — Redis 분산락. concurrency-spec.md 4.5절.
 *
 * <p>Redisson {@code RLock}, 키는 {@code lock:seat:{sessionId}:{seatId}}.
 * {@code waitTime} 1초, {@code leaseTime} 10초(7.3 고정 변수).
 *
 * <p><b>{@code leaseTime}을 반드시 명시한다.</b> 생략하면 워치독이 30초 리스를
 * 10초마다 자동 연장하는데, 락을 오래 쥐는 버그가 측정에서 가려지고 실행마다
 * 결과가 달라진다(4.5).
 *
 * <h2>이 락은 정합성을 보장하지 않는다 — 그것이 측정할 명제다</h2>
 *
 * <p>단일 Redis 노드 기반 락은 형식적 의미의 안전한 분산락이 아니다(4.5.1).
 * GC 정지나 네트워크 지연으로 리스가 만료된 뒤에도 클라이언트가 락을 보유했다고
 * 믿을 수 있다. <b>이 프로젝트에서 그럼에도 쓸 수 있는 이유는 U-2가 최후
 * 방어선으로 깔려 있기 때문이다.</b> 분산락은 DB 경합을 줄이는 성능 장치이고,
 * 정합성의 최종 책임은 제약이 진다.
 *
 * <p>그래서 이 전략에서 <b>제약 위반이 0이 아니면 락이 샌 것</b>이고, 그럼에도
 * <b>초과 확정이 0이면 계층 방어가 작동한 것</b>이다(7.5.1). 두 값을 함께 읽어야
 * 4.5.1이 숫자로 뒷받침된다.
 *
 * <p><b>0이 나와도 반증이 아니다.</b> 락이 새는 조건(리스 만료, GC 정지)이 120초
 * 실행에서 재현된다는 보장이 없다 — "관측하지 못했다"와 "새지 않는다"는 다르다.
 * 그 조건을 인위적으로 만든 재현은
 * {@code RedisSeatHoldStrategyConcurrencyTest}의 실패 모드 테스트에 있다.
 *
 * <h2>해제는 커밋 이후다</h2>
 *
 * <p>5.2가 못박은 사항이다. <b>커밋 전에 풀면 락을 걸지 않은 것과 같다</b> —
 * 다른 요청이 커밋되지 않은 상태를 읽는다. 흔한 실수이며 단위 테스트로는 잡히지
 * 않고 부하 테스트에서만 드러난다고 적혀 있어, 이 클래스는 그것을 <b>단위
 * 테스트로 잡도록</b> 만들었다({@code lockIsHeldUntilCommit}).
 *
 * <p>{@code afterCommit}이 아니라 {@code afterCompletion}을 쓴다. 롤백 시에도
 * 해제되어야 한다.
 *
 * <h2>락을 트랜잭션 <i>안에서</i> 잡는다 — erd.md 4.1과 다른 점</h2>
 *
 * <p>erd.md 4.1의 전략별 표는 이 락을 <b>"트랜잭션 시작 전에 획득"</b>하라고
 * 적었다. 그렇게 하지 않았고, 이유는 인터페이스의 원칙이다.
 *
 * <p>{@link SeatHoldStrategy#hold}는 {@link SeatHoldService#hold}의
 * {@code @Transactional} <b>안에서</b> 호출된다. 트랜잭션보다 먼저 락을 잡으려면
 * 전략 전용 훅을 인터페이스에 추가하거나 서비스에 전략별 분기를 넣어야 하는데,
 * 둘 다 <b>"전략 밖 코드에 전략별 분기를 넣지 않는다"</b>는 이 인터페이스의
 * 전제를 깬다. 그 전제가 "k6 시나리오는 고정하고 프로퍼티만 바꿔 측정한다"를
 * 성립시킨다.
 *
 * <p><b>대가는 분명히 적어 둔다.</b> 락을 기다리는 동안 DB 커넥션을 쥐고 있게
 * 되므로, 7.5가 {@code redis}의 장점으로 지목한 "대기 중 커넥션 미점유"가 이
 * 구현에는 없다. 다만 7.5는 이미 <b>이 조건에서 검증 불가</b>로 판정됐다 —
 * 이 응용의 임계 구역이 서브밀리초라 락 대기가 사실상 발생하지 않기 때문이며,
 * {@code pessimistic}의 락 포기가 0에 수렴한 것이 그 증거다
 * ({@code results/sustained-lock-wait-investigation.md}). 기다림이 없으면 대기 중
 * 점유도 없으므로, 이 차이가 측정값을 가르지 않는다.
 *
 * <p>더 긴 임계 구역을 갖는 응용으로 옮길 때는 이 결정을 다시 봐야 한다.
 */
@Component
@ConditionalOnProperty(name = "holdfast.strategy", havingValue = "redis")
public class RedisSeatHoldStrategy implements SeatHoldStrategy {

    private static final Logger log = LoggerFactory.getLogger(RedisSeatHoldStrategy.class);

    private static final String STATUS_AVAILABLE = "AVAILABLE";
    private static final String STATUS_HELD = "HELD";
    private static final String STATUS_SOLD = "SOLD";

    private final SeatInventoryRepository seatInventoryRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final RedissonClient redisson;

    @Value("${holdfast.hold-ttl-seconds:300}")
    private int holdTtlSeconds;

    /** 7.3 고정 변수. 1초 안에 못 얻으면 포기하고 409 {@code LOCK_TIMEOUT}이다. */
    @Value("${holdfast.redis.wait-time-ms:1000}")
    private long waitTimeMs;

    /**
     * 7.3 고정 변수. <b>명시하지 않으면 워치독이 자동 연장해 버그를 가린다</b>(4.5).
     *
     * <p>실패 모드 테스트가 이 값을 아주 작게 낮춰 리스 만료를 재현한다.
     */
    @Value("${holdfast.redis.lease-time-ms:10000}")
    private long leaseTimeMs;

    public RedisSeatHoldStrategy(SeatInventoryRepository seatInventoryRepository,
                                 SeatHoldRepository seatHoldRepository,
                                 RedissonClient redisson) {
        this.seatInventoryRepository = seatInventoryRepository;
        this.seatHoldRepository = seatHoldRepository;
        this.redisson = redisson;
    }

    /** 4.5가 정한 키 형식. 테스트가 같은 키를 조회하므로 형식을 바꾸지 않는다. */
    public static String lockKey(long sessionId, long seatId) {
        return "lock:seat:" + sessionId + ":" + seatId;
    }

    @Override
    public HoldResult hold(HoldCommand command) {
        // 좌석 ID는 이미 오름차순으로 정렬돼 들어온다(5.1, SeatHoldService).
        // 이 전략에서도 순서가 중요하다 — 두 요청이 반대 순서로 분산락을 잡으면
        // DB 데드락이 아니라 **Redis 레벨 교착**이 생기고, waitTime 1초가 지나야
        // 풀린다. 그때는 양쪽 다 LOCK_TIMEOUT으로 실패한다.
        List<SeatConflict> conflicts = new ArrayList<>();

        for (Long seatId : command.seatIds()) {
            ErrorCode outcome = holdOneSeat(command, seatId);
            if (outcome != null) {
                conflicts.add(new SeatConflict(seatId, outcome));
            }
        }

        if (!conflicts.isEmpty()) {
            // 전부 아니면 전무(api-spec.md 4절). 되돌리는 것은 호출자의 트랜잭션이
            // 하고, 잡아둔 락은 afterCompletion이 롤백 시에도 푼다(5.2).
            return HoldResult.conflict(conflicts.getFirst().code(), conflicts);
        }

        Instant heldUntil = seatHoldRepository.findHeldUntilByHoldId(command.holdId())
                .orElseThrow(() -> new IllegalStateException(
                        "홀드를 INSERT했는데 held_until을 읽지 못했다: holdId=" + command.holdId()));
        return HoldResult.success(heldUntil);
    }

    /** @return 실패 사유. 성공이면 {@code null} */
    private ErrorCode holdOneSeat(HoldCommand command, Long seatId) {
        // 1) 분산락 획득. waitTime 안에 못 얻으면 포기다 — 좌석이 남아 있었을
        //    수도 있는데 기다리다 포기한 것이라 정상 거절이 아니라 락 포기로
        //    집계된다(7.6.1).
        if (!acquireLock(command.sessionId(), seatId)) {
            log.debug("분산락 획득 실패 — waitTime {}ms 초과. sessionId={} seatId={}",
                    waitTimeMs, command.sessionId(), seatId);
            return ErrorCode.LOCK_TIMEOUT;
        }

        // 여기서부터 커밋까지 이 좌석은 (락이 유효하다면) 내 것이다.
        Optional<SeatInventory> found =
                seatInventoryRepository.findBySessionIdAndSeatId(command.sessionId(), seatId);
        if (found.isEmpty()) {
            return ErrorCode.SEAT_NOT_IN_SESSION;
        }
        SeatInventory inventory = found.get();

        if (STATUS_SOLD.equals(inventory.getStatus())) {
            return ErrorCode.SEAT_ALREADY_SOLD;
        }
        if (STATUS_HELD.equals(inventory.getStatus())) {
            if (!isExpired(inventory)) {
                return ErrorCode.SEAT_HELD_BY_OTHER;
            }
            // 2) 만료 홀드 정리. erd.md 4.1: **락이 정리와 INSERT를 함께 감싸므로
            //    두 단계 사이가 벌어지지 않는다.** rowsAffected = 0은 정리할 만료
            //    행이 없었다는 뜻이므로 그대로 진행한다 — pessimistic과 같은
            //    근거이며(배타성이 이미 확보됐다), 그쪽은 행 락이고 여기는 분산락이다.
            if (seatHoldRepository.releaseExpired(command.sessionId(), seatId) == 0) {
                log.debug("만료 홀드 정리 rowsAffected=0 — 정리할 행이 없다. 분산락이 "
                        + "배타성을 확보했으므로 진행한다. sessionId={} seatId={}",
                        command.sessionId(), seatId);
            }
        } else if (!STATUS_AVAILABLE.equals(inventory.getStatus())) {
            throw new IllegalStateException("알 수 없는 seat_inventory.status: " + inventory.getStatus());
        }

        // 3) 홀드 행 INSERT. **U-2를 계층 방어로 유지한다**(4.5.1). 분산락이
        //    새면 여기서 제약 위반이 나고, 그 숫자가 "락이 샜다"의 증거가 된다.
        seatHoldRepository.insertHeld(
                command.sessionId(), seatId, command.holdId(), command.userId(), holdTtlSeconds);

        // 4) 재고를 HELD로.
        seatInventoryRepository.markHeldUnconditionally(
                inventory.getId(), command.holdId(), holdTtlSeconds);
        return null;
    }

    /**
     * 락을 얻고, <b>커밋 이후 해제</b>를 예약한다(5.2).
     *
     * <p>{@code afterCommit}이 아니라 {@code afterCompletion}이다 — 롤백 시에도
     * 풀려야 한다. {@code isHeldByCurrentThread()} 확인은 <b>리스가 만료된 뒤</b>
     * 해제를 시도하다 {@code IllegalMonitorStateException}이 나는 것을 막는다.
     * 리스 만료는 4.5.1이 말하는 바로 그 상황이므로 실제로 일어날 수 있다.
     */
    private boolean acquireLock(long sessionId, long seatId) {
        RLock lock = redisson.getLock(lockKey(sessionId, seatId));
        boolean acquired;
        try {
            acquired = lock.tryLock(waitTimeMs, leaseTimeMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("분산락 대기 중 인터럽트됐다", e);
        }
        if (acquired) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            });
        }
        return acquired;
    }

    /**
     * 확정. <b>한 줄 위임이다</b> — 4개 전략이 공유하는 lazy 검증 조건부 UPDATE를
     * 그대로 쓴다(concurrency-spec.md 3절).
     *
     * <p><b>확정에는 분산락을 잡지 않는다.</b> 만료·소유 판정이 확정과 같은 SQL
     * 문 안에서 원자적으로 끝나므로 앞뒤로 벌어질 틈이 없고, 락을 하나 더 잡으면
     * 대기 구간만 늘어난다. 이 전략이 락을 쓰는 곳은 홀드 획득(CS-1)이다.
     *
     * <p>이것이 4.5.1의 구조를 그대로 보여준다 — <b>정합성의 최종 책임은 락이
     * 아니라 DB에 있다.</b>
     */
    @Override
    public boolean confirmSeat(long sessionId, long seatId, String holdId) {
        return seatInventoryRepository.confirmIfStillHeld(sessionId, seatId, holdId) == 1;
    }

    private boolean isExpired(SeatInventory inventory) {
        // 만료 판정의 기준 시각은 DB다(concurrency-spec.md 3절).
        Instant heldUntil = inventory.getHeldUntil();
        return heldUntil == null || !heldUntil.isAfter(Instant.now());
    }
}
