package com.inhalab.holdfast.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 좌석재고. 좌석 단위 점유의 경합 대상(CS-1). docs/erd.md 2절 REQ-01, REQ-02, REQ-09.
 *
 * <p><b>파생값이다.</b> 정본은 {@link SeatHold}다 — 근거는
 * docs/state-transitions.md 0절. 이 엔티티의 {@code status}는 좌석맵 조회처럼
 * 빈번한 읽기를 위해 같은 트랜잭션에서 함께 갱신되는 읽기 최적화 프로젝션이며,
 * 점유의 유일성 자체는 {@code seat_hold}의 U-2 부분 인덱스가 담당한다.
 *
 * <p><b>상태 전이 로직을 이 클래스에 넣지 않는다.</b> {@code AVAILABLE → HELD}
 * 전이(CS-1)를 지키는 방법이 락 전략마다 다르다 —
 * {@code pessimistic}은 {@code FOR UPDATE}, {@code optimistic}은
 * {@code version} 비교를 포함한 명시적 조건부 UPDATE, {@code unique}는 앱
 * 레벨 락 없이 {@code seat_hold} INSERT의 제약 위반으로 사후 검출,
 * {@code redis}는 {@code RLock}이다(concurrency-spec.md 4절). 엔티티가
 * 전이를 강제하면 전략을 갈아 끼울 수 없다.
 *
 * <p><b>{@code version}은 JPA {@code @Version}이 아니다.</b> 낙관적 락 전략의
 * 명시적 조건부 UPDATE
 * ({@code UPDATE ... SET version = version + 1 WHERE id = ? AND version = ?})
 * 에서 애플리케이션이 직접 다루는 일반 컬럼이다(concurrency-spec.md 4.3). JPA의
 * 자동 버전 검사로 매핑하면 Hibernate가 모든 저장에 낙관적 락을 강제로 걸어,
 * 락 전략을 바꿔도 낙관적 락 하나가 항상 함께 켜져 있게 된다.
 */
@Entity
@Table(name = "seat_inventory")
public class SeatInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    /** AVAILABLE / HELD / SOLD. concurrency-spec.md 2절. */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** 현재 홀드 식별자. nullable — 홀드 중이 아니면 없다. */
    @Column(name = "hold_id", length = 36)
    private String holdId;

    /** 홀드 만료 시각. nullable — 홀드 중이 아니면 없다. */
    @Column(name = "held_until")
    private Instant heldUntil;

    /** 낙관적 락 전략의 명시적 조건부 UPDATE용 컬럼. 클래스 주석 참조. */
    @Column(name = "version", nullable = false)
    private Long version;

    protected SeatInventory() {
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getHoldId() {
        return holdId;
    }

    public void setHoldId(String holdId) {
        this.holdId = holdId;
    }

    public Instant getHeldUntil() {
        return heldUntil;
    }

    public void setHeldUntil(Instant heldUntil) {
        this.heldUntil = heldUntil;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
