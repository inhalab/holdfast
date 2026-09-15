package com.inhalab.holdfast.ticket;

import java.time.Instant;

/**
 * 검표 결과. {@code REJECTED_*}도 200으로 나간다 — 검표 거절은 오류가 아니라
 * 판정이다(concurrency-spec 7.1의 정상 거절·오류 분리 원칙과 같은 이유).
 *
 * @param seatNo   좌석번호. <b>거절일 때도 채운다</b>(#192) — "이미 사용된 티켓"만
 *                 뜨면 검표원은 줄에 선 사람 중 누구를 붙잡아야 할지 모른다.
 *                 토큰 자체가 없어 티켓을 특정하지 못한 경우에만 {@code null}이다.
 * @param zoneName 구역명. {@code seatNo}와 같은 조건으로 채운다.
 */
public record TicketScanResponse(
        ScanResult result,
        Long ticketId,
        String rejectReason,
        Instant scannedAt,
        Instant serverTime,
        String seatNo,
        String zoneName
) {
}
