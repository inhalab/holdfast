package com.inhalab.holdfast.admin;

import com.inhalab.holdfast.seat.SeatLayout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * 좌석배치도 목록. 회차 등록 화면의 드롭다운을 채운다.
 *
 * <p>배치도 자체를 만드는 화면은 별도 이슈(#102)다. 여기서는 이미 있는 것을
 * 고르기만 한다.
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
}
