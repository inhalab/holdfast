package com.inhalab.holdfast.admin;

/** 격자 미리보기의 좌석 한 칸. 이슈 #102. */
public record SeatRow(Long id, Long zoneId, String seatNo, Integer rowIndex, Integer colIndex) {
}
