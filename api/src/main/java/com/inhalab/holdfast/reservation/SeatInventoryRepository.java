package com.inhalab.holdfast.reservation;

import com.inhalab.holdfast.seat.SeatMapRow;
import com.inhalab.holdfast.seat.SeatStatusRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * {@code SeatInventory} 저장소. 엔티티가 있는 이 패키지({@code reservation/})에
 * 함께 둔다 — Spring Data 관례이자, erd.md 4절이 정한 대로 이 테이블이 CS-1의
 * 쓰기 경로임을 리뷰 경계로 삼기 위해서다.
 *
 * <p>아래 두 메서드는 전부 읽기 전용 SELECT다. 좌석·회차 조회는 락을 잡지
 * 않는다 — {@code SELECT ... FOR UPDATE}도, {@code @Lock}도 쓰지 않는다.
 */
public interface SeatInventoryRepository extends JpaRepository<SeatInventory, Long> {

    /**
     * 좌석맵 전체 조회. {@code Seat}·{@code Zone}과 {@code @ManyToOne} 연관 없이
     * JPQL ad hoc join(ON 절)으로 직접 조인한다(erd.md 4절 — 조회에서 연관 매핑을
     * 새로 추가하지 않는다). 구역 정렬 순서 → 행 → 열 순으로 정렬해, 컨트롤러가
     * 별도 정렬 없이 순서대로 묶기만 하면 되게 한다.
     */
    @Query("""
            SELECT new com.inhalab.holdfast.seat.SeatMapRow(
                z.id, z.name, z.sortOrder,
                s.id, s.seatNo, s.rowIndex, s.colIndex,
                si.status)
            FROM SeatInventory si
            JOIN Seat s ON s.id = si.seatId
            JOIN Zone z ON z.id = s.zoneId
            WHERE si.sessionId = :sessionId
            ORDER BY z.sortOrder, s.rowIndex, s.colIndex
            """)
    List<SeatMapRow> findSeatMapRows(@Param("sessionId") Long sessionId);

    /**
     * 폴링 전용. {@code seatId}·{@code status}만 읽는다 — 정적 정보를 다시 조인하지
     * 않으므로 {@code Seat}·{@code Zone}에 닿지 않는다.
     */
    @Query("""
            SELECT new com.inhalab.holdfast.seat.SeatStatusRow(si.seatId, si.status)
            FROM SeatInventory si
            WHERE si.sessionId = :sessionId
            ORDER BY si.seatId
            """)
    List<SeatStatusRow> findStatusRows(@Param("sessionId") Long sessionId);
}
