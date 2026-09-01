package com.inhalab.holdfast.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code (회차, 사용자)} 단위 보유 매수 집계. docs/erd.md 2절 REQ-03.
 *
 * <p>1인 최대 매수 검사(CS-6)는 좌석 단위 락으로는 막히지 않는 별도 축이다
 * (concurrency-spec.md 1.1절, docs/requirements.md REQ-11). 같은 사용자의
 * 요청 두 개가 서로 다른 좌석을 대상으로 동시에 들어오면 좌석 락은 이 경로를
 * 막지 못하므로, 이 행을 좌석 행보다 먼저 잠근다(전역 락 순서,
 * concurrency-spec.md 5.1).
 */
@Entity
@Table(name = "user_session_quota")
public class UserSessionQuota {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "held_count", nullable = false)
    private Integer heldCount;

    protected UserSessionQuota() {
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

    public Integer getHeldCount() {
        return heldCount;
    }

    public void setHeldCount(Integer heldCount) {
        this.heldCount = heldCount;
    }
}
