package com.inhalab.holdfast.ticket;

import java.time.Instant;

/**
 * 검표 결과. {@code REJECTED_*}도 200으로 나간다 — 검표 거절은 오류가 아니라
 * 판정이다(concurrency-spec 7.1의 정상 거절·오류 분리 원칙과 같은 이유).
 */
public record TicketScanResponse(
        ScanResult result,
        Long ticketId,
        String rejectReason,
        Instant scannedAt,
        Instant serverTime
) {
}
