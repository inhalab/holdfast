package com.inhalab.holdfast.ticket;

import java.time.Instant;

/** {@code GET /api/reservations/{id}/tickets} 응답의 티켓 한 건. */
public record TicketResponse(
        Long ticketId,
        String qrToken,
        String status,
        String seatNo,
        String zoneName,
        Instant issuedAt,
        Instant usedAt,
        Instant serverTime
) {
}
