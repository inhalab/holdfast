package com.inhalab.holdfast.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 예약 헤더와 상태. docs/erd.md 2절 REQ-01, REQ-03, REQ-04.
 *
 * <p><b>홀드 시점에 생성된다.</b> {@code status}가 {@code HELD}로 시작해
 * {@code hold_id}로 {@link SeatHold} 그룹과 연결된다(erd.md 4절). 확정 시점에
 * 생성되는 것이 아니다.
 *
 * <p>{@code status}는 {@code HELD/PENDING_PAYMENT/CONFIRMED/CANCELLED/EXPIRED}
 * 다섯 값이며, 전이 규칙(무엇이 무엇으로 갈 수 있는지)은 이 클래스가 아니라
 * docs/state-transitions.md 1절과 그 전이를 수행하는 애플리케이션 로직이 갖는다.
 */
@Entity
@Table(name = "reservation")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /** 사용자 식별자. 사용자 테이블은 없다 — erd.md 4절. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** seat_hold 그룹 참조. */
    @Column(name = "hold_id", nullable = false, length = 36)
    private String holdId;

    /** HELD / PENDING_PAYMENT / CONFIRMED / CANCELLED / EXPIRED. */
    @Column(name = "status", nullable = false, length = 30)
    private String status;

    /** 결제 금액. 요금 정책 미정으로 현재 0 고정(api-spec.md 7절). */
    @Column(name = "total_amount", nullable = false)
    private Long totalAmount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    protected Reservation() {
    }

    public Long getId() {
        return id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getHoldId() {
        return holdId;
    }

    public void setHoldId(String holdId) {
        this.holdId = holdId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(Long totalAmount) {
        this.totalAmount = totalAmount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Instant confirmedAt) {
        this.confirmedAt = confirmedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(Instant cancelledAt) {
        this.cancelledAt = cancelledAt;
    }
}
