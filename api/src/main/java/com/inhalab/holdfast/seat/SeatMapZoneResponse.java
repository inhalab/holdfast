package com.inhalab.holdfast.seat;

import java.util.List;

/**
 * openapi.yaml {@code SeatMapZone}.
 */
public record SeatMapZoneResponse(
        Long zoneId,
        String name,
        Integer sortOrder,
        List<SeatMapSeatResponse> seats
) {
}
