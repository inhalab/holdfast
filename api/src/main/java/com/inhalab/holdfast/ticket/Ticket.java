package com.inhalab.holdfast.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * QR 모바일 티켓. docs/erd.md 2절 REQ-07.
 *
 * <p>예약좌석당 1장이다(U-9 {@code ux_ticket_reservation_seat}). 예약당 1장이
 * 아니라 예약좌석당 1장인 이유는 erd.md 4절에 있다 — 그래야 검표 시 중복 사용
 * 차단이 {@link TicketScan}의 U-11 하나로 처리된다.
 *
 * <p>발권·검표 도메인은 이 문서 작성 시점에 API 계약이 아직 없다
 * (docs/state-transitions.md 4절 "부분 확정"). 여기서는 스키마 매핑만 정의한다.
 */
@Entity
@Table(name = "ticket")
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_seat_id", nullable = false)
    private Long reservationSeatId;

    @Column(name = "qr_token", nullable = false, length = 255)
    private String qrToken;

    /** {@link TicketStatus} 이름. ISSUED / USED — VOID는 두지 않는다. */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "used_at")
    private Instant usedAt;

    protected Ticket() {
    }

    public Long getId() {
        return id;
    }

    public Long getReservationSeatId() {
        return reservationSeatId;
    }

    public void setReservationSeatId(Long reservationSeatId) {
        this.reservationSeatId = reservationSeatId;
    }

    public String getQrToken() {
        return qrToken;
    }

    public void setQrToken(String qrToken) {
        this.qrToken = qrToken;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public void setIssuedAt(Instant issuedAt) {
        this.issuedAt = issuedAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public void setUsedAt(Instant usedAt) {
        this.usedAt = usedAt;
    }
}
