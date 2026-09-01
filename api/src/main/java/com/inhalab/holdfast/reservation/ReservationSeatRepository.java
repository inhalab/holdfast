package com.inhalab.holdfast.reservation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 예약 좌석 저장소.
 *
 * <p><b>이 테이블이 초과 예약 검증(V-1)의 근거다.</b>
 * {@code load-test/sql/verify.sql}이 {@code reservation_seat}를
 * {@code status = 'CONFIRMED'}인 예약과 조인해 같은 재고 행이 두 건 이상
 * 팔렸는지 센다. 그래서 확정된 예약에는 반드시 이 행이 있어야 하며, 없으면
 * 베이스라인 측정의 핵심 지표가 0으로 나온다.
 */
public interface ReservationSeatRepository extends JpaRepository<ReservationSeat, Long> {

    List<ReservationSeat> findByReservationId(Long reservationId);

    /** 예약 응답의 {@code seats} 배열. 좌석 번호·구역명까지 함께 읽는다. */
    @Query("""
            SELECT new com.inhalab.holdfast.reservation.ReservationSeatRow(
                s.id, s.seatNo, z.name)
            FROM ReservationSeat rs
            JOIN SeatInventory si ON si.id = rs.seatInventoryId
            JOIN Seat s ON s.id = si.seatId
            JOIN Zone z ON z.id = s.zoneId
            WHERE rs.reservationId = :reservationId
            ORDER BY s.id
            """)
    List<ReservationSeatRow> findSeatRows(@Param("reservationId") Long reservationId);
}
