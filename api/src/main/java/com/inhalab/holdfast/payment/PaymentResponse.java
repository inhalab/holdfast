package com.inhalab.holdfast.payment;

import java.time.Instant;

/**
 * {@code POST /api/payments}의 201 응답.
 *
 * <p><b>거절은 오류가 아니라 결과다.</b> {@code DECLINED}도 201로 나가고 상태는
 * {@code status}로 구분한다. 409로 내보내지 않는 이유는 좌석 경합이 아니기
 * 때문이다 — concurrency-spec.md 7.1이 정상 거절(409)·락 포기·오류(5xx)를 나눈
 * 것은 <b>경합 지표</b>를 오염시키지 않기 위해서인데, 결제 거절을 409에 섞으면
 * k6의 409율에 좌석 경합과 무관한 건수가 들어간다.
 *
 * @param reservationStatus 결제 후의 예약 상태. 승인이면 {@code CONFIRMED},
 *                          거절이면 {@code HELD} 그대로다.
 * @param approvedAt        승인 시각. 거절이면 {@code null}.
 */
public record PaymentResponse(
        Long paymentId,
        String pgTxId,
        PaymentStatus status,
        Long reservationId,
        String reservationStatus,
        Long amount,
        Instant approvedAt,
        Instant serverTime
) {
}
