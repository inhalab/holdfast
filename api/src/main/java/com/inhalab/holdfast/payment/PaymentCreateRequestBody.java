package com.inhalab.holdfast.payment;

/**
 * {@code POST /api/payments} 요청 본문.
 *
 * <p>{@code holdId}로 받는 이유는 확정({@code POST /api/reservations})과 같다 —
 * 예약 행은 홀드 시점에 생기지만 {@code reservationId}는 확정 응답에서만 노출되므로
 * (api-spec.md 1.2절), 클라이언트가 결제 시점에 들고 있는 식별자는 {@code holdId}다.
 */
public record PaymentCreateRequestBody(String holdId) {
}
