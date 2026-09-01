package com.inhalab.holdfast.seat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 회차. docs/erd.md 2절 REQ-02, REQ-06, REQ-08.
 *
 * {@code seatLayoutId}를 갖는 이유는 erd.md 4절에 있다 — seat_inventory를
 * 회차 × 좌석 행으로 사전 생성하려면 그 회차에 어떤 좌석이 속하는지 알아야
 * 하고, 그 출처가 배치도다.
 *
 * {@code status}는 문자열로만 둔다({@code SCHEDULED/OPEN/CLOSED}). 전이 규칙은
 * 이 클래스가 아니라 애플리케이션 로직이 갖는다.
 */
@Entity
@Table(name = "event_session")
public class EventSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "program_id", nullable = false)
    private Long programId;

    @Column(name = "seat_layout_id", nullable = false)
    private Long seatLayoutId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "entry_opens_at", nullable = false)
    private Instant entryOpensAt;

    @Column(name = "entry_closes_at", nullable = false)
    private Instant entryClosesAt;

    @Column(name = "reserve_opens_at", nullable = false)
    private Instant reserveOpensAt;

    @Column(name = "max_per_user", nullable = false)
    private Integer maxPerUser;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    protected EventSession() {
    }

    public Long getId() {
        return id;
    }

    public Long getProgramId() {
        return programId;
    }

    public void setProgramId(Long programId) {
        this.programId = programId;
    }

    public Long getSeatLayoutId() {
        return seatLayoutId;
    }

    public void setSeatLayoutId(Long seatLayoutId) {
        this.seatLayoutId = seatLayoutId;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public void setStartsAt(Instant startsAt) {
        this.startsAt = startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public void setEndsAt(Instant endsAt) {
        this.endsAt = endsAt;
    }

    public Instant getEntryOpensAt() {
        return entryOpensAt;
    }

    public void setEntryOpensAt(Instant entryOpensAt) {
        this.entryOpensAt = entryOpensAt;
    }

    public Instant getEntryClosesAt() {
        return entryClosesAt;
    }

    public void setEntryClosesAt(Instant entryClosesAt) {
        this.entryClosesAt = entryClosesAt;
    }

    public Instant getReserveOpensAt() {
        return reserveOpensAt;
    }

    public void setReserveOpensAt(Instant reserveOpensAt) {
        this.reserveOpensAt = reserveOpensAt;
    }

    public Integer getMaxPerUser() {
        return maxPerUser;
    }

    public void setMaxPerUser(Integer maxPerUser) {
        this.maxPerUser = maxPerUser;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
