package com.inhalab.holdfast.payment;

/**
 * 결제 시도 상태. docs/state-transitions.md 5절의 상태 기계를 그대로 옮긴다.
 *
 * <p>{@code CANCELLED}는 없다 — {@code payment} 행은 한 번 종결되면 바뀌지 않는
 * 이력이고, 결제 취소는 {@code reservation.status}가 담당한다(erd.md 4절).
 *
 * <p>다섯 값을 모두 선언하지만 <b>최소 완결에서 실제로 도달하는 것은
 * {@code REQUESTED → APPROVED|DECLINED}뿐</b>이다(#79). 나머지 둘을 지금 빼면
 * 여유 항목을 구현할 때 열거형을 고쳐야 하고, DB {@code payment.status}는 이미
 * 다섯 값을 전제로 만들어져 있다.
 *
 * <p>{@code TIMEOUT}을 {@code FAILED}와 분리한 이유는 5.1절에 있다 —
 * {@code TIMEOUT}은 승인 여부를 <b>모르는</b> 상태이고, {@code FAILED}는 승인이
 * 없었음이 <b>확실한</b> 상태다.
 */
public enum PaymentStatus {

    /** Mock PG 호출 직후. 아직 결과가 정해지지 않았다. */
    REQUESTED,

    /** 동기 응답 — 승인. 이 전이가 예약을 확정시킨다. */
    APPROVED,

    /** 동기 응답 — 거절. 예약은 HELD로 남는다. */
    DECLINED,

    /** callback-delay-ms 초과. 승인 여부를 모른다. **여유 항목 — 아직 도달하지 않는다.** */
    TIMEOUT,

    /** 호출 자체의 실패. 승인이 없었음이 확실하다. **여유 항목 — 아직 도달하지 않는다.** */
    FAILED
}
