package com.inhalab.holdfast.admin;

import com.inhalab.holdfast.reservation.SeatInventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * 회차별 좌석 상태 집계. 이슈 #82 — 관리자 예약 현황 조회.
 *
 * <p>{@code reservation/}의 {@link SeatInventory} 엔티티를 읽기만 하는 별도
 * 저장소다. 그 패키지의 파일은 하나도 고치지 않는다 — Mock PG(#79)·티켓(#80)에서
 * 확립한 것과 같은 경계다. 같은 엔티티에 저장소 인터페이스를 여러 개 두는 것은
 * Spring Data JPA에서 충돌 없이 동작한다.
 */
public interface AdminSeatSummaryRepository extends JpaRepository<SeatInventory, Long> {

    @Query("""
            SELECT new com.inhalab.holdfast.admin.SeatSummaryRow(si.sessionId, si.status, COUNT(si))
            FROM SeatInventory si
            GROUP BY si.sessionId, si.status
            ORDER BY si.sessionId, si.status
            """)
    List<SeatSummaryRow> summarize();
}
