package com.inhalab.holdfast.catalog;

/** 회차 하나의 좌석 상태별 개수. 회차 목록의 "잔여 N / 전체 M"을 만드는 재료다. */
public record SessionAvailabilityRow(Long sessionId, String status, Long count) {
}
