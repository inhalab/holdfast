package com.inhalab.holdfast.reservation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

/**
 * {@code seat_hold} 저장소. 홀드 이력과 유일성의 정본이다
 * (state-transitions.md 0절).
 */
public interface SeatHoldRepository extends JpaRepository<SeatHold, Long> {

    /**
     * 홀드 행을 INSERT한다.
     *
     * <p><b>U-2가 걸려 있다고 전제하지 않는다.</b> {@code none} 베이스라인에서는
     * 시드 스크립트가 {@code ux_seat_hold_active}를 지우므로(erd.md 3.1),
     * 같은 좌석에 HELD 행이 여러 개 들어가도 이 INSERT는 성공한다. 그 상태가
     * 곧 초과 홀드이며 2개월차 산출물인 실패 데이터다.
     *
     * <p>제약 위반을 잡아 판정에 쓰는 것은 {@code unique} 전략의 메커니즘이지
     * (concurrency-spec.md 4.4) 이 메서드의 책임이 아니다.
     *
     * <p>{@code held_until}은 DB {@code now()} 기준으로 계산한다(3절).
     */
    @Modifying
    @Query(value = """
            INSERT INTO seat_hold (session_id, seat_id, hold_id, user_id, held_until, status)
            VALUES (:sessionId, :seatId, :holdId, :userId,
                    now() + (:ttlSeconds * interval '1 second'), 'HELD')
            """, nativeQuery = true)
    void insertHeld(@Param("sessionId") long sessionId,
                    @Param("seatId") long seatId,
                    @Param("holdId") String holdId,
                    @Param("userId") long userId,
                    @Param("ttlSeconds") int ttlSeconds);

    /**
     * 방금 만든 홀드 그룹의 만료 시각을 읽는다. 응답의 {@code heldUntil}에 쓴다.
     *
     * <p>Postgres의 {@code now()}는 트랜잭션 시작 시각이라 같은 트랜잭션에서
     * INSERT된 행들의 {@code held_until}이 모두 같다. 그래서 한 건만 읽으면 된다.
     */
    @Query("SELECT MIN(sh.heldUntil) FROM SeatHold sh WHERE sh.holdId = :holdId")
    Optional<Instant> findHeldUntilByHoldId(@Param("holdId") String holdId);
}
