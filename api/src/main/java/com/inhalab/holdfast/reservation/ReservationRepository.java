package com.inhalab.holdfast.reservation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 예약 저장소.
 *
 * <p>예약 행은 <b>홀드 시점에</b> 생성된다(erd.md 4절). API가
 * {@code reservationId}를 확정 응답에서만 노출하는 것과 별개다 — 내부 상태와
 * 계약 표면을 분리한 것이다(api-spec.md 1.2절).
 */
public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    /** 홀드 그룹으로 예약을 찾는다. U-6이 홀드 그룹당 예약 1건을 보장한다. */
    Optional<Reservation> findByHoldId(String holdId);
}
