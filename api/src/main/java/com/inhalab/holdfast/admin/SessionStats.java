package com.inhalab.holdfast.admin;

/**
 * 회차 하나의 예약 현황 통계. 이슈 #104 — SFR-002의 통계 축.
 *
 * <h2>잔여석은 {@code AVAILABLE}만 센다</h2>
 *
 * <p><b>사용자 화면과 같은 정의여야 한다.</b> 카탈로그의 회차 카드가
 * {@code seat_inventory.status = 'AVAILABLE'}만 세고({@code CatalogPageController}
 * 의 {@code countsBySession}), 그 값이 매진 판정({@code SaleState.SOLD_OUT})을
 * 가른다. <b>같은 수를 두 화면이 다르게 말하면 안 된다</b>(#127이 #123과의 경계를
 * 그으며 적은 것과 같은 기준).
 *
 * <h2>그래서 {@code expiredHolds}를 따로 센다</h2>
 *
 * <p>만료된 홀드는 {@code HELD}로 남아 있어 <b>잔여석에서 빠지지만 실제로는 다음
 * 홀드 요청이 회수해 잡힌다</b>(erd.md 4.1의 전략별 정리 절차). 게다가
 * <b>{@code none}은 아예 회수하지 않고 청소 스케줄러도 없다</b>
 * ({@code concurrency-spec.md} 3절) — 그 상태로 쌓인다.
 *
 * <p><b>그 차이를 감추지 않고 옆에 적는다.</b> {@code design-spec.md} 5.6이
 * 접수종료를 감추지 않기로 한 것과 같은 자세다. 잔여석 하나만 보이면 운영자는
 * "0석인데 왜 예약이 되지"를 만나고 그 이유를 화면에서 찾을 수 없다.
 *
 * @param available   지금 이 순간 DB가 "잡을 수 있다"고 말하는 좌석
 * @param expiredHolds {@code HELD}이지만 {@code held_until}이 지난 좌석. 잔여석에
 *                     포함되지 않지만 다음 요청이 회수한다
 * @param ticketsIssued 발권된 티켓 전부. {@code ISSUED} + {@code USED}
 * @param ticketsUsed   검표를 통과한 티켓. {@code ticket.status = 'USED'}가
 *                      곧 검표 통과다({@code state-transitions.md} 4절)
 */
public record SessionStats(
        long totalSeats,
        long available,
        long held,
        long sold,
        long expiredHolds,
        long reservationsHeld,
        long reservationsConfirmed,
        long reservationsCancelled,
        long reservationsExpired,
        long ticketsIssued,
        long ticketsUsed
) {
}
