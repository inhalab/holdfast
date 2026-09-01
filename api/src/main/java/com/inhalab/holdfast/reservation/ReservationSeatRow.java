package com.inhalab.holdfast.reservation;

/**
 * 예약 응답의 좌석 한 건. openapi.yaml {@code ReservationSeat}에 그대로 대응한다.
 *
 * <p>{@code @ManyToOne} 연관 없이 JPQL ad hoc join으로 만든 평면 투영이다
 * (erd.md 4절 — 조회에서 연관 매핑을 추가하지 않는다).
 */
public record ReservationSeatRow(Long seatId, String seatNo, String zoneName) {
}
