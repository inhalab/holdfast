package com.inhalab.holdfast.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 예약 요청 멱등키와 응답 재생. docs/erd.md 2절 REQ-03, REQ-04.
 * concurrency-spec.md 6절.
 *
 * <p>{@code responseStatus}·{@code responseBody}는 nullable이다. 같은 키로
 * 요청이 아직 처리 중이면(api-spec.md {@code IDEMPOTENCY_KEY_IN_PROGRESS}) 응답이
 * 아직 없다.
 */
@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 클라이언트가 생성한 UUID(api-spec.md Idempotency-Key). */
    @Column(name = "idempotency_key", nullable = false, length = 36)
    private String idempotencyKey;

    /** 생성된 예약. nullable — erd.md. */
    @Column(name = "reservation_id")
    private Long reservationId;

    /** 요청 해시. 같은 키로 다른 본문이 오면 이 값으로 걸러 409를 반환한다. */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdempotencyRecord() {
    }

    public Long getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public Long getReservationId() {
        return reservationId;
    }

    public void setReservationId(Long reservationId) {
        this.reservationId = reservationId;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public void setRequestHash(String requestHash) {
        this.requestHash = requestHash;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public void setResponseStatus(Integer responseStatus) {
        this.responseStatus = responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
