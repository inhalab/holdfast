package com.inhalab.holdfast.ticket;

/**
 * 티켓 상태. design-spec.md 3.3절 · erd.md {@code ticket.status}.
 *
 * <p>{@code VOID}는 예약 취소로 무효가 된 티켓이다. 다만 이 구현은
 * <b>취소 시점에 티켓을 미리 무효화하지 않는다</b> — 검표 시점에 예약 상태를
 * 확인하는 lazy 검증을 쓴다({@link TicketService#scan}). 근거는 그쪽 주석에 있다.
 */
public enum TicketStatus {

    /** 발급됨. 검표를 통과할 수 있는 유일한 상태다. */
    ISSUED,

    /** 검표 통과. 재사용은 U-11이 막는다. */
    USED,

    /** 예약 취소로 무효. 현재는 도달하지 않는다 — lazy 검증이 대신한다. */
    VOID
}
