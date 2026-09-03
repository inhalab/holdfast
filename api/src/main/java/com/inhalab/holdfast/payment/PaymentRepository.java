package com.inhalab.holdfast.payment;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 결제 시도 저장소.
 *
 * <p>예약당 N건이다 — 재시도마다 새 행이 생기고 기존 행을 재사용하지 않는다
 * (state-transitions.md 5절). 그래서 {@code findByReservationId} 같은 단건 조회를
 * 두지 않는다. 필요해지면 목록으로 받아야 한다.
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {
}
