package com.inhalab.holdfast.reservation;

import com.inhalab.holdfast.seat.SeatMapRow;
import com.inhalab.holdfast.seat.SeatStatusRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * {@code SeatInventory} 저장소. 엔티티가 있는 이 패키지({@code reservation/})에
 * 함께 둔다 — Spring Data 관례이자, erd.md 4절이 정한 대로 이 테이블이 CS-1의
 * 쓰기 경로임을 리뷰 경계로 삼기 위해서다.
 *
 * <p>아래 두 메서드는 전부 읽기 전용 SELECT다. 좌석·회차 조회는 락을 잡지
 * 않는다 — {@code SELECT ... FOR UPDATE}도, {@code @Lock}도 쓰지 않는다.
 */
public interface SeatInventoryRepository extends JpaRepository<SeatInventory, Long> {

    /**
     * 좌석맵 전체 조회. {@code Seat}·{@code Zone}과 {@code @ManyToOne} 연관 없이
     * JPQL ad hoc join(ON 절)으로 직접 조인한다(erd.md 4절 — 조회에서 연관 매핑을
     * 새로 추가하지 않는다). 구역 정렬 순서 → 행 → 열 순으로 정렬해, 컨트롤러가
     * 별도 정렬 없이 순서대로 묶기만 하면 되게 한다.
     */
    @Query("""
            SELECT new com.inhalab.holdfast.seat.SeatMapRow(
                z.id, z.name, z.sortOrder,
                s.id, s.seatNo, s.rowIndex, s.colIndex,
                si.status)
            FROM SeatInventory si
            JOIN Seat s ON s.id = si.seatId
            JOIN Zone z ON z.id = s.zoneId
            WHERE si.sessionId = :sessionId
            ORDER BY z.sortOrder, s.rowIndex, s.colIndex
            """)
    List<SeatMapRow> findSeatMapRows(@Param("sessionId") Long sessionId);

    /**
     * 폴링 전용. {@code seatId}·{@code status}만 읽는다 — 정적 정보를 다시 조인하지
     * 않으므로 {@code Seat}·{@code Zone}에 닿지 않는다.
     */
    @Query("""
            SELECT new com.inhalab.holdfast.seat.SeatStatusRow(si.seatId, si.status)
            FROM SeatInventory si
            WHERE si.sessionId = :sessionId
            ORDER BY si.seatId
            """)
    List<SeatStatusRow> findStatusRows(@Param("sessionId") Long sessionId);

    /**
     * 재고 행 한 건을 <b>락 없이</b> 읽는다. {@code none} 베이스라인의 "조회 후
     * UPDATE"에서 조회에 해당한다(concurrency-spec.md 4.1).
     *
     * <p>{@code @Lock}을 붙이지 않는다 — 붙이면 그 순간 비관적 락 전략이 된다.
     */
    Optional<SeatInventory> findBySessionIdAndSeatId(Long sessionId, Long seatId);

    /**
     * 재고 행을 <b>조건 없이</b> HELD로 바꾼다. {@code none} 베이스라인 전용이다.
     *
     * <p><b>WHERE 절에 {@code id}만 있다.</b> {@code status = 'AVAILABLE'}이나
     * {@code version = ?} 같은 조건을 넣으면 그 순간 낙관적 락(4.3)이 되어
     * 베이스라인이 아니게 된다. 판정은 이 UPDATE가 아니라 앞선 조회 결과에 대한
     * 자바 쪽 비교가 하며, 그 둘 사이의 틈이 정확히 초과 예약이 발생하는
     * 지점이다 — 이 이슈가 만들려는 실패 증거다.
     *
     * <p><b>반환 타입이 {@code void}인 것도 의도다.</b> {@code int}로 두면
     * {@code rowsAffected}로 분기하고 싶어지는데, 그 판정은 조건부 UPDATE
     * 전략의 것이지 베이스라인의 것이 아니다. 타입 자체로 막아 둔다.
     *
     * <p>{@code held_until}은 DB {@code now()}로 계산한다 — 앱 서버 2대의 시계가
     * 어긋나면 만료 판정이 인스턴스마다 달라진다(concurrency-spec.md 3절).
     */
    /**
     * <b>lazy 검증 확정 쿼리.</b> concurrency-spec.md 3절이 정한 형태 그대로다.
     *
     * <p>만료 판정을 확정과 <b>같은 SQL 문 안에</b> 넣는 것이 핵심이다. 별도로
     * 조회해 만료를 확인한 뒤 확정하면 그 사이에 만료가 일어날 수 있는데, 그것이
     * CS-2(예약 확정)와 CS-3(홀드 TTL 만료)의 경합이며 이 프로젝트에서 가장 자주
     * 터지는 지점이다. 조건을 문장 안으로 넣으면 그 틈이 존재하지 않는다.
     *
     * <p>{@code rowsAffected = 0}이면 홀드가 만료됐거나 남의 홀드다.
     *
     * <p><b>이 메서드는 {@code none}을 제외한 4개 전략이 쓴다.</b> 조건부
     * UPDATE는 락이 아니라 올바르게 쓴 쿼리이고, 순진한 첫 구현은 이렇게 쓰지
     * 않는다 — 베이스라인은
     * {@link NoneSeatHoldStrategy#confirmSeat}의 SELECT-후-UPDATE를 쓴다.
     * 근거는 {@link SeatHoldStrategy#confirmSeat}에 있다.
     */
    @Modifying
    @Query(value = """
            UPDATE seat_inventory
               SET status = 'SOLD', hold_id = NULL, held_until = NULL, version = version + 1
             WHERE session_id = :sessionId
               AND seat_id = :seatId
               AND status = 'HELD'
               AND hold_id = :holdId
               AND held_until > now()
            """, nativeQuery = true)
    int confirmIfStillHeld(@Param("sessionId") long sessionId,
                           @Param("seatId") long seatId,
                           @Param("holdId") String holdId);

    /**
     * 재고 행을 <b>조건 없이</b> SOLD로 바꾼다. {@code none} 베이스라인 전용이다.
     *
     * <p>WHERE 절에 회차·좌석만 있다. {@code status = 'HELD'}도,
     * {@code hold_id = ?}도, {@code held_until > now()}도 없다 — 그 조건들을
     * 넣는 순간 {@link #confirmIfStillHeld}와 같아져 베이스라인이 아니게 된다.
     * 판정은 이 UPDATE가 아니라 앞선 홀드 행 조회에 대한 자바 쪽 비교가 하며,
     * 그 둘 사이의 틈이 초과 확정(V-1)이 발생하는 지점이다.
     *
     * <p>반환 타입이 {@code void}인 것도 의도다 — {@code rowsAffected}로
     * 분기하고 싶어지는 것을 타입으로 막는다.
     */
    @Modifying
    @Query(value = """
            UPDATE seat_inventory
               SET status = 'SOLD', hold_id = NULL, held_until = NULL, version = version + 1
             WHERE session_id = :sessionId AND seat_id = :seatId
            """, nativeQuery = true)
    void markSoldUnconditionally(@Param("sessionId") long sessionId, @Param("seatId") long seatId);

    /**
     * 홀드를 풀어 좌석을 되돌린다. 자진 해제(DELETE /api/holds/{holdId})와
     * 만료 정리가 함께 쓴다. {@code hold_id}를 조건에 넣어 남의 홀드를 풀지
     * 않는다.
     */
    @Modifying
    @Query(value = """
            UPDATE seat_inventory
               SET status = 'AVAILABLE', hold_id = NULL, held_until = NULL, version = version + 1
             WHERE session_id = :sessionId
               AND seat_id = :seatId
               AND hold_id = :holdId
            """, nativeQuery = true)
    int releaseHeld(@Param("sessionId") long sessionId,
                    @Param("seatId") long seatId,
                    @Param("holdId") String holdId);

    /**
     * 판매된 좌석을 되돌린다(취소). {@code SOLD}인 행만 바꾼다 — 확정 시
     * {@code hold_id}가 {@code NULL}이 되므로 홀드로는 식별할 수 없고,
     * 어떤 좌석이 이 예약의 것인지는 {@code reservation_seat}가 안다.
     */
    @Modifying
    @Query(value = """
            UPDATE seat_inventory
               SET status = 'AVAILABLE', hold_id = NULL, held_until = NULL, version = version + 1
             WHERE id = :seatInventoryId
               AND status = 'SOLD'
            """, nativeQuery = true)
    int releaseSold(@Param("seatInventoryId") long seatInventoryId);

    @Modifying
    @Query(value = """
            UPDATE seat_inventory
               SET status = 'HELD',
                   hold_id = :holdId,
                   held_until = now() + (:ttlSeconds * interval '1 second'),
                   version = version + 1
             WHERE id = :id
            """, nativeQuery = true)
    void markHeldUnconditionally(@Param("id") Long id,
                                 @Param("holdId") String holdId,
                                 @Param("ttlSeconds") int ttlSeconds);
}
