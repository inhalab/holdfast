package com.inhalab.holdfast.lookup;

import java.time.Instant;

/**
 * 예약 조회 목록의 한 줄. 화면 전용 평면 투영이다.
 *
 * <p>좌석 이름을 담지 않고 <b>좌석 수만</b> 센다. 목록에서 필요한 것은 "몇 석
 * 잡았는가"이고, 어느 좌석인지는 상세 화면이 보여준다 —
 * {@code ReservationService#seatsOf}를 줄마다 부르면 N+1이 된다.
 */
public record ReservationSummary(
        Long reservationId,
        Long sessionId,
        String status,
        Long seatCount,
        Instant createdAt,
        Instant confirmedAt,
        Instant cancelledAt
) {
}
