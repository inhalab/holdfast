package com.inhalab.holdfast.reservation;

/** openapi.yaml {@code ReservationCreateRequest}. 확정할 선점의 식별자만 받는다. */
public record ReservationCreateRequestBody(String holdId) {
}
