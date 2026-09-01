package com.inhalab.holdfast.reservation;

import com.inhalab.holdfast.api.ApiException;
import com.inhalab.holdfast.api.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 예약 확정·조회·취소. state-transitions.md 1~3절.
 *
 * <p>이 세 경로는 <b>락 전략과 무관하다.</b> "전략 인터페이스가 감싸는 것은
 * {@code hold()} 메서드 하나뿐"이고(concurrency-spec.md 4절), 나머지 전이는 5개
 * 전략에서 동일하게 처리된다(state-transitions.md 2절). 그래서 여기에는 전략
 * 주입도, 전략별 분기도 없다.
 */
@Service
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final SeatInventoryRepository seatInventoryRepository;
    private final UserSessionQuotaRepository userSessionQuotaRepository;

    public ReservationService(ReservationRepository reservationRepository,
                              ReservationSeatRepository reservationSeatRepository,
                              SeatHoldRepository seatHoldRepository,
                              SeatInventoryRepository seatInventoryRepository,
                              UserSessionQuotaRepository userSessionQuotaRepository) {
        this.reservationRepository = reservationRepository;
        this.reservationSeatRepository = reservationSeatRepository;
        this.seatHoldRepository = seatHoldRepository;
        this.seatInventoryRepository = seatInventoryRepository;
        this.userSessionQuotaRepository = userSessionQuotaRepository;
    }

    /**
     * 예약을 확정한다. {@code POST /api/reservations}. CS-2 — 가장 경합이 심한 구간이다.
     *
     * <p>만료 판정은 <b>lazy 검증</b>으로 한다(concurrency-spec.md 3절). 확정
     * 쿼리 자체에 {@code held_until > now()} 조건을 넣어, 만료(CS-3)와 확정(CS-2)이
     * 동시에 일어나도 단일 SQL 문 안에서 원자적으로 해소된다. 조회로 만료를 먼저
     * 확인한 뒤 확정하면 그 사이에 만료가 일어날 수 있고, 그것이 이 프로젝트에서
     * 가장 자주 터지는 경합이다.
     */
    @Transactional
    public Reservation confirm(String holdId, long userId) {
        List<SeatHold> holds = seatHoldRepository.findByHoldIdOrderBySeatIdAsc(holdId);
        if (holds.isEmpty() || holds.getFirst().getUserId() != userId) {
            // 남의 홀드는 존재 자체를 흘리지 않는다.
            throw new ApiException(ErrorCode.HOLD_NOT_FOUND);
        }
        if (holds.stream().anyMatch(hold -> "CONFIRMED".equals(hold.getStatus()))) {
            throw new ApiException(ErrorCode.HOLD_ALREADY_CONFIRMED);
        }
        if (holds.stream().anyMatch(hold -> "RELEASED".equals(hold.getStatus()))) {
            throw new ApiException(ErrorCode.HOLD_RELEASED);
        }

        Reservation reservation = reservationRepository.findByHoldId(holdId)
                .orElseThrow(() -> new ApiException(ErrorCode.HOLD_NOT_FOUND));
        long sessionId = reservation.getSessionId();

        // 좌석 오름차순으로 확정한다 — 전역 락 순서(5.1)는 확정 경로에서도 같다.
        for (SeatHold hold : holds) {
            int confirmed = seatInventoryRepository.confirmIfStillHeld(sessionId, hold.getSeatId(), holdId);
            if (confirmed == 0) {
                // 홀드가 만료됐거나 남의 홀드다. 전부 아니면 전무이므로
                // 예외를 던져 앞서 확정한 좌석까지 함께 되돌린다.
                throw new ApiException(ErrorCode.HOLD_EXPIRED);
            }
        }

        seatHoldRepository.confirmHeld(holdId);

        reservation.setStatus("CONFIRMED");
        reservation.setConfirmedAt(Instant.now());
        return reservationRepository.save(reservation);
    }

    /**
     * 예약을 조회한다. 남의 예약이면 존재 여부를 흘리지 않기 위해 403이 아니라
     * 404를 반환한다(openapi.yaml {@code getReservation}).
     */
    @Transactional(readOnly = true)
    public Reservation get(long reservationId, long userId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESERVATION_NOT_FOUND));
        if (reservation.getUserId() != userId) {
            throw new ApiException(ErrorCode.RESERVATION_NOT_FOUND);
        }
        return reservation;
    }

    public List<ReservationSeatRow> seatsOf(long reservationId) {
        return reservationSeatRepository.findSeatRows(reservationId);
    }

    /**
     * 예약을 취소하고 좌석을 반환한다. {@code POST /api/reservations/{id}/cancel}.
     *
     * <p><b>멱등하다.</b> 이미 취소된 예약에 다시 호출하면 409가 아니라 기존
     * 취소 결과를 그대로 돌려준다 — 재시도가 실패로 보이지 않아야 한다
     * (api-spec.md 6.1절).
     *
     * <p>{@code seat_hold}는 {@code CONFIRMED → RELEASED}로 간다.
     * {@code RELEASED}는 "활성 홀드가 아니게 된 모든 경우"를 가리키는 값 하나이며,
     * 만료였는지 취소였는지는 {@code reservation.status}가 구분한다
     * (state-transitions.md 1절).
     */
    @Transactional
    public CancelOutcome cancel(long reservationId, long userId) {
        Reservation reservation = get(reservationId, userId);

        List<Long> seatIds = seatsOf(reservationId).stream().map(ReservationSeatRow::seatId).toList();

        if ("CANCELLED".equals(reservation.getStatus())) {
            return new CancelOutcome(reservation, seatIds); // 멱등
        }
        if (!"CONFIRMED".equals(reservation.getStatus()) && !"HELD".equals(reservation.getStatus())) {
            throw new ApiException(ErrorCode.RESERVATION_NOT_CANCELLABLE);
        }

        // 할당량 행을 먼저 잠근다 — 전역 락 순서(5.1)는 취소 경로에서도 같다.
        UserSessionQuota quota = userSessionQuotaRepository
                .findForUpdate(reservation.getSessionId(), userId)
                .orElseThrow(() -> new IllegalStateException("할당량 행이 없습니다. reservationId=" + reservationId));

        for (ReservationSeat reservationSeat : reservationSeatRepository.findByReservationId(reservationId)) {
            seatInventoryRepository.releaseSold(reservationSeat.getSeatInventoryId());
        }
        seatHoldRepository.releaseByHoldId(reservation.getHoldId());

        reservation.setStatus("CANCELLED");
        reservation.setCancelledAt(Instant.now());
        Reservation saved = reservationRepository.save(reservation);

        // 취소하면 그 좌석은 더 이상 보유분이 아니다. 되돌리지 않으면 사용자의
        // 1인 최대 매수가 영구히 소모된다(REQ-03).
        quota.setHeldCount(Math.max(0, quota.getHeldCount() - seatIds.size()));
        userSessionQuotaRepository.save(quota);

        return new CancelOutcome(saved, seatIds);
    }

    /** 취소 결과. 반환된 좌석 목록을 응답의 {@code releasedSeatIds}에 쓴다. */
    public record CancelOutcome(Reservation reservation, List<Long> releasedSeatIds) {
    }
}
