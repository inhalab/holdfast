package com.inhalab.holdfast.reservation;

import com.inhalab.holdfast.api.ErrorCode;
import com.inhalab.holdfast.api.SeatConflict;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code holdfast.strategy=unique} — DB 유니크 제약. concurrency-spec.md 4.4절.
 *
 * <p><b>앱 레벨 락을 전혀 쓰지 않는다.</b> {@code seat_hold}에 INSERT하고 U-2
 * ({@code ux_seat_hold_active}) 위반을 받아 409로 바꾼다. 다른 전략과의 차이는
 * 테이블이 아니라 <b>앱 레벨에서 무엇을 하느냐</b>다(2.2) — 나머지 셋도 같은
 * 테이블에 INSERT하며 그 전에 각자의 락을 잡을 뿐이고, <b>이 전략만 그 단계가
 * 비어 있다.</b>
 *
 * <h2>제약 위반은 여기서 정상 동작이다</h2>
 *
 * <p><b>같은 카운터가 전략에 따라 정반대의 의미를 갖는다.</b> 이것을 놓치면
 * 7.6 표를 잘못 읽는다.
 *
 * <table>
 *   <caption>{@code holdfast.constraint.violations}의 의미</caption>
 *   <tr><th>전략</th><th>0이 아니면</th><th>0이면</th></tr>
 *   <tr><td>{@code pessimistic}·{@code optimistic}·{@code redis}</td>
 *       <td><b>앱 락이 샜다</b> — 결함</td><td>정상</td></tr>
 *   <tr><td><b>{@code unique}</b></td>
 *       <td><b>정상 동작</b> — 제약이 제 일을 했다</td>
 *       <td><b>오히려 이상하다</b> — 경합이 없었거나 U-2가 없다</td></tr>
 *   <tr><td>{@code none}</td><td colspan="2">{@code —} 제약 자체를 걸지 않는다(2.1)</td></tr>
 * </table>
 *
 * <p>이 값이 의미를 가지려면 <b>좌석을 동시에 노린 정상 경합만</b> 반영해야
 * 한다(erd.md 4.1). 만료 정리 경쟁이 섞이면 그 열은 무너진다 — 아래 게이트가
 * 그 분리를 담당한다. 측정 장치가 만든 위반이 섞인 사례가 실제로 있었다
 * ({@code docs/results/discarded-measurements.md} 3번).
 *
 * <h2>재시도를 넣지 않는다</h2>
 *
 * <p>4.4와 erd.md 4.1이 못박은 사항이다. 이 전략은 제약 위반을 정상 동작으로
 * <b>세므로</b>, 앱 레벨 재시도를 넣으면 세어야 할 사건을 앱이 먼저 삼켜
 * 다른 전략과 비교가 깨진다. {@code optimistic}의 재시도(4.3)와 혼동하면 안 된다.
 *
 * <p>그래서 <b>락 포기율은 이 전략에서 {@code —}</b>다(7.6.1). 앱 락이 없어
 * 시간 기반 포기({@code LOCK_TIMEOUT})가 없고, 재시도가 없어 횟수 기반 포기
 * ({@code RETRY_EXHAUSTED})도 없다. <b>제약 위반으로 인한 거절은 409율이
 * 담당한다.</b>
 *
 * <h2>U-2가 유일한 방어선이다</h2>
 *
 * <p>다른 전략에서 U-2는 앱 락 뒤의 최후 방어선이지만 여기서는 <b>유일한
 * 방어선</b>이다. 시드가 이 인덱스를 지우면(그것은 {@code none} 전용이다,
 * erd.md 3.1) 이 전략은 아무것도 막지 못하고 {@code none}과 같아진다.
 * {@code UniqueSeatHoldStrategyConcurrencyTest}가 그것을 실제로 확인한다.
 *
 * <p><b>U-2만으로는 부족한 곳이 하나 있다.</b> 부분 인덱스가
 * {@code WHERE status = 'HELD'}이므로 <b>이미 판매된 좌석은 보호하지 않는다</b> —
 * 확정된 홀드는 {@code CONFIRMED}가 되어 인덱스에서 빠지기 때문이다. 그래서
 * 재고 상태 확인이 필요하다. 이것은 락이 아니라 <b>어떤 좌석이 대상이 될 수
 * 있는지의 판정</b>이며, 5개 전략이 모두 한다.
 *
 * <h2>재고 상태 확인이 제약을 대신하지 않는다</h2>
 *
 * <p>"승자가 재고를 {@code HELD}로 바꾸면 나머지는 그것을 읽고 나가버려 INSERT까지
 * 가지 못하는 것 아닌가" — 그러면 이 전략은 제약이 아니라 조회로 막는 것이 되므로
 * 확인이 필요한 물음이다. <b>실측으로 기각됐다.</b>
 *
 * <pre>
 * 30스레드가 좌석 1석을 동시에 홀드
 *   성공 1 · 정상 거절 0 · 제약 위반 29 · 락 포기 0
 * </pre>
 *
 * <p>거른 요청이 <b>하나도 없다.</b> 동시에 출발한 30개가 전부 승자의 커밋 전에
 * 재고를 읽어 {@code AVAILABLE}을 보고, 29개가 INSERT까지 가서 U-2에 걸렸다.
 * <b>재고 확인이 거르는 것은 경쟁자가 아니라 늦게 도착한 요청</b>이며, 그쪽은
 * 애초에 "동시에 노린" 경합이 아니다.
 *
 * <p>그래서 이 확인을 빼지 않는다. 빼면 늦게 온 요청까지 전부 제약 위반으로
 * 세게 되어, 그 값이 <b>좌석을 동시에 노린 정상 경합만 반영해야 한다</b>는
 * erd.md 4.1의 요구를 어긴다. 확인은 지표를 무디게 하는 것이 아니라 <b>날카롭게
 * 한다.</b>
 *
 * <p><b>부하 측정에서는 이만큼 극적이지 않을 것이다.</b> k6는 도착이 1초에 걸쳐
 * 퍼지므로 승자 커밋 이후에 도착하는 요청 비중이 커지고, 그만큼 정상 거절이
 * 늘고 제약 위반이 줄어든다. {@code optimistic}의 재시도 소진이 단위 테스트에서
 * 29건이었다가 부하에서 0.05%로 떨어진 것과 같은 구조다. 7.7.2가 이 전략에서
 * "제약 위반이 0이 아닌가"를 보라고 했지만, <b>부하에서 작게 나오는 것 자체는
 * 결함이 아니라 경합 창이 짧다는 뜻</b>이다.
 */
@Component
@ConditionalOnProperty(name = "holdfast.strategy", havingValue = "unique")
public class UniqueSeatHoldStrategy implements SeatHoldStrategy {

    private static final String STATUS_AVAILABLE = "AVAILABLE";
    private static final String STATUS_HELD = "HELD";
    private static final String STATUS_SOLD = "SOLD";

    private final SeatInventoryRepository seatInventoryRepository;
    private final SeatHoldRepository seatHoldRepository;

    @Value("${holdfast.hold-ttl-seconds:300}")
    private int holdTtlSeconds;

    public UniqueSeatHoldStrategy(SeatInventoryRepository seatInventoryRepository,
                                  SeatHoldRepository seatHoldRepository) {
        this.seatInventoryRepository = seatInventoryRepository;
        this.seatHoldRepository = seatHoldRepository;
    }

    /**
     * 좌석을 홀드한다.
     *
     * <p><b>{@code DataIntegrityViolationException}을 여기서 잡지 않는다.</b>
     * 두 가지 이유가 있다.
     *
     * <ol>
     *   <li><b>제약 위반이 나면 Postgres 트랜잭션이 이미 중단 상태다.</b> 잡아서
     *       다음 좌석으로 넘어가려 해도 이후 모든 문장이 실패한다.</li>
     *   <li><b>카운터가 공통 핸들러에 있다.</b> 여기서 삼키면
     *       {@code holdfast.constraint.violations}가 오르지 않아, 이 전략에서
     *       가장 중요한 지표가 0으로 남는다.</li>
     * </ol>
     *
     * <p>{@code ApiExceptionHandler}가 U-2 위반을 409
     * {@code SEAT_HELD_BY_OTHER}로 바꾸고 카운터를 올린다. 그 코드는 정상 거절
     * 범주라 409율에 잡히며, 이 전략에서는 그것이 맞다(4.4).
     */
    @Override
    public HoldResult hold(HoldCommand command) {
        // 좌석 ID는 이미 오름차순으로 정렬돼 들어온다(5.1, SeatHoldService).
        List<SeatConflict> conflicts = new ArrayList<>();

        for (Long seatId : command.seatIds()) {
            ErrorCode outcome = holdOneSeat(command, seatId);
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
     * 좌석 하나. <b>락도 재시도도 없다.</b>
     *
     * @return 실패 사유. 성공이면 {@code null}. U-2 위반은 예외로 전파된다
     */
    private ErrorCode holdOneSeat(HoldCommand command, Long seatId) {
        // 1) 재고 상태 판정. 락을 잡지 않는 순수 조회다.
        //
        //    U-2가 못 막는 것을 여기서 막는다 — 부분 인덱스가 status='HELD'만
        //    보므로 이미 판매된 좌석(홀드가 CONFIRMED로 빠진 상태)은 INSERT를
        //    허용해 버린다. 이 확인이 없으면 팔린 좌석을 다시 홀드할 수 있다.
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
            // 2) 만료 홀드 정리. **이 UPDATE가 유일한 직렬화 지점이다**(erd.md 4.1).
            //
            //    rowsAffected = 0이면 다른 요청이 이미 정리했고 그쪽 INSERT가
            //    진행 중일 수 있다. **재시도 없이 409로 거절한다** — 재시도를
            //    넣으면 세어야 할 제약 위반을 앱이 먼저 삼켜 이 전략의 지표가
            //    무너진다.
            //
            //    이 게이트가 제약 위반 열의 의미를 지킨다. 게이트가 없으면 두
            //    요청이 같은 만료 행을 발견해 둘 다 INSERT하고, 그 위반이
            //    "좌석을 동시에 노린 정상 경합"이 아니라 "만료 정리 경쟁"으로
            //    카운터에 섞인다(erd.md 4.1).
            if (seatHoldRepository.releaseExpired(command.sessionId(), seatId) == 0) {
                return ErrorCode.SEAT_HELD_BY_OTHER;
            }
        } else if (!STATUS_AVAILABLE.equals(inventory.getStatus())) {
            throw new IllegalStateException("알 수 없는 seat_inventory.status: " + inventory.getStatus());
        }

        // 3) INSERT. **여기가 이 전략의 실체다.** 두 요청이 같은 좌석을 동시에
        //    노리면 U-2가 하나만 통과시키고, 진 쪽은 DataIntegrityViolationException을
        //    받아 공통 핸들러에서 409가 된다. 앱은 아무것도 하지 않는다.
        seatHoldRepository.insertHeld(
                command.sessionId(), seatId, command.holdId(), command.userId(), holdTtlSeconds);

        // 4) 재고를 HELD로. 조건 없는 UPDATE지만 안전하다 — 위 INSERT가 이미
        //    승자를 결정했다. 안전성이 WHERE 절이 아니라 유니크 제약에서 나온다.
        seatInventoryRepository.markHeldUnconditionally(
                inventory.getId(), command.holdId(), holdTtlSeconds);
        return null;
    }

    /**
     * 확정. <b>한 줄 위임이다</b> — 4개 전략이 공유하는 lazy 검증 조건부 UPDATE를
     * 그대로 쓴다(concurrency-spec.md 3절).
     *
     * <p>확정 경로에는 유니크 제약이 관여하지 않는다. {@code seat_hold}가
     * {@code HELD → CONFIRMED}로 바뀌면 U-2의 부분 인덱스에서 빠질 뿐이다.
     * 초과 확정을 막는 것은 여기서도 조건부 UPDATE의 원자성이다.
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
