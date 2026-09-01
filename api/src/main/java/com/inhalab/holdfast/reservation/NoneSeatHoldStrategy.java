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
 * {@code holdfast.strategy=none} — 락 없는 베이스라인. concurrency-spec.md 4.1절.
 *
 * <p><b>이것은 전략이 아니라 실패 증거다.</b> 여기서 초과 예약 M건이 나와야
 * 나머지 4개 전략이 무엇을 고쳤는지 말할 수 있다. 2개월차 산출물이 이 숫자다.
 *
 * <h2>이 클래스가 하지 않는 것 — 이것이 존재 이유다</h2>
 *
 * <p><b>락을 잡지 않는다.</b> 비관적 락({@code FOR UPDATE})도, 낙관적 락
 * (조건부 UPDATE + {@code version})도, 분산락({@code RLock})도 쓰지 않는다.
 *
 * <p><b>홀드 경로에서 만료 홀드를 정리하지 않는다.</b> erd.md 4.1의 전략별 표가
 * {@code none}에 대해 명시한 대로다 — 정리의 {@code rowsAffected} 게이트는 앱
 * 레벨 방어라, 베이스라인에 넣으면 실패 증거가 흐려진다. U-2가 없어 만료 행이
 * INSERT를 막지도 않는다. 정리는 스케줄러에만 맡긴다.
 *
 * <p><b>조건부 UPDATE의 {@code rowsAffected}로 판정하지 않는다.</b> 조회 후
 * UPDATE 방식이며, 조회 결과에 대한 자바 쪽 비교가 유일한 판정이다.
 * {@link SeatInventoryRepository#markHeldUnconditionally}가 {@code void}를
 * 돌려주는 것도 그래서다.
 *
 * <p><b>U-2가 걸려 있다고 전제하지 않는다.</b> {@code none}에서는 시드
 * 스크립트가 그 인덱스를 지운다(erd.md 3.1). 제약 위반을 잡아 거절로 바꾸는 것은
 * {@code unique} 전략의 메커니즘이지 여기서 할 일이 아니다.
 *
 * <p><b>확정도 순진하게 한다.</b> 4개 전략이 쓰는 lazy 검증 조건부 UPDATE는
 * 락이 아니라 올바르게 쓴 쿼리이고, 순진한 첫 구현은 그렇게 쓰지 않는다.
 * {@link #confirmSeat}에 이유를 적었다.
 *
 * <h2>실패가 발생하는 지점</h2>
 *
 * <p><b>홀드 단계(초과 홀드, V-2).</b> 조회와 UPDATE 사이에 틈이 있다. 두
 * 요청이 같은 좌석을 동시에 조회하면 둘 다 {@code AVAILABLE}을 보고 둘 다
 * 통과한 뒤 각자 UPDATE·INSERT한다. Postgres가 UPDATE를 행 단위로 직렬화하지만,
 * 그때는 이미 두 요청 모두 자바 쪽 검사를 지난 뒤라 아무것도 막지 못한다.
 * 결과는 같은 좌석에 대한 HELD {@code seat_hold} 행 여러 개다.
 *
 * <p><b>확정 단계(초과 확정, V-1).</b> 위에서 여러 명이 각자 자기 홀드 행을
 * 갖게 됐으므로, 확정 때 각자 자기 홀드만 확인하면 전원이 통과한다. 그리고
 * 재고를 조건 없이 덮어쓰므로 같은 좌석이 여러 예약에 확정된다. 이것이 검수
 * 기준 "초과 승인 0건"(REQ-01)을 실제로 위반하는 경로다.
 */
@Component
@ConditionalOnProperty(name = "holdfast.strategy", havingValue = "none")
public class NoneSeatHoldStrategy implements SeatHoldStrategy {

    private static final String STATUS_AVAILABLE = "AVAILABLE";
    private static final String STATUS_HELD = "HELD";
    private static final String STATUS_SOLD = "SOLD";

    private final SeatInventoryRepository seatInventoryRepository;
    private final SeatHoldRepository seatHoldRepository;

    /**
     * 홀드 유지 시간. 호출 측이 아니라 전략이 직접 주입받는다 — 호출 측이 정하면
     * 전략마다 다른 값이 들어갈 여지가 생겨 7.3 고정 변수 통제가 깨진다.
     */
    @Value("${holdfast.hold-ttl-seconds:300}")
    private int holdTtlSeconds;

    public NoneSeatHoldStrategy(SeatInventoryRepository seatInventoryRepository,
                                SeatHoldRepository seatHoldRepository) {
        this.seatInventoryRepository = seatInventoryRepository;
        this.seatHoldRepository = seatHoldRepository;
    }

    @Override
    public HoldResult hold(HoldCommand command) {
        // 좌석 ID는 이미 오름차순으로 정렬돼 들어온다(5.1, SeatHoldService).
        // 여기서 다시 정렬하지 않는다 — 정렬 책임이 두 곳에 있으면 한쪽을
        // 고쳤을 때 다른 쪽이 남는다.
        List<SeatConflict> conflicts = new ArrayList<>();

        for (Long seatId : command.seatIds()) {
            // 1) 조회 — 락 없음.
            Optional<SeatInventory> found =
                    seatInventoryRepository.findBySessionIdAndSeatId(command.sessionId(), seatId);

            if (found.isEmpty()) {
                conflicts.add(new SeatConflict(seatId, ErrorCode.SEAT_NOT_IN_SESSION));
                continue;
            }

            SeatInventory inventory = found.get();

            // 2) 자바 쪽 비교 — 이 검사와 아래 UPDATE 사이의 틈이 초과 예약이
            //    발생하는 지점이다. 좁히려 하지 않는다.
            if (!STATUS_AVAILABLE.equals(inventory.getStatus())) {
                conflicts.add(new SeatConflict(seatId, conflictCodeFor(inventory.getStatus())));
                continue;
            }

            // 3) UPDATE — 조건 없음. 앞선 조회가 유효한지 다시 확인하지 않는다.
            seatInventoryRepository.markHeldUnconditionally(
                    inventory.getId(), command.holdId(), holdTtlSeconds);

            // 4) 홀드 행 INSERT — 만료 행 정리 없이 그대로 넣는다.
            seatHoldRepository.insertHeld(
                    command.sessionId(), seatId, command.holdId(), command.userId(), holdTtlSeconds);
        }

        if (!conflicts.isEmpty()) {
            // 전부 아니면 전무(api-spec.md 4절). 여기까지 쓴 행을 되돌리는 것은
            // 호출자의 트랜잭션이 한다 — 전략은 보고만 한다.
            return HoldResult.conflict(representativeCode(conflicts), conflicts);
        }

        Instant heldUntil = seatHoldRepository.findHeldUntilByHoldId(command.holdId())
                .orElseThrow(() -> new IllegalStateException(
                        "홀드를 INSERT했는데 held_until을 읽지 못했다: holdId=" + command.holdId()));
        return HoldResult.success(heldUntil);
    }

    /**
     * 순진한 확정. <b>자기 홀드 행만 보고 재고를 덮어쓴다.</b>
     *
     * <p>4개 전략이 쓰는 {@link SeatInventoryRepository#confirmIfStillHeld}는
     * 만료·소유 판정을 확정과 같은 SQL 문에 넣어 원자적으로 끝낸다. 그것은 락이
     * 아니라 <b>올바르게 쓴 쿼리</b>이고, 아무 동시성 제어도 모르는 첫 구현은
     * 그렇게 쓰지 않는다 — 자기가 만든 홀드 레코드를 정본으로 믿고
     * (state-transitions.md 0절) 재고는 그 결과를 반영하는 파생값이니 그냥
     * 쓰면 된다고 생각한다.
     *
     * <p><b>재고의 {@code hold_id}를 대조하지 않는 것이 핵심이다.</b> 홀드
     * 단계에서 여러 명이 각자 HELD 행을 갖게 됐는데, 재고 행에는 마지막에 쓴
     * 한 명의 {@code hold_id}만 남는다. 여기서 그 값을 대조하면 한 명만
     * 통과해 초과 확정이 생기지 않는다. 대조하지 않으므로 홀드를 가진 전원이
     * 확정에 성공하고, 같은 좌석이 여러 예약에 팔린다.
     *
     * <p>만료 판정도 자바 쪽에서 한다. DB {@code now()}로 통일하라는
     * concurrency-spec.md 3절의 규칙을 지키지 않는 것 역시 순진한 구현의 특징이다
     * (로컬에서는 두 인스턴스가 호스트 시계를 공유하므로 측정에 잡음을 더하지는
     * 않는다).
     */
    @Override
    public boolean confirmSeat(long sessionId, long seatId, String holdId) {
        // 1) 조회 — 자기 홀드 행. 락 없음.
        SeatHold hold = seatHoldRepository.findByHoldIdAndSeatId(holdId, seatId).orElse(null);
        if (hold == null) {
            return false;
        }

        // 2) 자바 쪽 비교. 재고의 hold_id는 보지 않는다 — 위 주석 참조.
        if (!STATUS_HELD.equals(hold.getStatus())) {
            return false;
        }
        if (hold.getHeldUntil() == null || hold.getHeldUntil().isBefore(Instant.now())) {
            return false;
        }

        // 3) UPDATE — 조건 없음. 앞선 조회가 아직 유효한지 다시 확인하지 않는다.
        seatInventoryRepository.markSoldUnconditionally(sessionId, seatId);
        return true;
    }

    private ErrorCode conflictCodeFor(String seatStatus) {
        if (STATUS_SOLD.equals(seatStatus)) {
            return ErrorCode.SEAT_ALREADY_SOLD;
        }
        if (STATUS_HELD.equals(seatStatus)) {
            return ErrorCode.SEAT_HELD_BY_OTHER;
        }
        // 상태값이 셋 중 어느 것도 아니면 데이터가 깨진 것이다. 조용히 넘기지 않는다.
        throw new IllegalStateException("알 수 없는 seat_inventory.status: " + seatStatus);
    }

    /**
     * 최상위 {@code code}에 담을 대표 사유. 화면이 좌석별 처리를 할 때는 이 값이
     * 아니라 {@code conflicts}를 봐야 한다(api-spec.md 4.2절).
     */
    private ErrorCode representativeCode(List<SeatConflict> conflicts) {
        return conflicts.getFirst().code();
    }
}
