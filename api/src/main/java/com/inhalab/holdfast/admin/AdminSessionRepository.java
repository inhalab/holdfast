package com.inhalab.holdfast.admin;

import com.inhalab.holdfast.seat.EventSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 관리자 회차 목록이 쓰는 조회. 쓰기는 {@link AdminCatalogService}가 맡는다.
 *
 * <p><b>회차마다 세지 않는다.</b> 한 프로그램의 회차 전부를 한 번에 가져온다 —
 * 줄마다 쿼리를 돌리면 회차가 늘수록 목록이 느려진다. 같은 이유로
 * {@code CatalogSessionRepository#availabilityOf}도 회차 목록을 통째로 센다.
 */
public interface AdminSessionRepository extends JpaRepository<EventSession, Long> {

    /**
     * 한 프로그램의 회차별 배치도 이름과 좌석 수.
     *
     * <p>{@code LEFT JOIN}인 이유는 배치도가 사라진 회차도 목록에서 빠지면 안 되기
     * 때문이다. 지금은 참조 중인 배치도를 지울 수 없어 생기지 않지만, 목록이
     * 조용히 한 줄 비는 것보다 이름이 비는 편이 낫다.
     */
    @Query("""
            SELECT new com.inhalab.holdfast.admin.SessionSeatInfo(
                e.id, l.name,
                (SELECT COUNT(si) FROM SeatInventory si WHERE si.sessionId = e.id))
            FROM EventSession e
            LEFT JOIN SeatLayout l ON l.id = e.seatLayoutId
            WHERE e.programId = :programId
            """)
    List<SessionSeatInfo> seatInfoOf(@Param("programId") long programId);
}
