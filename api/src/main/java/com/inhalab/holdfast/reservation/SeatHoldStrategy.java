package com.inhalab.holdfast.reservation;

/**
 * 좌석 홀드 획득·확정 전략. concurrency-spec.md 4절.
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
 * <p><b>구현체가 책임지지 않는 것</b>은 CS-6 사용자 할당량 행 선행 락(1.1)과
 * 좌석 ID 오름차순 정렬(5.1)이다. 이 둘은 전략 비교 대상이 아니라 5종 전부
 * 동일해야 하므로 {@link SeatHoldService}가 전략 밖에서 처리한다.
 */
public interface SeatHoldStrategy {

    /**
     * 좌석을 홀드한다. CS-1 — 이 메서드가 락 전략 비교의 실체다.
     *
     * <p>전부 아니면 전무다(api-spec.md 4절). 요청한 좌석 중 하나라도 잡을 수
     * 없으면 {@link HoldResult#conflict}를 돌려주고, 부분 성공을 만들지 않는다.
     * 이미 쓴 행을 되돌리는 것은 호출자의 트랜잭션이 담당한다.
     *
     * @param command 오름차순 정렬된 좌석 ID를 담은 요청
     */
    HoldResult hold(HoldCommand command);

    /**
     * 좌석 하나를 {@code HELD → SOLD}로 확정한다. CS-2.
     *
     * <p><b>4개 전략은 이 메서드를 동일하게 구현한다</b> —
     * concurrency-spec.md 3절의 lazy 검증 조건부 UPDATE
     * ({@link SeatInventoryRepository#confirmIfStillHeld})를 그대로 쓴다.
     * 한 줄짜리 위임이며, 만료 판정이 확정과 같은 SQL 문 안에서 원자적으로
     * 끝난다.
     *
     * <p><b>{@code none}만 예외다.</b> 베이스라인은 "아무 동시성 제어도 없는
     * 순진한 구현"이어야 하는데, 조건부 UPDATE는 락이 아니라 <b>올바르게 쓴
     * 쿼리</b>다. 순진한 첫 구현은 그렇게 쓰지 않는다 — 자기 홀드 행을 읽어
     * 확인한 뒤 재고를 그냥 덮어쓴다. 이 예외가 없으면 확정 단계가 베이스라인
     * 에서도 원자적이라 초과 확정(V-1)이 구조적으로 0이 되고, 검수 기준인
     * "초과 승인 0건"(REQ-01)을 베이스라인이 위반할 수 없게 된다. 그러면
     * 재현한 결점(초과 홀드)과 측정 기준(초과 확정)이 서로 다른 것을 가리킨다.
     *
     * <p>확정 경로가 전략마다 갈리는 것은 {@code none}에 한정되며,
     * <b>4개 전략 사이의 비교는 오염되지 않는다</b> — {@code none}은 성능 비교
     * 대상이 아니라 실패 증거이기 때문이다(4.1).
     *
     * @return 확정에 성공했으면 {@code true}. {@code false}면 홀드가 만료됐거나
     *         남의 홀드다 — 호출자가 {@code HOLD_EXPIRED}로 거절한다.
     */
    boolean confirmSeat(long sessionId, long seatId, String holdId);
}
