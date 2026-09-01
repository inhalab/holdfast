package com.inhalab.holdfast.reservation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
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

    /**
     * 홀드 그룹 안의 좌석 한 건. {@code none}의 순진한 확정이 자기 홀드 행을
     * 확인하는 데 쓴다.
     */
    Optional<SeatHold> findByHoldIdAndSeatId(String holdId, long seatId);

    /** 홀드 그룹의 행 전체. 확정·해제가 대상 좌석을 찾는 데 쓴다. */
    List<SeatHold> findByHoldIdOrderBySeatIdAsc(String holdId);

    /**
     * 홀드 그룹을 확정으로 전이시킨다. {@code HELD}인 행만 바꾼다.
     */
    @Modifying
    @Query(value = """
            UPDATE seat_hold SET status = 'CONFIRMED'
             WHERE hold_id = :holdId AND status = 'HELD'
            """, nativeQuery = true)
    int confirmHeld(@Param("holdId") String holdId);

    /**
     * 홀드 그룹을 해제로 전이시킨다.
     *
     * <p>{@code RELEASED}는 "활성 홀드가 아니게 된 모든 경우"를 가리키는 값
     * 하나이며, 만료였는지 자진 해제였는지 취소였는지는
     * {@code reservation.status}가 구분한다(state-transitions.md 3절).
     * 그래서 확정된 홀드의 취소도 같은 전이를 쓴다.
     */
    @Modifying
    @Query(value = """
            UPDATE seat_hold SET status = 'RELEASED'
             WHERE hold_id = :holdId AND status IN ('HELD', 'CONFIRMED')
            """, nativeQuery = true)
    int releaseByHoldId(@Param("holdId") String holdId);

    /**
     * <b>만료 홀드 정리.</b> erd.md 4.1절이 정한 조건부 UPDATE 그대로이며,
     * {@code rowsAffected}가 판정의 근거다.
     *
     * <pre>
     * 1 → 내가 만료 행을 정리했다      → INSERT 진행
     * 0 → 다른 요청이 이미 정리했다    → 재조회하거나 409로 거절
     * </pre>
     *
     * <p>정리와 INSERT를 두 단계로 나누면 두 요청이 같은 만료 행을 동시에
     * 발견했을 때 둘 다 INSERT를 시도하고, U-2가 막은 쪽이 제약 위반 카운터를
     * 올려 "앱 락이 샜다"는 신호와 섞인다. 판정을 단일 SQL 문 안에서 끝내
     * 그 틈을 없앤다.
     *
     * <p><b>{@code none} 전략은 이 메서드를 호출하지 않는다.</b> erd.md 4.1의
     * 전략별 표가 명시한 대로다 — {@code rowsAffected} 게이트는 앱 레벨 방어라
     * 베이스라인에 넣으면 실패 증거가 흐려지고, U-2가 없어 만료 행이 INSERT를
     * 막지도 않는다. 나머지 4개 전략이 홀드 획득 경로에서 쓴다.
     */
    @Modifying
    @Query(value = """
            UPDATE seat_hold SET status = 'RELEASED'
             WHERE session_id = :sessionId
               AND seat_id = :seatId
               AND status = 'HELD'
               AND held_until <= now()
            """, nativeQuery = true)
    int releaseExpired(@Param("sessionId") long sessionId, @Param("seatId") long seatId);
}
