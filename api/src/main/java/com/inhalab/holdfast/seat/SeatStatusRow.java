package com.inhalab.holdfast.seat;

/**
 * 폴링 전용 평면 투영. {@code seatId}·{@code status} 두 값뿐이다 — 정적 정보를
 * 다시 조인하지 않는다(api-spec.md 5.1절, 좌석당 필드 2개).
 */
public record SeatStatusRow(Long seatId, String status) {
}
