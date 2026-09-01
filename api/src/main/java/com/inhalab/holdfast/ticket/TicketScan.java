package com.inhalab.holdfast.ticket;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 검표 이력. docs/erd.md 2절 REQ-06.
 *
 * <p>실패 스캔도 행으로 남긴다 — U-11
 * {@code ux_ticket_scan_admitted}(ticket_id, {@code WHERE result = 'ADMITTED'})가
 * 성공 입장만 티켓당 1건으로 제한하는 부분 인덱스이기 때문이다(erd.md 4절).
 * 실패 이력까지 막으면 왜 거절됐는지 남길 수 없다.
 */
@Entity
@Table(name = "ticket_scan")
public class TicketScan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_id", nullable = false)
    private Long ticketId;

    /** ADMITTED / REJECTED_DUPLICATE / REJECTED_TIME / REJECTED_INVALID. */
    @Column(name = "result", nullable = false, length = 30)
    private String result;

    /** 거절 사유. nullable — ADMITTED면 없다. */
    @Column(name = "reject_reason", length = 200)
    private String rejectReason;

    @Column(name = "scanned_at", nullable = false)
    private Instant scannedAt;

    protected TicketScan() {
    }

    public Long getId() {
        return id;
    }

    public Long getTicketId() {
        return ticketId;
    }

    public void setTicketId(Long ticketId) {
        this.ticketId = ticketId;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    public Instant getScannedAt() {
        return scannedAt;
    }

    public void setScannedAt(Instant scannedAt) {
        this.scannedAt = scannedAt;
    }
}
