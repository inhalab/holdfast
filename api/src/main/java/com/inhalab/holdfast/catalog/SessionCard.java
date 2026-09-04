package com.inhalab.holdfast.catalog;

import java.time.Instant;

/**
 * 회차 목록의 카드 한 장.
 *
 * @param reservable 지금 좌석 선택으로 넘어갈 수 있는가. 예약 오픈 전이거나
 *                   회차가 {@code OPEN}이 아니면 거짓이다 — 실제 예약 사이트가
 *                   "오픈 예정"을 눌리지 않게 두는 것과 같다. 서버는 이와 별개로
 *                   홀드 요청을 {@code RESERVATION_NOT_OPEN}으로 거절한다(REQ-08).
 *                   화면은 그 거절을 미리 보여줄 뿐 대신하지 않는다.
 */
public record SessionCard(
        Long sessionId,
        Instant startsAt,
        Instant endsAt,
        Instant reserveOpensAt,
        String status,
        long available,
        long total,
        boolean reservable
) {
}
