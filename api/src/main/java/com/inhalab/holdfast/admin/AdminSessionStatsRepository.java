package com.inhalab.holdfast.admin;

import com.inhalab.holdfast.reservation.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * 회차별 예약 현황 통계. 이슈 #104 — SFR-002의 통계 축.
 *
 * <p>{@code reservation/}·{@code ticket/}의 엔티티를 <b>읽기만</b> 한다. 그
 * 패키지의 파일은 하나도 고치지 않는다 — {@code AdminSeatSummaryRepository}·
 * {@code lookup/}과 같은 경계다(#79·#80 이후 지켜 온 것).
 *
 * <h2>회차마다 세지 않는다</h2>
 *
 * <p>세 쿼리가 <b>회차 전부를 한 번에</b> 집계하고, 호출자가 회차 id로 맞춘다.
 * 줄마다 세면 회차가 늘수록 화면이 느려진다 — #127이 총 좌석 수를 한 쿼리로
 * 가져와 id 매칭으로 푼 것과 같은 형태다.
 *
 * <p><b>#105가 곧 이 화면의 응답시간을 잰다</b>(PER-002). N+1이 있으면 그
 * 측정이 화면이 아니라 회차 수를 재게 된다.
 */
public interface AdminSessionStatsRepository extends JpaRepository<Reservation, Long> {

    /**
     * 회차별 예약 상태 개수. {@code key}는 {@code reservation.status}다.
     *
     * <p><b>상태를 가리지 않고 전부 센다.</b> 화면이 상태별 열을 따로 두므로
     * 여기서 걸러 낼 이유가 없고, 무엇을 세는지가 라벨과 하나씩 대응한다.
     *
     * <p>{@code PENDING_PAYMENT}는 나오지 않는다 — 확정이 결제 안에서 한
     * 트랜잭션으로 끝나 그 값이 외부에서 관측되지 않는다
     * ({@code state-transitions.md} 1절). 그래서 화면에도 열을 두지 않는다.
     */
    @Query("""
            SELECT new com.inhalab.holdfast.admin.SessionCountRow(r.sessionId, r.status, COUNT(r))
            FROM Reservation r
            GROUP BY r.sessionId, r.status
            """)
    List<SessionCountRow> reservationCounts();

    /**
     * 회차별 티켓 상태 개수. {@code key}는 {@code ticket.status}({@code ISSUED} /
     * {@code USED})다.
     *
     * <p><b>{@code ticket_scan}을 조인하지 않는다.</b> {@code USED}가 곧 검표
     * 통과이고({@code state-transitions.md} 4절) 거절 스캔은 이력이라 세는 대상이
     * 아니다. 조인이 한 단계 얕아진다.
     */
    @Query("""
            SELECT new com.inhalab.holdfast.admin.SessionCountRow(r.sessionId, t.status, COUNT(t))
            FROM Ticket t
            JOIN ReservationSeat rs ON rs.id = t.reservationSeatId
            JOIN Reservation r ON r.id = rs.reservationId
            GROUP BY r.sessionId, t.status
            """)
    List<SessionCountRow> ticketCounts();

    /**
     * 회차별 <b>만료된</b> 홀드 좌석 수. {@code key}는 쓰지 않는다.
     *
     * <p>{@code HELD}인데 {@code held_until}이 지난 행이다. <b>잔여석에서 빠지지만
     * 실제로는 다음 홀드 요청이 회수한다</b>({@link SessionStats} 참조).
     *
     * <p>기준 시각을 <b>호출자가 넘긴다.</b> 쿼리 안에서 {@code CURRENT_TIMESTAMP}를
     * 쓰면 테스트가 그 값을 고정할 수 없고, 만료 판정을 시험하려면 그 고정이
     * 필요하다.
     */
    @Query("""
            SELECT new com.inhalab.holdfast.admin.SessionCountRow(si.sessionId, 'EXPIRED', COUNT(si))
            FROM SeatInventory si
            WHERE si.status = 'HELD' AND si.heldUntil IS NOT NULL AND si.heldUntil <= :now
            GROUP BY si.sessionId
            """)
    List<SessionCountRow> expiredHoldCounts(@Param("now") Instant now);
}
