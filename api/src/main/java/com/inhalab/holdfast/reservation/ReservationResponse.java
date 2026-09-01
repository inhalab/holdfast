package com.inhalab.holdfast.reservation;

import java.time.Instant;
import java.util.List;

/**
 * openapi.yaml {@code Reservation}.
 *
 * <p>{@code reservationId}는 여기서 처음 노출된다. 예약 행 자체는 홀드 시점에
 * 생기지만(erd.md 4절) 클라이언트는 확정 전까지 {@code holdId}만 다룬다 —
 * 내부 상태와 계약 표면의 분리다(api-spec.md 1.2절).
 */
public record ReservationResponse(
        Long reservationId,
        Long sessionId,
        Long userId,
        String status,
        List<ReservationSeatRow> seats,
        Long totalAmount,
        Instant confirmedAt,
        Instant cancelledAt,
        Instant serverTime
) {
}
