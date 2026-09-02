package com.inhalab.holdfast.reservation;

import com.inhalab.holdfast.api.ApiException;
import com.inhalab.holdfast.api.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code unique} 전략이 <b>앱 락 없이 제약만으로 막는지</b> 확인한다.
 * concurrency-spec.md 4.4·7.7.2·8절.
 *
 * <p>7.7.2가 이 전략에서 보라고 한 넷을 그대로 옮겼다.
 *
 * <ol>
 *   <li><b>제약 위반이 0이 아닌가</b> — 여기서는 0이면 오히려 이상하다</li>
 *   <li><b>V-1·V-2가 0인가</b> — 앱 락 없이 제약만으로 충분한지가 이 전략의 질문이다</li>
 *   <li><b>락 포기율이 해당 없음인가</b> — 앱 락도 재시도도 없다</li>
 *   <li><b>실패 모드</b> — U-2를 제거하면 {@code none}과 같아지는가</li>
 * </ol>
 *
 * <h2>U-2를 지우지 않는다 — 이 전략에서는 특히 치명적이다</h2>
 *
 * <p>{@code none} 테스트의 {@code seed()}는 {@code ux_seat_hold_active}를
 * 지운다(erd.md 3.1). 그 코드를 복사해 오면 다른 전략에서는 "최후 방어선이
 * 사라지는" 정도지만, <b>이 전략에서는 방어선이 통째로 없어진다.</b> 앱 락이
 * 없으므로 U-2가 유일한 직렬화 지점이고, 없으면 그대로 {@code none}이 된다.
 *
 * <p>그 사실을 말로만 두지 않고 4번 테스트가 <b>실제로 지워서 확인한다.</b>
 *
 * <p>H2가 아니라 실제 Postgres를 쓴다 — 부분 유니크 인덱스와 동시 INSERT의
 * 직렬화 의미론이 달라 아무것도 보장하지 않기 때문이다(8절).
 */
@SpringBootTest(properties = {
        "holdfast.strategy=unique",
        // 7.3 고정 변수를 그대로 쓴다. none 테스트는 경합 재현을 위해 풀을
        // 스레드 수보다 넉넉히(40) 잡지만, 측정 조건과 같은 30으로 둔다.
        "spring.datasource.hikari.maximum-pool-size=30"
})
@Testcontainers
@DisplayName("unique: 앱 락 없이 제약만으로 초과 홀드와 초과 확정을 막는다")
class UniqueSeatHoldStrategyConcurrencyTest {

    private static final long SESSION_ID = 1L;
    private static final long SEAT_ID = 1L;
    private static final int THREADS = 30;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    // Redis 자체는 이 전략이 쓰지 않지만, Redisson 자동설정이 기동 시 접속을
    // 시도하므로 컨텍스트를 띄우려면 필요하다.
    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private SeatHoldService seatHoldService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private SeatHoldStrategy strategy;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        // **U-2를 지우지 않는다.** V1__init_schema.sql이 만든 ux_seat_hold_active를
        // 그대로 둔다. 이 전략에서는 그것이 유일한 방어선이다 — 클래스 주석 참조.
        jdbc.execute("""
                TRUNCATE TABLE ticket_scan, ticket, outbox, idempotency_record,
                               payment, reservation_seat, reservation,
                               seat_hold, seat_inventory, user_session_quota,
                               seat, zone, seat_layout, event_session, program
                RESTART IDENTITY CASCADE
                """);

        jdbc.update("INSERT INTO program (id, name, created_at) VALUES (1, '경합 테스트', now())");
        jdbc.update("INSERT INTO seat_layout (id, name, created_at) VALUES (1, '경합 테스트', now())");
        jdbc.update("INSERT INTO zone (id, seat_layout_id, name, sort_order) VALUES (1, 1, 'A', 1)");
        jdbc.update("INSERT INTO seat (id, zone_id, seat_no, row_index, col_index) VALUES (?, 1, 'A-1', 1, 1)",
                SEAT_ID);
        jdbc.update("""
                INSERT INTO event_session (id, program_id, seat_layout_id, starts_at, ends_at,
                                           entry_opens_at, entry_closes_at, reserve_opens_at,
                                           max_per_user, status)
                VALUES (?, 1, 1, now() + interval '1 day', now() + interval '1 day 2 hours',
                        now(), now() + interval '1 day 2 hours', now() - interval '1 hour', 4, 'OPEN')
                """, SESSION_ID);

        jdbc.update("""
                INSERT INTO seat_inventory (session_id, seat_id, status, version)
                VALUES (?, ?, 'AVAILABLE', 0)
                """, SESSION_ID, SEAT_ID);

        for (int i = 1; i <= THREADS; i++) {
            jdbc.update("INSERT INTO user_session_quota (session_id, user_id, held_count) VALUES (?, ?, 0)",
                    SESSION_ID, (long) i);
        }
    }

    /** 30스레드가 좌석 1석을 동시에 홀드한다. 결과를 종류별로 센다. */
    private Outcome raceForOneSeat() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(THREADS);
        Outcome o = new Outcome();

        for (int i = 1; i <= THREADS; i++) {
            long userId = i;
            pool.submit(() -> {
                try {
                    startGate.await();
                    seatHoldService.hold(SESSION_ID, userId, List.of(SEAT_ID), UUID.randomUUID().toString());
                    o.succeeded.incrementAndGet();
                } catch (DataIntegrityViolationException e) {
                    // **이 전략에서는 정상 동작이다.** 제약이 제 일을 했다(4.4).
                    // 웹 계층이라면 공통 핸들러가 409로 바꾸고 카운터를 올린다.
                    o.constraintViolation.incrementAndGet();
                } catch (ApiException e) {
                    if (e.getCode() == ErrorCode.LOCK_TIMEOUT || e.getCode() == ErrorCode.RETRY_EXHAUSTED) {
                        o.lockGiveup.incrementAndGet();
                    } else {
                        o.rejected.incrementAndGet();
                    }
                } catch (Exception e) {
                    o.failed.incrementAndGet();
                } finally {
                    finished.countDown();
                }
            });
        }
        startGate.countDown();
        assertThat(finished.await(60, TimeUnit.SECONDS)).as("모든 스레드가 60초 안에 끝나야 한다").isTrue();
        pool.shutdown();
        return o;
    }

    private static final class Outcome {
        final AtomicInteger succeeded = new AtomicInteger();
        final AtomicInteger rejected = new AtomicInteger();
        final AtomicInteger lockGiveup = new AtomicInteger();
        final AtomicInteger constraintViolation = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
    }

    private int activeHolds() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM seat_hold
                 WHERE session_id = ? AND seat_id = ? AND status = 'HELD'
                """, Integer.class, SESSION_ID, SEAT_ID);
    }

    private int oversoldHolds() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT session_id, seat_id FROM seat_hold
                     WHERE status = 'HELD' GROUP BY session_id, seat_id HAVING count(*) > 1
                ) t
                """, Integer.class);
    }

    @Test
    @DisplayName("U-2 인덱스가 걸려 있다 — 이 전략에서는 유일한 방어선이다")
    void uniqueIndexIsInPlace() {
        Integer indexes = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'ux_seat_hold_active'", Integer.class);

        assertThat(indexes)
                .as("""
                        ux_seat_hold_active(U-2)가 있어야 한다. none 테스트의 seed()를 복사하면서 \
                        DROP INDEX를 함께 가져오면 여기서 걸린다 — 다른 전략에서는 최후 방어선이 \
                        사라지는 정도지만 이 전략에서는 방어선이 통째로 없어져 none과 같아진다 \
                        (concurrency-spec 4.4, erd.md 3.1)""")
                .isEqualTo(1);

        assertThat(strategy)
                .as("holdfast.strategy=unique면 unique 구현체가 주입돼야 한다")
                .isInstanceOf(UniqueSeatHoldStrategy.class);
    }

    @Test
    @DisplayName("제약 위반이 발생하고, 그것이 정상 동작이다 — 초과 홀드는 0")
    void constraintViolationsHappenAndAreNormal() throws Exception {
        Outcome o = raceForOneSeat();

        int held = activeHolds();
        System.out.printf(
                "[unique 홀드] 스레드 %d · 성공 %d · 정상거절 %d · 제약위반 %d · 락포기 %d · 예외 %d "
                        + "→ HELD 홀드 %d건, 초과 홀드(V-2) %d건%n",
                THREADS, o.succeeded.get(), o.rejected.get(), o.constraintViolation.get(),
                o.lockGiveup.get(), o.failed.get(), held, oversoldHolds());

        assertThat(o.failed.get()).as("예기치 못한 예외는 없어야 한다").isZero();

        // 7.7.2 ① — 이 전략에서는 제약 위반이 0이면 오히려 이상하다.
        assertThat(o.constraintViolation.get())
                .as("""
                        제약 위반이 발생해야 한다. 0이라면 U-2가 걸려 있지 않거나(그러면 초과 \
                        홀드가 나온다) 경합이 실제로 일어나지 않은 것이다 — 이 전략은 제약 위반을 \
                        정상 동작으로 세므로(4.4) 이 값이 0이면 아무것도 측정하지 못한 것이다""")
                .isPositive();

        // 7.7.2 ② — 앱 락 없이 제약만으로 충분한가.
        assertThat(oversoldHolds()).as("초과 홀드(V-2)는 0이어야 한다").isZero();
        assertThat(held).as("좌석 1석에 활성 홀드는 정확히 1건이어야 한다").isEqualTo(1);
        assertThat(o.succeeded.get()).as("좌석을 잡았다고 믿는 스레드는 정확히 하나여야 한다").isEqualTo(1);

        // 7.7.2 ③ — 락 포기는 해당 없음이다. 앱 락도 재시도도 없다.
        assertThat(o.lockGiveup.get())
                .as("""
                        락 포기는 0이어야 한다 — 7.6의 이 전략 행은 '—'(해당 없음)다. \
                        앱 락이 없어 LOCK_TIMEOUT이, 재시도가 없어 RETRY_EXHAUSTED가 나올 수 없다. \
                        0이 아니면 어딘가에 앱 레벨 락이나 재시도가 섞여 들어간 것이다""")
                .isZero();

        assertThat(o.succeeded.get() + o.rejected.get() + o.constraintViolation.get())
                .as("모든 스레드가 성공·정상거절·제약위반 중 하나로 끝나야 한다")
                .isEqualTo(THREADS);
    }

    @Test
    @DisplayName("동시 확정 — 성공은 1건, 초과 확정(V-1)은 0이다")
    void concurrentConfirmsProduceNoOverConfirmation() throws Exception {
        String holdId = UUID.randomUUID().toString();
        seatHoldService.hold(SESSION_ID, 1L, List.of(SEAT_ID), holdId);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(THREADS);
        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    reservationService.confirm(holdId, 1L);
                    confirmed.incrementAndGet();
                } catch (ApiException e) {
                    rejected.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                } finally {
                    finished.countDown();
                }
            });
        }
        startGate.countDown();
        assertThat(finished.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        Integer oversoldSeats = jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT rs.seat_inventory_id
                      FROM reservation_seat rs
                      JOIN reservation r ON r.id = rs.reservation_id
                     WHERE r.status = 'CONFIRMED'
                     GROUP BY rs.seat_inventory_id
                    HAVING count(*) > 1
                ) t
                """, Integer.class);
        Integer confirmedReservations = jdbc.queryForObject(
                "SELECT count(*) FROM reservation WHERE status = 'CONFIRMED'", Integer.class);

        System.out.printf(
                "[unique 확정] 스레드 %d · 확정성공 %d · 정상거절 %d · 예외 %d "
                        + "→ 확정된 예약 %d건, 초과 확정(V-1) %d건%n",
                THREADS, confirmed.get(), rejected.get(), failed.get(),
                confirmedReservations, oversoldSeats);

        assertThat(failed.get()).as("예기치 못한 예외는 없어야 한다").isZero();
        assertThat(oversoldSeats)
                .as("""
                        초과 확정(V-1)은 0이어야 한다 — 검수 기준(REQ-01). 확정 경로에는 유니크 \
                        제약이 관여하지 않으므로 이것을 막는 것은 confirmIfStillHeld의 원자성이다. \
                        0이 아니면 이 전략이 markSoldUnconditionally를 쓰고 있는지 확인한다""")
                .isZero();
        assertThat(confirmed.get()).as("확정에 성공한 스레드는 정확히 하나여야 한다").isEqualTo(1);
        assertThat(confirmedReservations).isEqualTo(1);
    }

    @Test
    @DisplayName("판매된 좌석은 다시 잡히지 않는다 — U-2가 못 막는 구간")
    void soldSeatCannotBeHeldAgain() {
        String holdId = UUID.randomUUID().toString();
        seatHoldService.hold(SESSION_ID, 1L, List.of(SEAT_ID), holdId);
        reservationService.confirm(holdId, 1L);

        // 확정으로 seat_hold가 CONFIRMED가 되면 U-2의 부분 인덱스에서 빠진다.
        // 인덱스 자리가 비므로 **제약만으로는 이 INSERT를 막지 못한다.**
        // 재고 상태 확인이 그 구간을 담당한다.
        Integer activeAfterConfirm = activeHolds();
        assertThat(activeAfterConfirm)
                .as("확정 후 활성 홀드는 0 — U-2 인덱스 자리가 비어 있다")
                .isZero();

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> seatHoldService.hold(SESSION_ID, 2L, List.of(SEAT_ID), UUID.randomUUID().toString())))
                .as("판매된 좌석은 재고 상태 확인에서 걸러져야 한다")
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.SEAT_ALREADY_SOLD);
    }

    /**
     * 7.7.2 ④ 실패 모드 — <b>U-2를 제거하면 {@code none}과 같아진다.</b>
     *
     * <p>다른 전략에서 U-2는 앱 락 뒤의 최후 방어선이라 없어도 앱 락이 막는다.
     * 이 전략은 앱 락이 없으므로 <b>U-2가 사라지면 남는 방어가 없다.</b> 그
     * 사실을 말로 두지 않고 실제로 지워서 확인한다.
     *
     * <p>이 테스트가 통과한다는 것은 "제약이 실제로 일하고 있었다"는 증거이기도
     * 하다 — 다른 무언가가 막고 있었다면 인덱스를 지워도 결과가 같아야 한다.
     */
    @Test
    @DisplayName("실패 모드 — U-2를 지우면 none과 같아진다(초과 홀드 발생)")
    void withoutUniqueIndexItDegradesToNone() throws Exception {
        jdbc.execute("DROP INDEX IF EXISTS ux_seat_hold_active");
        try {
            Outcome o = raceForOneSeat();
            int held = activeHolds();

            System.out.printf(
                    "[unique U-2제거] 스레드 %d · 성공 %d · 정상거절 %d · 제약위반 %d · 예외 %d "
                            + "→ HELD 홀드 %d건, 초과 홀드(V-2) %d건%n",
                    THREADS, o.succeeded.get(), o.rejected.get(), o.constraintViolation.get(),
                    o.failed.get(), held, oversoldHolds());

            assertThat(o.failed.get()).as("예기치 못한 예외는 없어야 한다").isZero();
            assertThat(o.constraintViolation.get())
                    .as("인덱스가 없으니 제약 위반도 없다")
                    .isZero();

            assertThat(held)
                    .as("""
                            U-2가 없으면 초과 홀드가 발생해야 한다. 1건뿐이라면 어딘가에 다른 \
                            방어가 남아 있다는 뜻이고, 그러면 이 전략이 재는 것이 제약이 아니게 된다""")
                    .isGreaterThan(1);
            assertThat(oversoldHolds()).as("초과 홀드(V-2)가 관측돼야 한다").isPositive();
            assertThat(o.succeeded.get())
                    .as("두 스레드 이상이 '내가 좌석을 잡았다'고 믿어야 한다 — none과 같은 상태다")
                    .isGreaterThan(1);
        } finally {
            jdbc.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS ux_seat_hold_active
                      ON seat_hold (session_id, seat_id) WHERE status = 'HELD'
                    """);
        }
    }
}
