package com.inhalab.holdfast.reservation;

import com.inhalab.holdfast.api.ErrorCode;
import com.inhalab.holdfast.api.SeatConflict;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code holdfast.strategy=pessimistic} — 비관적 락. concurrency-spec.md 4.2절.
 *
 * <p><b>{@code SELECT ... FOR UPDATE}로 재고 행을 잡고 트랜잭션 종료까지
 * 점유한다.</b> 판정이 전부 락 안에서 일어나므로, 락을 쥔 채 읽은 상태는 커밋
 * 전까지 바뀌지 않는다.
 *
 * <h2>{@code none}과 정반대인 지점</h2>
 *
 * <p>베이스라인이 "하지 않는 것"으로 정의된 것과 대칭으로, 이 전략은 그 넷을
 * 전부 한다.
 *
 * <table>
 *   <caption>{@code none}(4.1) 대비</caption>
 *   <tr><th>항목</th><th>{@code none}</th><th>{@code pessimistic}</th></tr>
 *   <tr><td>락</td><td>없음</td><td>{@code FOR UPDATE} (4.2)</td></tr>
 *   <tr><td>홀드 경로 만료 정리</td><td>하지 않음</td><td>수행 + {@code rowsAffected} 판정 (erd 4.1)</td></tr>
 *   <tr><td>확정</td><td>SELECT 후 무조건 UPDATE</td><td>{@link SeatInventoryRepository#confirmIfStillHeld}</td></tr>
 *   <tr><td>U-2 유니크 인덱스</td><td>시드가 제거</td><td><b>걸어둔다</b> — 최후 방어선</td></tr>
 * </table>
 *
 * <p><b>U-2는 이 전략에서 한 번도 발동하지 않아야 한다.</b> 제약 위반 카운터는
 * "앱 락이 샜다"만 세는 값이고(7.1·7.6), 여기서 0이 아니면 행 락이 막지 못한
 * 경로가 있다는 뜻이다. 그래서 경합 테스트에서 U-2를 지우지 않는다 — 지운 채
 * 재면 그 카운터가 의미를 잃는다.
 *
 * <h2>성능 특성 — 이 전략을 규정하는 사실</h2>
 *
 * <p><b>대기하는 동안 DB 커넥션을 계속 쥐고 있다</b>(4.2). 락을 얻지 못한
 * 요청은 커넥션을 점유한 채 줄을 서므로, 경합이 커지면 커넥션 풀이 먼저
 * 마른다. {@code lock_timeout} 1초(7.3)가 그 줄의 길이를 끊는 유일한 장치다.
 * 상한이 없으면 측정 대상이 락 경합에서 커넥션 고갈로 바뀐다.
 *
 * <p><b>7.5의 사전 가설이 여기에 걸려 있었고, 검증 불가로 판정됐다.</b> 가설은
 * "Redis 분산락이 p95에서 이 전략을 앞선다 — Redis가 빨라서가 아니라 대기 구간에
 * 커넥션을 쥐지 않기 때문"이었다. 검증하려면 이 전략이 <b>실제로 대기해야</b>
 * 하는데, 그런 구간이 만들어지지 않았다.
 *
 * <p>실측에서 이 전략이 {@code none}보다 더 점유한 커넥션 시간은 회당
 * <b>0.010ms</b>였다(5.340 vs 5.330ms). 락 포기도 경합도와 무관하게 0에
 * 수렴했다(7.6.1) — 재초기화 버스트가 지나면 좌석이 전부 {@code SOLD}가 되어
 * 아무도 행 락을 쥐지 않으므로 {@code FOR UPDATE}가 즉시 성공한다.
 * <b>기다림이 없으면 대기 중 점유도 없다.</b>
 *
 * <p>그래도 커넥션 풀 대기({@code hikaricp.connections.pending})는 회차마다
 * 기록한다(7.4.2). 이 전략의 p95를 읽으려면 필요하기 때문이다 — p95는 락 비용이
 * 아니라 <b>커넥션 획득 대기</b>를 따라간다
 * ({@code results/p95-inversion-investigation.md}).
 *
 * <p>위 "커넥션 풀이 먼저 마른다"는 서술은 <b>측정된 사실이 아니라 이 전략의
 * 구조가 갖는 위험</b>이다. 임계 구역이 더 긴 응용에서는 실제로 그렇게 되고,
 * {@code lock_timeout} 1초가 그때 줄의 길이를 끊는다.
 */
@Component
@ConditionalOnProperty(name = "holdfast.strategy", havingValue = "pessimistic")
public class PessimisticSeatHoldStrategy implements SeatHoldStrategy {

    private static final Logger log = LoggerFactory.getLogger(PessimisticSeatHoldStrategy.class);

    private static final String STATUS_AVAILABLE = "AVAILABLE";
    private static final String STATUS_HELD = "HELD";
    private static final String STATUS_SOLD = "SOLD";

    private final SeatInventoryRepository seatInventoryRepository;
    private final SeatHoldRepository seatHoldRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 홀드 유지 시간. 호출 측이 아니라 전략이 직접 주입받는다 — 호출 측이 정하면
     * 전략마다 다른 값이 들어갈 여지가 생겨 7.3 고정 변수 통제가 깨진다.
     */
    @Value("${holdfast.hold-ttl-seconds:300}")
    private int holdTtlSeconds;

    /** 7.3 고정 변수. 락 대기 상한 1초. */
    @Value("${holdfast.lock-timeout-ms:1000}")
    private int lockTimeoutMs;

    public PessimisticSeatHoldStrategy(SeatInventoryRepository seatInventoryRepository,
                                       SeatHoldRepository seatHoldRepository) {
        this.seatInventoryRepository = seatInventoryRepository;
        this.seatHoldRepository = seatHoldRepository;
    }

    @Override
    public HoldResult hold(HoldCommand command) {
        applyLockTimeout();

        // 좌석 ID는 이미 오름차순으로 정렬돼 들어온다(5.1, SeatHoldService).
        // 여기서 다시 정렬하지 않는다 — 정렬 책임이 두 곳에 있으면 한쪽을
        // 고쳤을 때 다른 쪽이 남는다. 이 전략에서 순서가 특히 중요하다:
        // 두 요청이 반대 순서로 FOR UPDATE를 걸면 그대로 데드락이다.
        List<SeatConflict> conflicts = new ArrayList<>();

        for (Long seatId : command.seatIds()) {
            // 1) FOR UPDATE. 여기서부터 커밋까지 이 행은 내 것이다.
            //    락을 얻지 못하면 lock_timeout 1초 뒤 예외가 나고,
            //    ApiExceptionHandler가 409 LOCK_TIMEOUT으로 바꾼다(api-spec 3.3).
            Optional<SeatInventory> found =
                    seatInventoryRepository.findForUpdate(command.sessionId(), seatId);

            if (found.isEmpty()) {
                conflicts.add(new SeatConflict(seatId, ErrorCode.SEAT_NOT_IN_SESSION));
                continue;
            }

            SeatInventory inventory = found.get();

            if (STATUS_SOLD.equals(inventory.getStatus())) {
                conflicts.add(new SeatConflict(seatId, ErrorCode.SEAT_ALREADY_SOLD));
                continue;
            }

            if (STATUS_HELD.equals(inventory.getStatus())) {
                // 아직 살아 있는 홀드면 정상 점유다. 정리하지 않고 거절한다
                // (erd.md 4.1 — 만료된 HELD를 발견한 경우에만 정리 판정을 적용).
                if (!isExpired(inventory)) {
                    conflicts.add(new SeatConflict(seatId, ErrorCode.SEAT_HELD_BY_OTHER));
                    continue;
                }
                // 만료된 홀드다. 정리하고 이 좌석을 회수한다.
                if (!reclaimExpired(command.sessionId(), seatId)) {
                    conflicts.add(new SeatConflict(seatId, ErrorCode.SEAT_HELD_BY_OTHER));
                    continue;
                }
            } else if (!STATUS_AVAILABLE.equals(inventory.getStatus())) {
                // 상태값이 셋 중 어느 것도 아니면 데이터가 깨진 것이다.
                throw new IllegalStateException("알 수 없는 seat_inventory.status: " + inventory.getStatus());
            }

            // 2) 재고를 HELD로. 조건 없는 UPDATE지만 안전하다 — 이 행은 위에서
            //    FOR UPDATE로 잡아 두었고, 안전성이 WHERE 절이 아니라 락에서 나온다.
            seatInventoryRepository.markHeldUnconditionally(
                    inventory.getId(), command.holdId(), holdTtlSeconds);

            // 3) 홀드 행 INSERT. U-2가 걸려 있지만 여기까지 온 요청은 행 락을
            //    쥔 하나뿐이므로 제약이 발동하지 않아야 한다. 발동하면 앱 락이 샌 것이다.
            seatHoldRepository.insertHeld(
                    command.sessionId(), seatId, command.holdId(), command.userId(), holdTtlSeconds);
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
     * 확정. <b>한 줄 위임이다</b> — 4개 전략이 공유하는 lazy 검증 조건부 UPDATE를
     * 그대로 쓴다(concurrency-spec.md 3절).
     *
     * <p>여기에 {@code FOR UPDATE}를 덧대지 않는다. 만료·소유 판정이 확정과 같은
     * SQL 문 안에서 원자적으로 끝나므로 앞뒤로 벌어질 틈이 없고, 락을 하나 더
     * 잡으면 대기 구간만 늘어난다. 이 전략이 락을 쓰는 곳은 홀드 획득(CS-1)이다.
     *
     * <p>{@code rowsAffected = 0}이면 홀드가 만료됐거나 남의 홀드다. 동시에 여러
     * 요청이 같은 좌석을 확정하려 해도 <b>정확히 하나만 1을 받는다</b> — 이것이
     * 초과 확정(V-1)이 0이 되는 이유이며,
     * {@code PessimisticSeatHoldStrategyConcurrencyTest}가 검증한다.
     */
    @Override
    public boolean confirmSeat(long sessionId, long seatId, String holdId) {
        applyLockTimeout();
        return seatInventoryRepository.confirmIfStillHeld(sessionId, seatId, holdId) == 1;
    }

    /**
     * 만료된 홀드를 정리하고 좌석을 회수한다. erd.md 4.1의 조건부 UPDATE와
     * {@code rowsAffected} 판정을 그대로 쓴다.
     *
     * <p>순서는 {@code seat_inventory} 락 → {@code seat_hold} 정리로 고정한다
     * (erd.md 4.1의 전역 락 순서). 뒤집어 {@code seat_hold}를 먼저 잡으면 역순이
     * 생겨 데드락이 난다.
     *
     * <p><b>{@code rowsAffected = 0}은 이 전략에서 사실상 나오지 않는다.</b> 행
     * 락이 이미 직렬화하므로 같은 만료 행을 두 요청이 동시에 발견할 수 없다.
     * 그래도 판정을 남겨 두는 것은 erd.md 4.1이 <b>전략 간 코드 경로를 같게</b>
     * 두라고 정했기 때문이다.
     *
     * <p>0이 나온다면 스케줄러가 먼저 정리한 경우뿐이고, 그때 좌석은 실제로 비어
     * 있다. 행 락을 쥔 채 확인한 사실이라 재조회 없이 진행한다 — 여기서 무조건
     * 거절하면 비어 있는 좌석까지 거절하게 된다(erd.md 4.1).
     *
     * @return 홀드를 진행해도 되면 {@code true}
     */
    private boolean reclaimExpired(long sessionId, long seatId) {
        int cleaned = seatHoldRepository.releaseExpired(sessionId, seatId);
        if (cleaned == 0) {
            log.debug("만료 홀드 정리 rowsAffected=0 — 스케줄러가 먼저 정리했다. "
                    + "행 락을 쥐고 있으므로 그대로 진행한다. sessionId={} seatId={}", sessionId, seatId);
        }
        return true;
    }

    private boolean isExpired(SeatInventory inventory) {
        // 만료 판정의 기준 시각은 DB다(concurrency-spec.md 3절). held_until은
        // DB now()로 계산돼 저장되고, 여기서는 그 값을 읽어 비교만 한다.
        Instant heldUntil = inventory.getHeldUntil();
        return heldUntil == null || !heldUntil.isAfter(Instant.now());
    }

    /**
     * 7.3 고정 변수 {@code lock_timeout} 1초를 <b>이 전략의 트랜잭션에만</b>
     * 건다.
     *
     * <p>Postgres 서버 전역으로 두지 않는 이유는 7.3이 이 값을
     * {@code pessimistic}에 한정했기 때문이다. 전역으로 걸면 다른 네 전략의
     * 대기 특성까지 바뀌어 전략 비교가 오염된다.
     *
     * <p>{@code SET LOCAL}은 현재 트랜잭션이 끝나면 저절로 풀린다. 호출 경로가
     * 전부 {@code @Transactional}이므로(SeatHoldService·ReservationService) 커넥션
     * 풀에 설정이 남지 않는다.
     *
     * <p>값은 바인딩 파라미터로 넘길 수 없다({@code SET}은 파라미터를 받지
     * 않는다). {@code int}로 주입받은 프로퍼티라 문자열로 이어 붙여도 안전하다.
     */
    private void applyLockTimeout() {
        entityManager.createNativeQuery("SET LOCAL lock_timeout = " + lockTimeoutMs).executeUpdate();
    }
}
