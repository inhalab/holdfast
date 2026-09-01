package com.inhalab.holdfast.seat;

import java.time.Instant;
import java.util.List;

/**
 * openapi.yaml {@code SeatMap}. {@code GET /api/sessions/{sessionId}/seats} 응답이다.
 * 화면 최초 진입 시 1회 받는 전체 조회이며, 3초 폴링에는 쓰지 않는다
 * (api-spec.md 5절).
 */
public record SeatMapResponse(
        Long sessionId,
        String sessionStatus,
        Instant reserveOpensAt,
        Instant startsAt,
        Instant endsAt,
        Integer maxPerUser,
        Integer holdTtlSeconds,
        Instant serverTime,
        List<SeatMapZoneResponse> zones
) {
}
