package com.inhalab.holdfast.ticket;

/** {@code POST /api/tickets/scan} 요청 본문. QR에 담긴 값 그대로다. */
public record TicketScanRequestBody(String qrToken) {
}
