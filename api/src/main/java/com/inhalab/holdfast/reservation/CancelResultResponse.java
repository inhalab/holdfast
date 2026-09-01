package com.inhalab.holdfast.reservation;

import java.time.Instant;
import java.util.List;

/** openapi.yaml {@code CancelResult}. */
public record CancelResultResponse(
        Long reservationId,
        String status,
        Instant cancelledAt,
        List<Long> releasedSeatIds,
        Instant serverTime
) {
}
