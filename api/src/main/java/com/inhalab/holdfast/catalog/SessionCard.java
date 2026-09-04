package com.inhalab.holdfast.catalog;

import java.time.Instant;

/**
 * 회차 목록의 카드 한 장.
 *
 * @param saleState 목록에서 어떻게 보여줄지. 화면은 이 값만 보고 분기한다 —
 *                  "예약 오픈 전"과 "종료"를 한 조건으로 묶으면 닫힌 회차에도
 *                  오픈 일시가 찍힌다. 서버는 이와 별개로 홀드 요청을 다시
 *                  판정한다(REQ-08) — 화면은 그 거절을 미리 보여줄 뿐이다.
 */
public record SessionCard(
        Long sessionId,
        Instant startsAt,
        Instant endsAt,
        Instant reserveOpensAt,
        String status,
        long available,
        long total,
        SaleState saleState
) {

    /** 좌석 선택으로 넘어갈 수 있는가. 템플릿에서 쓰기 좋게 파생값으로 둔다. */
    public boolean isReservable() {
        return saleState == SaleState.ON_SALE;
    }
}
