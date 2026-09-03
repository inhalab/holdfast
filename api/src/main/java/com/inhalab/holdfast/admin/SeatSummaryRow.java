package com.inhalab.holdfast.admin;

/** 회차별 좌석 상태 집계 한 건. */
public record SeatSummaryRow(Long sessionId, String status, Long count) {
}
