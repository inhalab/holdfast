package com.inhalab.holdfast.seat;

/**
 * 좌석맵 전체 조회의 평면 투영(flat projection). 구역 × 좌석 조인 결과 한 행이다.
 *
 * <p>{@link com.inhalab.holdfast.reservation.SeatInventoryRepository}가 이 타입으로
 * 직접 JPQL 생성자 표현식을 만든다. {@code SeatInventory}·{@code Seat}·{@code Zone}
 * 사이에 {@code @ManyToOne} 연관을 새로 추가하지 않는다 — erd.md 4절: FK는 스칼라
 * 필드이고, 조회에서 연관 매핑을 추가하면 그 대가(지연 로딩이 언제 추가 쿼리를
 * 발생시키는지 예측하기 어려워지는 것)가 조회 경로에도 그대로 옮겨붙는다.
 * JPQL의 ad hoc join(ON 절)으로 세 엔티티를 직접 조인해 이 문제를 피한다.
 *
 * <p>JSON으로 직접 나가지 않는 내부 전용 타입이다. 컨트롤러는 이 값을 구역별로
 * 묶어 {@link SeatMapResponse}로 조립한다.
 */
public record SeatMapRow(
        Long zoneId,
        String zoneName,
        Integer sortOrder,
        Long seatId,
        String seatNo,
        Integer rowIndex,
        Integer colIndex,
        String status
) {
}
