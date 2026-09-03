package com.inhalab.holdfast.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 알림 발송 큐. docs/erd.md 2절 REQ-05.
 *
 * <p>워커는 {@code SELECT ... FOR UPDATE SKIP LOCKED}를 감싼 조건부 UPDATE로
 * 이 테이블을 집는다(concurrency-spec.md 6절·6.1절). 앱 2대가 같은 행을 잡지
 * 않으면서 한쪽이 죽어도 다른 쪽이 이어받는다.
 *
 * <h2>중복 발송을 막는 것은 두 층이다</h2>
 *
 * <p><b>U-12 {@code ux_outbox_reservation_notification}</b>은 같은 예약에
 * 같은 종류의 알림이 <b>두 번 큐에 들어가는 것</b>을 막는다. 확정 트랜잭션이
 * 어떤 이유로 두 번 INSERT를 시도해도 두 번째가 제약에 걸린다.
 *
 * <p><b>클레임의 조건부 UPDATE</b>는 큐에 든 한 행을 <b>두 워커가 함께 집는
 * 것</b>을 막는다. 층이 다르므로 둘 중 하나로 대신할 수 없다 — U-12는 행이
 * 하나임을 보장할 뿐 그 행이 한 번만 발송된다고 말하지 않는다.
 *
 * <h2>상태</h2>
 *
 * <pre>
 * PENDING ──클레임──> SENDING ──성공──> SENT
 *    ^                   │
 *    └──실패(재시도 남음)─┤
 *                        └──상한 소진──> FAILED
 * </pre>
 *
 * <p>{@code SENDING}은 워커가 집었다는 표시이며 V2 마이그레이션에서 추가됐다.
 * 발송이 외부 호출이라 클레임과 같은 트랜잭션에 둘 수 없기 때문이다 —
 * 근거는 {@code V2__outbox_claim.sql}에 있다.
 */
@Entity
@Table(name = "outbox")
public class Outbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Column(name = "notification_type", nullable = false, length = 50)
    private String notificationType;

    /** {@link OutboxStatus} 이름. PENDING / SENDING / SENT / FAILED. */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    @Column(name = "payload", nullable = false)
    private String payload;

    /** 다음 재시도 시각. nullable — 재시도가 예정되지 않았으면 없다. */
    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    /**
     * 워커가 이 행을 {@code SENDING}으로 바꾼 시각. nullable — 아직 집히지
     * 않았으면 없다.
     *
     * <p><b>클레임 만료 판정에 쓴다.</b> 워커가 집은 뒤 죽으면 행이 영원히
     * {@code SENDING}으로 남는데, 이 값이 충분히 오래되면 다른 워커가
     * 되찾는다. {@code seat_inventory.held_until}과 같은 구조다.
     */
    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected Outbox() {
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

    public String getNotificationType() {
        return notificationType;
    }

    public void setNotificationType(String notificationType) {
        this.notificationType = notificationType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(Instant nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public void setClaimedAt(Instant claimedAt) {
        this.claimedAt = claimedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }
}
