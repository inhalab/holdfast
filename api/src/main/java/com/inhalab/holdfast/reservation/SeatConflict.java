package com.inhalab.holdfast.reservation;

/**
 * 좌석별 거절 사유. api-spec.md 4.2절의 {@code conflicts} 배열 한 항목이다.
 *
 * @param code openapi.yaml {@code ErrorCode} 값 — {@code SEAT_ALREADY_SOLD},
 *             {@code SEAT_HELD_BY_OTHER} 등
 */
public record SeatConflict(long seatId, String code) {
}
