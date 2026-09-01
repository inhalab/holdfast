package com.inhalab.holdfast.reservation;

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
 * <h2>실패가 발생하는 지점</h2>
 *
 * <p>조회와 UPDATE 사이에 틈이 있다. 두 요청이 같은 좌석을 동시에 조회하면
 * 둘 다 {@code AVAILABLE}을 보고 둘 다 통과한 뒤 각자 UPDATE·INSERT한다.
 * Postgres가 UPDATE를 행 단위로 직렬화하지만, 그때는 이미 두 요청 모두 자바
 * 쪽 검사를 지난 뒤라 아무것도 막지 못한다. 결과는 같은 좌석에 대한 HELD
 * {@code seat_hold} 행 여러 개 — 초과 홀드다.
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
                conflicts.add(new SeatConflict(seatId, "SEAT_NOT_IN_SESSION"));
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

    private String conflictCodeFor(String seatStatus) {
        if (STATUS_SOLD.equals(seatStatus)) {
            return "SEAT_ALREADY_SOLD";
        }
        if (STATUS_HELD.equals(seatStatus)) {
            return "SEAT_HELD_BY_OTHER";
        }
        // 상태값이 셋 중 어느 것도 아니면 데이터가 깨진 것이다. 조용히 넘기지 않는다.
        throw new IllegalStateException("알 수 없는 seat_inventory.status: " + seatStatus);
    }

    /**
     * 최상위 {@code code}에 담을 대표 사유. 화면이 좌석별 처리를 할 때는 이 값이
     * 아니라 {@code conflicts}를 봐야 한다(api-spec.md 4.2절).
     */
    private String representativeCode(List<SeatConflict> conflicts) {
        return conflicts.getFirst().code();
    }
}
