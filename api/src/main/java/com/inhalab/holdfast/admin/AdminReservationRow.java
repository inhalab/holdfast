package com.inhalab.holdfast.admin;

import com.inhalab.holdfast.reservation.ReservationSeatRow;

import java.time.Instant;
import java.util.List;

/** 관리자 화면에 뿌릴 예약 한 건. 화면 전용 평면 투영이다. */
public record AdminReservationRow(
        Long reservationId,
        Long sessionId,
        Long userId,
        String status,
        Long totalAmount,
        Instant createdAt,
        Instant confirmedAt,
        Instant cancelledAt,
        List<ReservationSeatRow> seats
) {
}
