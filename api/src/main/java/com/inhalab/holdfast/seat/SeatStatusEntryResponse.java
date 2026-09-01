package com.inhalab.holdfast.seat;

/**
 * openapi.yaml {@code SeatStatusEntry}. 폴링 응답의 좌석당 필드 2개 중 하나다.
 */
public record SeatStatusEntryResponse(Long seatId, String status) {
}
