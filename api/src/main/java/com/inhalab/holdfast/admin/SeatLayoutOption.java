package com.inhalab.holdfast.admin;

/** 회차 등록 화면의 배치도 선택지. 좌석 수를 함께 보여준다. */
public record SeatLayoutOption(Long id, String name, Long seatCount) {
}
