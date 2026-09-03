package com.inhalab.holdfast.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * {@code outbox} 저장소. 워커가 행을 집고 결과를 기록하는 경로다.
 *
 * <h2>M3의 관용구를 그대로 쓴다</h2>
 *
 * <p>좌석 점유에 쓴 <b>조건부 UPDATE + {@code rowsAffected} 판정</b>이 여기에도
 * 적용된다({@code SeatHoldRepository#releaseExpired},
 * {@code SeatInventoryRepository#confirmIfStillHeld}). 판정 조건을 UPDATE의
 * WHERE에 넣고 반환값으로 이겼는지 졌는지 가른다 — 조회와 갱신 사이에 틈이
 * 없으므로 두 워커가 같은 행을 함께 처리할 수 없다.
 *
 * <pre>
 * 1 → 내가 이 행의 상태를 바꿨다     → 진행
 * 0 → 다른 워커가 이미 가져갔다      → 내 결과를 버린다
 * </pre>
 *
 * <h2>다른 점 하나 — {@code SKIP LOCKED}</h2>
 *
 * <p>좌석에는 {@code SKIP LOCKED}를 쓰지 않았고 여기서는 쓴다. <b>경합의 성격이
 * 다르기 때문이다.</b>
 *
 * <table>
 *   <caption>좌석 행과 outbox 행</caption>
 *   <tr><th></th><th>{@code seat_inventory}</th><th>{@code outbox}</th></tr>
 *   <tr><td>요청이 원하는 것</td><td><b>그 좌석</b> — 대체 불가</td>
 *       <td><b>일거리 아무거나</b> — 행끼리 교환 가능</td></tr>
 *   <tr><td>남이 잡고 있으면</td><td>기다리거나 거절한다</td><td><b>건너뛴다</b></td></tr>
 *   <tr><td>기다림의 의미</td><td>경쟁 그 자체 — 한 명만 이겨야 한다</td>
 *       <td>순수한 낭비 — 옆 행을 집으면 된다</td></tr>
 * </table>
 *
 * <p>{@code SKIP LOCKED}가 없어도 정합성은 같다. 뒤에 온 워커가 앞 워커의
 * 커밋을 기다렸다가 조건이 어긋난 것을 보고 {@code rowsAffected = 0}을 받을
 * 뿐이다. 하지만 그 기다림 동안 <b>워커 하나가 통째로 놀고</b>, 앱 2대를 띄운
 * 이유가 사라진다. 두 워커가 서로를 막지 않는 것이 이 절의 요구다
 * (concurrency-spec.md 6절).
 */
public interface OutboxRepository extends JpaRepository<Outbox, Long> {

    /**
     * 집을 수 있는 행의 id를 잠근 채 고른다. <b>클레임의 1단계다.</b>
     *
     * <p>대상은 둘이다.
     *
     * <ol>
     *   <li>{@code PENDING}이고 재시도 예정 시각이 지난 행. {@code next_retry_at}이
     *       {@code NULL}이면 아직 실패한 적이 없다는 뜻이라 바로 대상이다</li>
     *   <li>{@code SENDING}인데 클레임이 만료된 행 — <b>집은 워커가 죽은
     *       경우다.</b> {@code seat_inventory}가 만료된 {@code HELD}를 다음
     *       요청에 넘기는 것과 같은 구조다(concurrency-spec.md 4.3)</li>
     * </ol>
     *
     * <p>{@code FOR UPDATE}로 잠그므로 2단계 UPDATE까지 다른 워커가 이 행에
     * 손대지 못한다. {@code SKIP LOCKED}는 <b>남이 잡은 행에서 기다리지 않고
     * 다음 행으로 넘어가게</b> 한다 — 클래스 주석의 표 참조.
     *
     * <p>{@code ORDER BY id}는 데드락 회피와 같은 이유다. 워커 둘이 서로 다른
     * 순서로 여러 행을 잠그면 순환 대기가 생긴다(concurrency-spec.md 5.1).
     * {@code SKIP LOCKED}가 있으면 대기 자체가 없어 데드락 조건이 성립하지
     * 않지만, 두 장치 중 하나에만 기대지 않는다.
     */
    @Query(value = """
            SELECT id FROM outbox
             WHERE (status = 'PENDING' AND (next_retry_at IS NULL OR next_retry_at <= now()))
                OR (status = 'SENDING'
                    AND claimed_at <= now() - (:claimTimeoutSeconds * interval '1 second'))
             ORDER BY id
             FOR UPDATE SKIP LOCKED
             LIMIT :batchSize
            """, nativeQuery = true)
    List<Long> findClaimableIds(@Param("claimTimeoutSeconds") int claimTimeoutSeconds,
                                @Param("batchSize") int batchSize);

    /**
     * 고른 행을 {@code SENDING}으로 바꾼다. <b>클레임의 2단계다.</b>
     *
     * <p>1단계가 이미 잠근 행이므로 여기서 지는 일은 없다. 그래도 상태 조건을
     * WHERE에 남긴다 — 이 메서드만 따로 호출되는 실수가 생겨도 {@code SENT}나
     * {@code FAILED}를 되살리지 않는다.
     */
    @Modifying
    @Query(value = """
            UPDATE outbox SET status = 'SENDING', claimed_at = now()
             WHERE id IN (:ids) AND status IN ('PENDING', 'SENDING')
            """, nativeQuery = true)
    int markSending(@Param("ids") Collection<Long> ids);

    /**
     * 발송 성공을 기록한다. <b>{@code SENDING}인 행만 바꾼다.</b>
     *
     * <p>{@code rowsAffected = 0}은 <b>내가 발송하는 동안 클레임이 만료돼 다른
     * 워커가 이 행을 가져갔다</b>는 뜻이다. 그 경우 이 행은 저쪽이 책임지므로
     * 내 결과를 버린다 — 여기서 상태 조건 없이 덮어쓰면 저쪽의 진행을 지운다.
     */
    @Modifying
    @Query(value = """
            UPDATE outbox SET status = 'SENT', sent_at = now(), next_retry_at = NULL
             WHERE id = :id AND status = 'SENDING'
            """, nativeQuery = true)
    int markSent(@Param("id") long id);

    /**
     * 발송 실패를 기록하고 재시도를 예약한다. {@code SENDING}인 행만 바꾼다.
     *
     * <p>{@code retry_count}를 <b>DB에서 증가시킨다.</b> 워커가 읽은 값에 1을
     * 더해 쓰면 클레임이 만료된 상황에서 두 워커의 증가가 겹쳐 하나가 사라진다.
     * 상태 조건이 그것을 막지만, 증가를 SQL에 두면 조건이 없어도 안전하다.
     */
    @Modifying
    @Query(value = """
            UPDATE outbox
               SET status = 'PENDING',
                   retry_count = retry_count + 1,
                   next_retry_at = now() + (:backoffMillis * interval '1 millisecond'),
                   claimed_at = NULL
             WHERE id = :id AND status = 'SENDING'
            """, nativeQuery = true)
    int scheduleRetry(@Param("id") long id, @Param("backoffMillis") long backoffMillis);

    /**
     * 재시도 상한을 소진한 행을 {@code FAILED}로 내린다. 종착이며 워커가 다시
     * 집지 않는다({@link OutboxStatus#FAILED}).
     */
    @Modifying
    @Query(value = """
            UPDATE outbox
               SET status = 'FAILED', retry_count = retry_count + 1, next_retry_at = NULL
             WHERE id = :id AND status = 'SENDING'
            """, nativeQuery = true)
    int markFailed(@Param("id") long id);

    /**
     * 확정 트랜잭션이 알림을 큐에 넣는다.
     *
     * <p><b>JPA {@code save()}가 아니라 네이티브 INSERT다.</b> 영속성 컨텍스트에
     * 엔티티를 올리면 flush 시점이 트랜잭션 커밋 직전으로 밀리는데, 그러면 U-12
     * 위반이 확정 로직이 끝난 뒤 엉뚱한 자리에서 터진다. 여기서 즉시 나가야
     * 실패 지점과 원인이 붙는다.
     */
    @Modifying
    @Query(value = """
            INSERT INTO outbox (reservation_id, notification_type, status, retry_count,
                                payload, created_at)
            VALUES (:reservationId, :notificationType, 'PENDING', 0, :payload, now())
            """, nativeQuery = true)
    void insertPending(@Param("reservationId") long reservationId,
                       @Param("notificationType") String notificationType,
                       @Param("payload") String payload);
}
