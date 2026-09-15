package com.inhalab.holdfast.admin;

import com.inhalab.holdfast.seat.SeatLayout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * 좌석배치도 조회. 회차 등록 화면(#101)의 드롭다운과 배치도 관리 화면(#102)이 쓴다.
 *
 * <p><b>쓰기는 여기 없다.</b> {@code SeatLayout}·{@code Zone}·{@code Seat}의 기본
 * 생성자가 {@code protected}라 다른 패키지에서 만들 수 없고, 격자 생성은 어차피
 * {@code INSERT ... SELECT generate_series}가 자연스러운 벌크 연산이다 —
 * {@link AdminSeatLayoutService}가 맡는다. {@link AdminCatalogService}와 같은 이유다.
 */
public interface AdminSeatLayoutRepository extends JpaRepository<SeatLayout, Long> {

    /**
     * 배치도와 그 배치도에 속한 좌석 수. 좌석이 0인 배치도를 고르면 회차를
     * 만들 수 없으므로(재고가 비어 예약 자체가 불가능하다) 화면에서 미리 보여준다.
     */
    @Query("""
            SELECT new com.inhalab.holdfast.admin.SeatLayoutOption(
                l.id, l.name, (SELECT COUNT(s) FROM Seat s JOIN Zone z ON z.id = s.zoneId
                               WHERE z.seatLayoutId = l.id))
            FROM SeatLayout l
            ORDER BY l.id
            """)
    List<SeatLayoutOption> options();

    /**
     * 배치도 목록. 구역·좌석 수와 함께 <b>이 배치도를 쓰는 회차 수</b>를 센다 —
     * 그 값이 0인지가 삭제 가능 여부다({@link AdminSeatLayoutService} 삭제 정책).
     */
    @Query("""
            SELECT new com.inhalab.holdfast.admin.LayoutRow(
                l.id, l.name,
                (SELECT COUNT(z) FROM Zone z WHERE z.seatLayoutId = l.id),
                (SELECT COUNT(s) FROM Seat s JOIN Zone z2 ON z2.id = s.zoneId
                  WHERE z2.seatLayoutId = l.id),
                (SELECT COUNT(e) FROM EventSession e WHERE e.seatLayoutId = l.id))
            FROM SeatLayout l
            ORDER BY l.id
            """)
    List<LayoutRow> rows();

    /**
     * 목록에 뿌릴 배치도들의 <b>구역 구성</b>(#192). 배치도를 고르는 판단에 필요한
     * 것은 "24석"이라는 총합이 아니라 "A구역 12 · B구역 12"라는 구성이다.
     *
     * <p><b>배치도마다 묻지 않는다.</b> {@code IN}으로 한 번에 받아 호출자가
     * 배치도별로 가른다 — #140이 관리자 예약 현황에서 지적한 N+1을 옆 화면에
     * 새로 만들 이유가 없다. 목록 쿼리가 하나 늘 뿐이고 배치도 수에 비례하지 않는다.
     *
     * <p>좌석이 0인 구역도 나와야 한다. 구역만 만들고 좌석을 안 넣은 상태가
     * 실제로 있고, 그것이 보이지 않으면 관리자는 구역을 왜 못 쓰는지 모른다 —
     * 그래서 {@code LEFT JOIN}이다.
     */
    @Query("""
            SELECT new com.inhalab.holdfast.admin.ZoneSummary(
                z.seatLayoutId, z.name, COUNT(s.id))
            FROM Zone z
            LEFT JOIN Seat s ON s.zoneId = z.id
            WHERE z.seatLayoutId IN :layoutIds
            GROUP BY z.seatLayoutId, z.id, z.name, z.sortOrder
            ORDER BY z.seatLayoutId, z.sortOrder, z.id
            """)
    List<ZoneSummary> zoneSummariesOf(@Param("layoutIds") Collection<Long> layoutIds);

    /** 한 배치도의 구역과 각 구역의 격자 크기. 정렬 순서는 좌석맵이 그리는 순서다. */
    @Query("""
            SELECT new com.inhalab.holdfast.admin.ZoneRow(
                z.id, z.name, z.sortOrder,
                (SELECT COUNT(s) FROM Seat s WHERE s.zoneId = z.id),
                (SELECT COALESCE(MAX(s.rowIndex), 0) FROM Seat s WHERE s.zoneId = z.id),
                (SELECT COALESCE(MAX(s.colIndex), 0) FROM Seat s WHERE s.zoneId = z.id))
            FROM Zone z
            WHERE z.seatLayoutId = :layoutId
            ORDER BY z.sortOrder, z.id
            """)
    List<ZoneRow> zonesOf(@Param("layoutId") long layoutId);

    /**
     * 한 배치도의 모든 좌석. 화면이 구역별로 갈라 격자로 그린다.
     *
     * <p>구역마다 따로 묻지 않는다 — 구역 수만큼 쿼리가 늘 이유가 없고,
     * 배치도 하나의 좌석 수는 화면에 그릴 수 있는 만큼이다.
     */
    @Query("""
            SELECT new com.inhalab.holdfast.admin.SeatRow(
                s.id, s.zoneId, s.seatNo, s.rowIndex, s.colIndex)
            FROM Seat s JOIN Zone z ON z.id = s.zoneId
            WHERE z.seatLayoutId = :layoutId
            ORDER BY z.sortOrder, z.id, s.rowIndex, s.colIndex
            """)
    List<SeatRow> seatsOf(@Param("layoutId") long layoutId);
}
