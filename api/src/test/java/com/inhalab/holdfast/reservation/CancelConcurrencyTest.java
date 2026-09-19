package com.inhalab.holdfast.reservation;

import com.inhalab.holdfast.api.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>CS-4 — 취소가 겹쳐도 좌석이 한 번만 반환되는가.</b>
 * concurrency-spec.md 1절 CS-4 · 7.2.4, 이슈 #200.
 *
 * <h2>이 자리가 비어 있었다</h2>
 *
 * <p>CS-4는 검증 쿼리도, 부하 경로도, 동시성 테스트도 없이
 * <b>{@code MinimumScopeFlowTest}의 단일 스레드 재취소 하나</b>로만 덮여 있었다.
 * {@code requirements.md} 3절이 새 REQ를 두지 않은 근거로 든 것이 취소 API의
 * 멱등성인데, <b>그 멱등성은 순차 재호출을 전제한다.</b>
 *
 * <h2>왜 부하 시나리오만으로는 부족한가</h2>
 *
 * <p>7.2.4의 {@code cancel.js}는 HTTP로 취소 둘을 동시에 쏘지만, 두
 * <b>트랜잭션</b>이 위험한 구간에서 겹쳤는지는 보장하지 못한다. 여기서는
 * {@link CountDownLatch}로 같은 순간에 풀어 그 구간을 직접 겨눈다 — 8절이
 * 단위 경합 테스트와 부하 테스트를 함께 두는 이유와 같다.
 *
 * <h2>무엇을 겨누나</h2>
 *
 * <p>{@link ReservationService#cancel}은 예약을 <b>읽은 뒤</b> 할당량 행을
 * {@code FOR UPDATE}로 잡는다. READ COMMITTED에서 뒤엣 트랜잭션은 락을
 * 기다리는 동안 <b>이미 읽은 스냅샷</b>을 쥐고 있으므로, 앞엣 것이 취소를
 * 끝낸 뒤에도 "아직 CONFIRMED"로 보고 한 번 더 반환할 수 있다.
 *
 * <p><b>할당량이 그 증거다.</b> 좌석 반환은 {@code status = 'SOLD'} 조건부라
 * 두 번째가 조용히 0행을 고치고 끝나지만, {@code held_count} 감산에는 조건이
 * 없다. 그래서 보유량을 <b>1보다 크게</b> 두고 잰다 — 1에서 재면 두 번 깎여도
 * {@code Math.max(0, ...)}가 눌러 흔적이 사라진다(7.2.4).
 *
 * <p>H2가 아니라 실제 Postgres를 쓴다 — {@code FOR UPDATE} 의미론이 달라
 * 동시성 테스트가 통과해도 아무것도 보장하지 않기 때문이다(8절).
 */
@SpringBootTest(properties = {
        "holdfast.strategy=pessimistic",
        // 재는 임계 구역이 전략 밖이라(7.2.4) 전략은 하나로 고정한다.
        "holdfast.outbox.scheduler.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=30",
        "holdfast.lock-timeout-ms=1000"
})
@Testcontainers
@DisplayName("CS-4: 취소가 겹쳐도 좌석과 할당량이 한 번만 되돌아온다 (#200)")
class CancelConcurrencyTest {

    private static final long SESSION_ID = 1L;
    private static final long USER_ID = 1L;
    private static final int SEATS = 4;
    private static final int THREADS = 12;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @Autowired
    private SeatHoldService seatHoldService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.execute("""
                TRUNCATE TABLE ticket_scan, ticket, outbox, idempotency_record,
                               payment, reservation_seat, reservation,
                               seat_hold, seat_inventory, user_session_quota,
                               seat, zone, seat_layout, event_session, program
                RESTART IDENTITY CASCADE
                """);

        jdbc.update("INSERT INTO program (id, name, created_at) VALUES (1, '취소 경합', now())");
        jdbc.update("INSERT INTO seat_layout (id, name, created_at) VALUES (1, '취소 경합', now())");
        jdbc.update("INSERT INTO zone (id, seat_layout_id, name, sort_order) VALUES (1, 1, 'A', 1)");
        for (int i = 1; i <= SEATS; i++) {
            jdbc.update("INSERT INTO seat (id, zone_id, seat_no, row_index, col_index) VALUES (?, 1, ?, 1, ?)",
                    (long) i, "A-" + i, i);
        }
        jdbc.update("""
                INSERT INTO event_session (id, program_id, seat_layout_id, starts_at, ends_at,
                                           entry_opens_at, entry_closes_at, reserve_opens_at,
                                           max_per_user, status)
                VALUES (?, 1, 1, now() + interval '1 day', now() + interval '1 day 2 hours',
                        now(), now() + interval '1 day 2 hours', now() - interval '1 hour', 4, 'OPEN')
                """, SESSION_ID);
        for (int i = 1; i <= SEATS; i++) {
            jdbc.update("""
                    INSERT INTO seat_inventory (session_id, seat_id, status, version)
                    VALUES (?, ?, 'AVAILABLE', 0)
                    """, SESSION_ID, (long) i);
        }
        jdbc.update("INSERT INTO user_session_quota (session_id, user_id, held_count) VALUES (?, ?, 0)",
                SESSION_ID, USER_ID);
    }

    /**
     * <b>지금 깨진다. 고치는 것은 이 이슈가 아니다</b>(#200 → #209).
     *
     * <p>실측: 보유량 4에서 좌석 하나짜리 예약을 동시에 취소하면
     * <b>스레드 2개에 4 → 2, 12개에 4 → 0</b>이 나온다. 정답은 3이다.
     * 취소 하나당 한 번만 깎여야 하는데 <b>겹친 수만큼 깎인다.</b>
     *
     * <p><b>드리프트가 아래로 난다는 것이 중요하다.</b> {@code held_count}가
     * 실제 보유량보다 작아지면 그 사용자는 상한을 넘겨 더 잡을 수 있다 —
     * REQ-11("상한 초과 승인 0건")을 우회하는 경로다.
     *
     * <p><b>7.2.4의 부하 시나리오는 이것을 못 봤다.</b> 거기서는 한 사용자가
     * 한 번에 한 좌석만 들고 있어 보유량이 1이고, 두 번 깎여도
     * {@code Math.max(0, ...)}가 눌러 <b>정답(0)과 구분되지 않는다.</b>
     * `verify-cancel.sql`의 C-3이 "0에 눌린 것은 안 보인다"라고 적은 한계가
     * 그대로 나타난 것이고, <b>이 테스트가 보유량을 4로 두는 이유가 그것이다.</b>
     *
     * <p>고친 뒤 {@code @Disabled}를 떼면 회귀 수단이 된다. 어긋나면 실패하는
     * 것을 확인했으므로(위 실측) <b>통과만 보고 회귀 수단이라 하는 것이 아니다.</b>
     */
    @Test
    @Disabled("#209 — #200이 찾은 결함 — 겹친 취소가 held_count를 여러 번 깎는다. 고치는 PR에서 연다")
    @DisplayName("12스레드가 같은 예약을 동시에 취소해도 할당량은 정확히 1만 줄어든다")
    void concurrentCancelsDecrementQuotaExactlyOnce() throws Exception {
        // 좌석 넷을 각각 따로 잡아 보유량을 4로 만든다. **1에서 재지 않는다** —
        // 두 번 깎여도 Math.max(0, ...)가 눌러 이중 감산이 안 보인다(7.2.4).
        String targetHoldId = null;
        for (int seat = 1; seat <= SEATS; seat++) {
            String holdId = UUID.randomUUID().toString();
            seatHoldService.hold(SESSION_ID, USER_ID, List.of((long) seat), holdId);
            if (seat == 1) {
                targetHoldId = holdId;
            }
        }
        assertThat(heldCount()).as("사전 조건 — 보유량이 4여야 한다").isEqualTo(SEATS);

        // 좌석 하나만 확정한다. 그 좌석이 SOLD 가 되고 거기서부터 CS-4 구역이다.
        long reservationId = reservationService.confirm(targetHoldId, USER_ID).getId();
        assertThat(seatStatus(1L)).as("확정했으니 SOLD 여야 한다").isEqualTo("SOLD");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(THREADS);
        AtomicInteger ok = new AtomicInteger();
        ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try {
                    // 같은 순간에 풀어 취소 트랜잭션들을 겹치게 한다.
                    startGate.await();
                    reservationService.cancel(reservationId, USER_ID);
                    ok.incrementAndGet();
                } catch (ApiException e) {
                    failures.add("ApiException " + e.getCode());
                } catch (Exception e) {
                    failures.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                } finally {
                    finished.countDown();
                }
            });
        }

        startGate.countDown();
        assertThat(finished.await(60, TimeUnit.SECONDS))
                .as("모든 스레드가 60초 안에 끝나야 한다")
                .isTrue();
        pool.shutdown();

        // **멱등성 — 동시 호출에서도 전부 성공해야 한다.** api-spec 6.1이 재취소를
        // 409가 아니라 200으로 설계했고, 그 성질이 겹친 호출에서도 서야 한다.
        assertThat(failures)
                .as("취소는 멱등이므로 동시 호출도 전부 성공해야 한다 (api-spec 6.1)")
                .isEmpty();
        assertThat(ok.get()).isEqualTo(THREADS);

        // **이 줄이 본체다.** 이중 감산이 있었다면 4가 아니라 그 아래로 떨어진다.
        assertThat(heldCount())
                .as("""
                        보유량은 정확히 1만 줄어야 한다(4 → 3). 이보다 작으면 취소가 겹치는 \
                        동안 held_count 가 여러 번 깎인 것이다 — ReservationService#cancel 이 \
                        예약을 읽는 시점과 할당량 행을 FOR UPDATE 로 잡는 시점 사이에서 \
                        스냅샷이 갱신되지 않기 때문이다(7.2.4)""")
                .isEqualTo(SEATS - 1);

        // 좌석은 돌아왔고, 건드리지 않은 셋은 그대로다.
        assertThat(seatStatus(1L)).as("취소했으니 AVAILABLE 이어야 한다").isEqualTo("AVAILABLE");
        for (long seat = 2; seat <= SEATS; seat++) {
            assertThat(seatStatus(seat))
                    .as("좌석 %d 는 이 취소와 무관하다 — 남의 좌석을 풀면 안 된다", seat)
                    .isEqualTo("HELD");
        }

        // 예약은 한 번만 취소된 상태여야 한다.
        assertThat(jdbc.queryForObject(
                "SELECT status FROM reservation WHERE id = ?", String.class, reservationId))
                .isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("C-2가 보는 것 — 확정된 예약의 좌석이 재고에서 비는 일이 없다")
    void confirmedReservationKeepsItsSeatSold() throws Exception {
        String holdA = UUID.randomUUID().toString();
        String holdB = UUID.randomUUID().toString();
        seatHoldService.hold(SESSION_ID, USER_ID, List.of(1L), holdA);
        seatHoldService.hold(SESSION_ID, USER_ID, List.of(2L), holdB);

        long cancelled = reservationService.confirm(holdA, USER_ID).getId();
        long kept = reservationService.confirm(holdB, USER_ID).getId();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(THREADS);

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    reservationService.cancel(cancelled, USER_ID);
                } catch (Exception ignored) {
                    // 실패 자체는 위 테스트가 본다. 여기서 보는 것은 끝 상태다.
                } finally {
                    finished.countDown();
                }
            });
        }
        startGate.countDown();
        assertThat(finished.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        // verify-cancel.sql 의 C-2 와 같은 질의다 — V-4 가 보지 않는 방향이다.
        Integer stranded = jdbc.queryForObject("""
                SELECT count(*)
                  FROM reservation r
                  JOIN reservation_seat rs ON rs.reservation_id = r.id
                  JOIN seat_inventory si ON si.id = rs.seat_inventory_id
                 WHERE r.session_id = ? AND r.status = 'CONFIRMED' AND si.status <> 'SOLD'
                """, Integer.class, SESSION_ID);

        // **취소가 실제로 됐는지를 먼저 본다.** 아래 stranded 만 보면 «아무 일도
        // 안 일어난» 회귀에도 0이 나와 초록이 된다 — 취소가 전부 실패하면
        // cancelled 는 CONFIRMED 로 남고 좌석도 SOLD 라 어긋남이 없다. 같은
        // 파일의 위 테스트가 @Disabled 인 동안에는 이 테스트가 혼자 서야
        // 한다(#210 리뷰).
        assertThat(jdbc.queryForObject(
                "SELECT status FROM reservation WHERE id = ?", String.class, cancelled))
                .as("취소가 실제로 됐어야 한다 — 아니면 아래 단언이 헛통과한다")
                .isEqualTo("CANCELLED");
        assertThat(seatStatus(1L))
                .as("취소했으니 좌석이 돌아왔어야 한다")
                .isEqualTo("AVAILABLE");

        assertThat(stranded)
                .as("""
                        확정된 예약의 좌석이 재고에서 비면 안 된다. 0이 아니면 겹친 취소가 \
                        «남의 좌석»을 푼 것이다 — V-4 는 SOLD 행에서 출발하므로 이 방향을 \
                        보지 않는다(verify-cancel.sql C-2)""")
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM reservation WHERE id = ?", String.class, kept))
                .as("건드리지 않은 예약은 확정 그대로여야 한다")
                .isEqualTo("CONFIRMED");
    }

    private int heldCount() {
        Integer n = jdbc.queryForObject(
                "SELECT held_count FROM user_session_quota WHERE session_id = ? AND user_id = ?",
                Integer.class, SESSION_ID, USER_ID);
        return n == null ? -1 : n;
    }

    private String seatStatus(long seatId) {
        return jdbc.queryForObject(
                "SELECT status FROM seat_inventory WHERE session_id = ? AND seat_id = ?",
                String.class, SESSION_ID, seatId);
    }
}
