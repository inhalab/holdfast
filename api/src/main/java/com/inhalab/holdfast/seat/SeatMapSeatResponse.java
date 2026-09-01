package com.inhalab.holdfast.seat;

/**
 * openapi.yaml {@code SeatMapSeat}. 필드명·순서를 계약과 그대로 맞춘다.
 */
public record SeatMapSeatResponse(
        Long seatId,
        String seatNo,
        Integer rowIndex,
        Integer colIndex,
        String status
) {
}
