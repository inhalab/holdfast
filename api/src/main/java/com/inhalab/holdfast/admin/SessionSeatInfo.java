package com.inhalab.holdfast.admin;

/**
 * 회차 목록 한 줄에 붙일 좌석 정보. 배치도 이름과 <b>그 회차의 좌석 수</b>다.
 *
 * <h2>배치도의 좌석 수가 아니라 회차의 재고 수를 센다</h2>
 *
 * <p>둘은 대개 같다. 회차를 만들 때 배치도의 모든 좌석에 대해
 * {@code seat_inventory}를 사전 생성하고, 회차가 참조하기 시작한 배치도는
 * 구조가 고정되기 때문이다({@link AdminSeatLayoutService} 삭제 정책).
 *
 * <p><b>그런데 갈릴 수 있다.</b> 시드 스크립트가 배치도로 거르지 않고
 * {@code FROM seat s}로 재고를 전부 넣으면 그렇게 된다 — 같은 사실이
 * {@code AdminSeatLayoutService} 주석에 이미 적혀 있다. 갈렸을 때 운영자가
 * 알아야 하는 것은 <b>사용자가 실제로 보는 좌석맵</b>이고 그것이 재고 쪽이다.
 *
 * <h2>이것은 집계값이 아니다</h2>
 *
 * <p>{@code COUNT}로 구하지만 <b>예약이 들어와도 바뀌지 않는다</b> — 바뀌는 것은
 * 상태 분포이지 행 수가 아니다. 잔여석·예약 건수 같은 값은 이슈 #104의 몫이며
 * 그 경계는 두 이슈 본문에 적혀 있다.
 */
public record SessionSeatInfo(Long sessionId, String layoutName, Long seatCount) {
}
