package com.inhalab.holdfast.reservation;

import com.inhalab.holdfast.api.ApiException;
import com.inhalab.holdfast.api.ErrorCode;
import com.inhalab.holdfast.seat.EventSession;
import com.inhalab.holdfast.seat.EventSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 좌석 홀드의 <b>전략 밖 공통 단계</b>. concurrency-spec.md 1.1·5.1절.
 *
 * <p>여기서 하는 일은 5개 전략 전부에서 동일하다. 전략별 분기를 두지 않는다 —
 * "k6 시나리오는 고정하고 프로퍼티만 바꿔 측정한다"(4절)는 전제가 성립하려면
 * 전략 밖 코드 경로가 전략에 따라 갈라지면 안 된다.
 *
 * <h2>전역 락 순서</h2>
 *
 * <p><b>사용자 할당량 행(CS-6) → 좌석 행(오름차순)</b>이다(5.1). 이 클래스가 앞
 * 단계를, 전략이 뒤 단계를 맡는다.
 *
 * <ol>
 *   <li>좌석 ID 오름차순 정렬 — 5.1</li>
 *   <li>예약 오픈 시각 검사 — REQ-08</li>
 *   <li>사용자 할당량 행 선행 락 + 1인 최대 매수 검사 — 1.1, REQ-03</li>
 *   <li>전략 위임 — 여기서부터가 락 전략 비교 대상이다</li>
 *   <li>전부 아니면 전무 판정, 예약 행 생성, 할당량 반영</li>
 * </ol>
 */
@Service
public class SeatHoldService {

    private final SeatHoldStrategy strategy;
    private final EventSessionRepository eventSessionRepository;
    private final UserSessionQuotaRepository userSessionQuotaRepository;
    private final SeatInventoryRepository seatInventoryRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;

    public SeatHoldService(SeatHoldStrategy strategy,
                           EventSessionRepository eventSessionRepository,
                           UserSessionQuotaRepository userSessionQuotaRepository,
                           SeatInventoryRepository seatInventoryRepository,
                           SeatHoldRepository seatHoldRepository,
                           ReservationRepository reservationRepository,
                           ReservationSeatRepository reservationSeatRepository) {
        this.strategy = strategy;
        this.eventSessionRepository = eventSessionRepository;
        this.userSessionQuotaRepository = userSessionQuotaRepository;
        this.seatInventoryRepository = seatInventoryRepository;
        this.seatHoldRepository = seatHoldRepository;
        this.reservationRepository = reservationRepository;
        this.reservationSeatRepository = reservationSeatRepository;
    }

    /**
     * 좌석을 홀드한다.
     *
     * @throws ApiException 정상 거절. 트랜잭션이 롤백되어 부분 성공이 남지
     *                      않는다(api-spec.md 4절 — 전부 아니면 전무).
     */
    @Transactional
    public HoldResult hold(long sessionId, long userId, List<Long> seatIds, String holdId) {
        if (seatIds == null || seatIds.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "좌석을 하나 이상 지정해야 합니다.");
        }

        // 1) 좌석 ID 오름차순 정렬(5.1). 중복은 제거한다 — 같은 좌석을 두 번 넣은
        //    요청이 스스로와 경합해 자기 자신을 초과 홀드로 만드는 것은 측정하려는
        //    경합이 아니다.
        List<Long> ordered = seatIds.stream().distinct().sorted().toList();

        EventSession session = eventSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ApiException(ErrorCode.SESSION_NOT_FOUND));

        // 2) 예약 오픈 검사(REQ-08). 시각 기준은 서버다 — 클라이언트 시계를
        //    신뢰하면 오픈 전 예약이 통과한다(design-spec.md 5.1).
        if (session.getReserveOpensAt() != null && Instant.now().isBefore(session.getReserveOpensAt())) {
            throw new ApiException(ErrorCode.RESERVATION_NOT_OPEN);
        }

        // 3) 사용자 할당량 행 선행 락(CS-6). 좌석 행보다 먼저 잠근다.
        UserSessionQuota quota = userSessionQuotaRepository.findForUpdate(sessionId, userId)
                .orElseThrow(() -> new IllegalStateException(
                        "할당량 행이 없습니다. 시드가 사전 생성해야 한다(concurrency-spec 1.1). "
                                + "sessionId=" + sessionId + " userId=" + userId));

        int requested = ordered.size();
        if (quota.getHeldCount() + requested > session.getMaxPerUser()) {
            throw new ApiException(ErrorCode.QUOTA_EXCEEDED,
                    "1인 최대 " + session.getMaxPerUser() + "매까지 예약할 수 있습니다. 현재 "
                            + quota.getHeldCount() + "매 보유 중입니다.");
        }

        // 4) 전략 위임. 여기서부터가 비교 대상이다.
        HoldResult result = strategy.hold(new HoldCommand(sessionId, userId, ordered, holdId));

        // 5) 전부 아니면 전무. 예외를 던져 트랜잭션째로 되돌린다.
        if (!result.success()) {
            throw new ApiException(result.code(), result.conflicts());
        }

        createReservation(sessionId, userId, holdId, ordered);

        quota.setHeldCount(quota.getHeldCount() + requested);
        userSessionQuotaRepository.save(quota);

        return result;
    }

    /**
     * 예약 행을 <b>홀드 시점에</b> 만든다(erd.md 4절). API는 확정 응답에서만
     * {@code reservationId}를 노출하지만, 행 자체는 여기서 생긴다 — 내부 상태와
     * 계약 표면을 분리한 것이다(api-spec.md 1.2절).
     *
     * <p>{@code reservation_seat}도 함께 만든다. <b>초과 예약 검증(V-1)이 이
     * 테이블을 근거로 삼기 때문이다</b> — {@code load-test/sql/verify.sql}이
     * 확정된 예약과 조인해 같은 재고 행이 두 건 이상 팔렸는지 센다. 이 행이 없으면
     * 베이스라인 측정의 핵심 지표가 0으로 나온다.
     */
    private void createReservation(long sessionId, long userId, String holdId, List<Long> seatIds) {
        Reservation reservation = new Reservation();
        reservation.setSessionId(sessionId);
        reservation.setUserId(userId);
        reservation.setHoldId(holdId);
        reservation.setStatus("HELD");
        reservation.setTotalAmount(0L);
        reservation.setCreatedAt(Instant.now());
        Reservation saved = reservationRepository.save(reservation);

        for (Long seatId : seatIds) {
            SeatInventory inventory = seatInventoryRepository.findBySessionIdAndSeatId(sessionId, seatId)
                    .orElseThrow(() -> new ApiException(ErrorCode.SEAT_NOT_IN_SESSION));
            ReservationSeat reservationSeat = new ReservationSeat();
            reservationSeat.setReservationId(saved.getId());
            reservationSeat.setSeatInventoryId(inventory.getId());
            reservationSeatRepository.save(reservationSeat);
        }
    }

    /**
     * 홀드를 자진 해제한다. {@code DELETE /api/holds/{holdId}}.
     *
     * <p><b>멱등하다.</b> 이미 해제·만료된 홀드에 다시 호출해도 조용히 성공한다 —
     * 재시도가 실패로 보이지 않아야 한다(api-spec.md 6.1절).
     *
     * <p>전이는 state-transitions.md 1·2·3절을 따른다. {@code seat_hold}는
     * {@code RELEASED}, {@code seat_inventory}는 {@code AVAILABLE},
     * {@code reservation}은 {@code CANCELLED}, 할당량은 감소한다.
     */
    @Transactional
    public void release(String holdId, long userId) {
        List<SeatHold> holds = seatHoldRepository.findByHoldIdOrderBySeatIdAsc(holdId);
        if (holds.isEmpty()) {
            throw new ApiException(ErrorCode.HOLD_NOT_FOUND);
        }
        if (holds.getFirst().getUserId() != userId) {
            // 남의 홀드는 존재 자체를 흘리지 않는다.
            throw new ApiException(ErrorCode.HOLD_NOT_FOUND);
        }
        if (holds.stream().anyMatch(hold -> "CONFIRMED".equals(hold.getStatus()))) {
            throw new ApiException(ErrorCode.HOLD_ALREADY_CONFIRMED);
        }
        if (holds.stream().allMatch(hold -> "RELEASED".equals(hold.getStatus()))) {
            return; // 이미 해제됨. 멱등하게 성공으로 둔다.
        }

        long sessionId = holds.getFirst().getSessionId();

        // 할당량 행을 먼저 잠근다 — 전역 락 순서(5.1)는 해제 경로에서도 같다.
        UserSessionQuota quota = userSessionQuotaRepository.findForUpdate(sessionId, userId)
                .orElseThrow(() -> new IllegalStateException("할당량 행이 없습니다. holdId=" + holdId));

        int released = seatHoldRepository.releaseByHoldId(holdId);
        for (SeatHold hold : holds) {
            seatInventoryRepository.releaseHeld(sessionId, hold.getSeatId(), holdId);
        }

        reservationRepository.findByHoldId(holdId).ifPresent(reservation -> {
            reservation.setStatus("CANCELLED");
            reservation.setCancelledAt(Instant.now());
            reservationRepository.save(reservation);
        });

        quota.setHeldCount(Math.max(0, quota.getHeldCount() - released));
        userSessionQuotaRepository.save(quota);
    }
}
