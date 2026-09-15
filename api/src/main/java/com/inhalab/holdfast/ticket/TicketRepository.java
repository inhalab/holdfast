package com.inhalab.holdfast.ticket;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 티켓 저장소.
 *
 * <p>조회 쿼리가 {@code reservation/}·{@code seat/} 패키지의 엔티티까지 조인한다.
 * 그 패키지의 파일은 하나도 고치지 않는다 — 이미 공개된 저장소·엔티티를 읽기만
 * 하는 조인이며, Mock PG(#79)에서 확립한 것과 같은 경계다. 락을 쥔 상태에서
 * 지연 로딩이 끼어드는 것을 막기 위해 {@code @ManyToOne} 연관 없이 JPQL ad hoc
 * join으로 평면 투영한다(erd.md 4절).
 */
public interface TicketRepository extends JpaRepository<Ticket, Long> {

    /**
     * 검표 판정에 필요한 값을 한 번에 읽는다 — 티켓 상태·예약 상태·회차 입장
     * 가능 시간, 그리고 <b>어느 좌석인가</b>.
     *
     * <p><b>좌석은 판정에 쓰지 않는다. 사람에게 보여주려고 읽는다</b>(#192).
     * 검표원이 티켓 ID 1042를 받아 할 수 있는 일이 없다 — 손에 든 표와 맞춰 볼
     * 수 있는 값은 구역과 좌석번호다.
     *
     * <p><b>조인이 늘지만 왕복은 늘지 않는다.</b> 아래
     * {@link #findRowsByReservationId}가 이미 같은 경로로 {@code s.seatNo}·
     * {@code z.name}을 읽고 있다 — 같은 조인을 여기에도 붙여 한 번에 가져온다.
     * 두 번째 쿼리를 더하면 판정마다 왕복이 둘이 된다.
     *
     * <p><b>내부 조인이어도 안전하다.</b> 네 단계 모두 FK로 묶여 있어 티켓이
     * 있으면 좌석도 있다 — 위 메서드가 같은 전제로 이미 돌고 있다.
     *
     * <p>이 경로는 부하 측정에 들어가지 않는다. k6 시나리오
     * ({@code load-test/scenarios/reservation.js})는 {@code /api/holds}와
     * {@code /api/reservations}만 부르고 검표는 지나지 않는다.
     */
    @Query("""
            SELECT new com.inhalab.holdfast.ticket.TicketScanContext(
                t.id, t.status, r.id, r.status, es.entryOpensAt, es.entryClosesAt,
                s.seatNo, z.name)
            FROM Ticket t
            JOIN ReservationSeat rs ON rs.id = t.reservationSeatId
            JOIN Reservation r ON r.id = rs.reservationId
            JOIN EventSession es ON es.id = r.sessionId
            JOIN SeatInventory si ON si.id = rs.seatInventoryId
            JOIN Seat s ON s.id = si.seatId
            JOIN Zone z ON z.id = s.zoneId
            WHERE t.qrToken = :qrToken
            """)
    Optional<TicketScanContext> findScanContextByQrToken(@Param("qrToken") String qrToken);

    /** 예약 확인·티켓 화면(#81)이 쓸 목록. 좌석 번호·구역명까지 함께 읽는다. */
    @Query("""
            SELECT new com.inhalab.holdfast.ticket.TicketRow(
                t.id, t.qrToken, t.status, t.issuedAt, t.usedAt, s.seatNo, z.name)
            FROM Ticket t
            JOIN ReservationSeat rs ON rs.id = t.reservationSeatId
            JOIN SeatInventory si ON si.id = rs.seatInventoryId
            JOIN Seat s ON s.id = si.seatId
            JOIN Zone z ON z.id = s.zoneId
            WHERE rs.reservationId = :reservationId
            ORDER BY t.id
            """)
    List<TicketRow> findRowsByReservationId(@Param("reservationId") Long reservationId);

    /**
     * 검표 성공(ADMITTED) 시 티켓을 사용 처리한다. 조건 없는 UPDATE이지만
     * 안전하다 — 호출자가 이미 {@code TicketScanRecorder#admit}에서 U-11
     * INSERT를 통과시킨 뒤에만 부른다({@code ticket_scan}이 승자를 이미 결정했다).
     */
    @Modifying
    @Query("UPDATE Ticket t SET t.status = 'USED', t.usedAt = :usedAt WHERE t.id = :ticketId")
    int markUsed(@Param("ticketId") Long ticketId, @Param("usedAt") Instant usedAt);
}
