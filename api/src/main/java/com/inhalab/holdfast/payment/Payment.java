package com.inhalab.holdfast.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Mock PG 결제 시도. docs/erd.md 2절 REQ-04.
 * docs/state-transitions.md 5절이 이 인터페이스의 정본이다.
 *
 * <p>예약당 N건이다 — 재시도마다 새 행이 생기고 기존 행이 재사용되지 않는다
 * (erd.md 4절). {@code status}는 {@code REQUESTED/APPROVED/DECLINED/TIMEOUT/
 * FAILED}이고 {@code CANCELLED}는 없다 — 결제 취소는
 * {@code reservation.status}가 담당하며, 이 엔티티는 승인·거절·실패로
 * 한 번 종결되면 그 자체로는 바뀌지 않는 이력이다.
 *
 * <p>{@code TIMEOUT}을 {@code FAILED}와 분리한 이유는
 * docs/state-transitions.md 5.1절에 있다 — {@code TIMEOUT}은 승인 여부를
 * 모르는 상태이고, {@code FAILED}는 승인이 없었음이 확실한 상태다.
 */
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    /** PG 거래 ID. U-8로 유니크 — 콜백 멱등키(concurrency-spec.md 6절). */
    @Column(name = "pg_tx_id", nullable = false, length = 100)
    private String pgTxId;

    /** REQUESTED / APPROVED / DECLINED / TIMEOUT / FAILED. */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 승인 시각. nullable — APPROVED로 전이하지 않으면 없다. */
    @Column(name = "approved_at")
    private Instant approvedAt;

    protected Payment() {
    }

    public Long getId() {
        return id;
    }

    public Long getReservationId() {
        return reservationId;
    }

    public void setReservationId(Long reservationId) {
        this.reservationId = reservationId;
    }

    public String getPgTxId() {
        return pgTxId;
    }

    public void setPgTxId(String pgTxId) {
        this.pgTxId = pgTxId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getAmount() {
        return amount;
    }

    public void setAmount(Long amount) {
        this.amount = amount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }
}
