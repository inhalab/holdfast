package com.inhalab.holdfast.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 홀드 이력과 유일성의 정본. docs/erd.md 2절 REQ-01, REQ-03, REQ-09.
 *
 * <p><b>이 테이블이 정본이고 {@link SeatInventory}가 파생이다</b>
 * (docs/state-transitions.md 0절). "누가 이 좌석을 정당하게 점유하고 있는가"의
 * 근거는 이 테이블의 행과 U-2 부분 유니크 인덱스
 * ({@code ux_seat_hold_active}, {@code WHERE status = 'HELD'})에 있다.
 *
 * <p>{@code status}는 {@code HELD/CONFIRMED/RELEASED} 셋뿐이다.
 * {@code RELEASED}는 "활성 홀드가 아니게 된 모든 경우"(만료·자진 해제·취소)를
 * 가리키는 값 하나이며, 그 경우들 사이의 구분은 이 테이블이 아니라
 * {@link Reservation#getStatus()}가 담당한다(docs/state-transitions.md 3절) —
 * 홀드 레코드의 생명주기와 해제 사유를 한 컬럼에 섞지 않는다.
 *
 * <p>이 엔티티에도 상태 전이 로직을 넣지 않는다. {@code SeatInventory}와 같은
 * 이유다.
 */
@Entity
@Table(name = "seat_hold")
public class SeatHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    /** 홀드 그룹 식별자. 여러 좌석을 한 번에 홀드할 때 공유된다. */
    @Column(name = "hold_id", nullable = false, length = 36)
    private String holdId;

    /** CS-6 집계용. 사용자 테이블은 없다 — erd.md 4절. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "held_until", nullable = false)
    private Instant heldUntil;

    /** HELD / CONFIRMED / RELEASED. */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    protected SeatHold() {
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

    public Long getSeatId() {
        return seatId;
    }

    public void setSeatId(Long seatId) {
        this.seatId = seatId;
    }

    public String getHoldId() {
        return holdId;
    }

    public void setHoldId(String holdId) {
        this.holdId = holdId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Instant getHeldUntil() {
        return heldUntil;
    }

    public void setHeldUntil(Instant heldUntil) {
        this.heldUntil = heldUntil;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
