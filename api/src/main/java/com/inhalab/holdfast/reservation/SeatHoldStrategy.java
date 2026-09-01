package com.inhalab.holdfast.reservation;

/**
 * 좌석 홀드 획득 전략. concurrency-spec.md 4절.
 *
 * <p>구현체 5개를 {@code @ConditionalOnProperty(name = "holdfast.strategy")}로
 * 스위칭한다. k6 시나리오는 고정하고 프로퍼티만 바꿔 측정하는 것이 전제이므로,
 * <b>전략 밖 코드에 전략별 분기를 넣지 않는다.</b>
 *
 * <table>
 *   <caption>프로퍼티 값과 전략</caption>
 *   <tr><td>{@code none}</td><td>베이스라인 (락 없음) — {@link NoneSeatHoldStrategy}</td></tr>
 *   <tr><td>{@code pessimistic}</td><td>비관적 락 (미구현)</td></tr>
 *   <tr><td>{@code optimistic}</td><td>낙관적 락 (미구현)</td></tr>
 *   <tr><td>{@code unique}</td><td>DB 유니크 제약 (미구현)</td></tr>
 *   <tr><td>{@code redis}</td><td>Redis 분산락 (미구현)</td></tr>
 * </table>
 *
 * <p><b>구현체가 책임지는 범위</b>는 erd.md 4.1의 파이프라인에서 좌석 루프
 * 안쪽이다 — 전략별 락 획득, {@code seat_inventory} 접근, (전략에 따라)
 * 만료 홀드 정리, {@code seat_hold} INSERT.
 *
 * <p><b>구현체가 책임지지 않는 것</b>은 CS-6 사용자 할당량 행 선행 락(1.1)과
 * 좌석 ID 오름차순 정렬(5.1)이다. 이 둘은 전략 비교 대상이 아니라 5종 전부
 * 동일해야 하므로 {@link SeatHoldService}가 전략 밖에서 처리한다 — 변수를
 * 늘리지 않는다.
 */
public interface SeatHoldStrategy {

    /**
     * 좌석을 홀드한다.
     *
     * <p>전부 아니면 전무다(api-spec.md 4절). 요청한 좌석 중 하나라도 잡을 수
     * 없으면 {@link HoldResult#conflict}를 돌려주고, 부분 성공을 만들지 않는다.
     * 이미 쓴 행을 되돌리는 것은 호출자의 트랜잭션이 담당한다.
     *
     * @param command 오름차순 정렬된 좌석 ID를 담은 요청
     */
    HoldResult hold(HoldCommand command);
}
