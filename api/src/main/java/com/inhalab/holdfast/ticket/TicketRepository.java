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
     * 가능 시간. QR 토큰 하나로 네 테이블(ticket → reservation_seat → reservation
     * → event_session)을 탄다.
     */
    @Query("""
            SELECT new com.inhalab.holdfast.ticket.TicketScanContext(
                t.id, t.status, r.id, r.status, es.entryOpensAt, es.entryClosesAt)
            FROM Ticket t
            JOIN ReservationSeat rs ON rs.id = t.reservationSeatId
            JOIN Reservation r ON r.id = rs.reservationId
            JOIN EventSession es ON es.id = r.sessionId
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
