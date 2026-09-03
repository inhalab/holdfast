package com.inhalab.holdfast.ticket;

import java.time.Instant;

/**
 * 검표 판정에 필요한 값을 한 번에 읽어온 평면 투영.
 *
 * <p>티켓 → 예약좌석 → 예약 → 회차까지 네 테이블을 타야 입장 가능 시간과 예약
 * 상태를 알 수 있다. {@code @ManyToOne} 연관을 만들지 않고 JPQL ad hoc join으로
 * 투영하는 것은 이 프로젝트의 규칙이다(erd.md 4절 — 락을 쥔 상태의 지연 로딩이
 * 락 보유 시간에 더해지는 것을 막기 위해 연관 매핑을 두지 않는다).
 */
public record TicketScanContext(
        Long ticketId,
        String ticketStatus,
        Long reservationId,
        String reservationStatus,
        Instant entryOpensAt,
        Instant entryClosesAt
) {
}
