package com.inhalab.holdfast.catalog;

import com.inhalab.holdfast.seat.EventSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * 프로그램 하나의 회차 목록과 그 회차들의 잔여 좌석.
 *
 * <p>{@code seat/}·{@code reservation/}의 엔티티를 읽기만 한다 — 그 패키지의
 * 파일은 고치지 않는다.
 */
public interface CatalogSessionRepository extends JpaRepository<EventSession, Long> {

    List<EventSession> findByProgramIdOrderByStartsAtAsc(Long programId);

    /**
     * 화면에 뿌릴 회차들의 좌석 상태 집계.
     *
     * <p>{@code admin/}의 전체 집계와 쿼리 모양이 닮았지만 <b>범위가 다르다</b> —
     * 이쪽은 지금 보여줄 회차만 센다. 관리자 화면은 회차 수가 적다는 전제로 전부
     * 세지만, 사용자 화면은 프로그램 하나만 열므로 그 범위로 좁히는 편이 맞다.
     */
    @Query("""
            SELECT new com.inhalab.holdfast.catalog.SessionAvailabilityRow(
                si.sessionId, si.status, COUNT(si))
            FROM SeatInventory si
            WHERE si.sessionId IN :sessionIds
            GROUP BY si.sessionId, si.status
            """)
    List<SessionAvailabilityRow> availabilityOf(@Param("sessionIds") Collection<Long> sessionIds);
}
