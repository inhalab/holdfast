package com.inhalab.holdfast.api;

/**
 * 좌석별 거절 사유. api-spec.md 4.2절의 {@code conflicts} 배열 한 항목이며
 * {@code POST /api/holds}의 409 응답에만 들어간다.
 *
 * <p>전부 아니면 전무라 좌석이 하나도 잡히지 않았을 때, 화면이 "무엇 때문에
 * 실패했는지"를 좌석 단위로 표시할 수 있게 하는 것이 이 타입의 목적이다.
 * <b>화면이 좌석별 처리를 할 때는 최상위 {@code code}가 아니라 이 배열을 본다.</b>
 */
public record SeatConflict(long seatId, ErrorCode code) {
}
