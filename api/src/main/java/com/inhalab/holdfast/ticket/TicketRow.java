package com.inhalab.holdfast.ticket;

import java.time.Instant;

/** 예약의 티켓 목록 조회용 평면 투영. openapi.yaml {@code Ticket}에 대응한다. */
public record TicketRow(
        Long ticketId,
        String qrToken,
        String status,
        Instant issuedAt,
        Instant usedAt,
        String seatNo,
        String zoneName
) {
}
