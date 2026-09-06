package com.inhalab.holdfast.lookup;

import com.inhalab.holdfast.reservation.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 예약 조회 화면의 목록 쿼리. 이슈 #103.
 *
 * <p>{@link Reservation}은 {@code reservation/} 소유 엔티티이지만 이 저장소는
 * 이 패키지에 둔다 — <b>조회 전용</b>이고 그 패키지의 파일을 고치지 않는다.
 * {@code admin/AdminReservationRepository}·{@code catalog/}가 같은 형태다
 * (#79·#80 이후 지켜 온 경계).
 *
 * <p><b>측정 경로를 건드리지 않는다.</b> 이 이슈가 만드는 것은 읽기뿐이고
 * {@code seat_inventory}·{@code seat_hold}·{@code user_session_quota}의 쓰기
 * 경로에는 손대지 않는다. #105가 곧 PER-002를 재므로 그 배선이 바뀌면 안 된다.
 */
public interface ReservationLookupRepository extends JpaRepository<Reservation, Long> {

    /**
     * 한 사용자의 예약 전부. 최근 것이 위다.
     *
     * <p><b>좌석 수는 서브쿼리로 한 번에 센다.</b> 줄마다 좌석을 조회하면
     * 예약이 늘수록 목록이 느려진다({@code catalog}·{@code admin}의 집계와 같은
     * 판단).
     *
     * <p>건수를 제한하지 않는다 — 한 사용자의 예약은 회차당 최대 매수에 묶여
     * 있어(CS-6) 화면에 그릴 수 있는 만큼이다. 관리자 목록이 200건으로 자른 것과
     * 범위가 다르다.
     */
    @Query("""
            SELECT new com.inhalab.holdfast.lookup.ReservationSummary(
                r.id, r.sessionId, r.status,
                (SELECT COUNT(rs) FROM ReservationSeat rs WHERE rs.reservationId = r.id),
                r.createdAt, r.confirmedAt, r.cancelledAt)
            FROM Reservation r
            WHERE r.userId = :userId
            ORDER BY r.id DESC
            """)
    List<ReservationSummary> summariesOf(@Param("userId") long userId);
}
