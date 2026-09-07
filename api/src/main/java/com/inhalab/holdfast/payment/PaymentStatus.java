package com.inhalab.holdfast.payment;

/**
 * 결제 시도 상태. docs/state-transitions.md 5절의 상태 기계를 그대로 옮긴다.
 *
 * <p>{@code CANCELLED}는 없다 — {@code payment} 행은 한 번 종결되면 바뀌지 않는
 * 이력이고, 결제 취소는 {@code reservation.status}가 담당한다(erd.md 4절).
 *
 * <h2>환불 상태도 없다 — 이슈 #106에서 다시 검토하고 유지했다</h2>
 *
 * <p>#106이 요구한 것이 이 열거형에 환불 상태를 더하는 일이었고, <b>더하지 않기로
 * 판정했다.</b> 근거 넷은 erd.md 4절에 있으며 요지는 하나다 — <b>환불 상태는
 * {@code reservation.status='CANCELLED'}와 이 행의 {@code APPROVED}의 순수
 * 함수라 새 사실을 담지 않는다.</b> 부분취소가 없어 금액이 갈리지 않고, 실제
 * PG가 없어 환불이 실패할 수 없으며, 시각은 {@code cancelled_at}이 들고 있다.
 *
 * <p><b>{@link com.inhalab.holdfast.ticket.TicketStatus}의 {@code VOID}와 같은
 * 모양이다.</b> 둘 다 설계 단계에 잡았다가 뺐고, 뺀 자리를 조회 시점의 판정이
 * 메우며, 둘 다 취소 트랜잭션에 쓰기를 더하지 않아 CS-4의 임계 구역을 늘리지
 * 않는다.
 *
 * <p>이 판단은 {@code MinimumScopeFlowTest#cancellationDoesNotTouchPayment}가
 * 고정한다 — 취소해도 이 행은 {@code APPROVED} 그대로다.
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
