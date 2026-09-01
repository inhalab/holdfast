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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
 * {@code pessimistic} 전략이 <b>실제로 막는지</b> 확인한다.
 * concurrency-spec.md 4.2·8절.
 *
 * <p>{@code NoneSeatHoldStrategyConcurrencyTest}와 방향이 반대다. 그쪽은 초과가
 * 발생해야 통과하고, 이쪽은 초과가 0이어야 통과한다. 두 테스트가 같은 부하
 * (스레드 30 · 좌석 1석)를 걸어야 비교가 성립하므로 조건을 맞춰 두었다.
 *
 * <h2>U-2를 지우지 않는다 — 이것이 {@code none} 테스트와의 결정적 차이다</h2>
 *
 * <p>{@code none} 테스트는 {@code seed()}에서 {@code ux_seat_hold_active}를
 * 지운다. 실패 증거를 얻으려면 제약이 없어야 하기 때문이다(erd.md 3.1). 그
 * 코드를 그대로 복사해 오면 <b>이 테스트가 의미를 잃는다.</b>
 *
 * <p>{@code none} 외의 전략에서 U-2는 <b>최후 방어선</b>이고, 제약 위반 횟수는
 * "앱 락이 샜다"만 세는 지표다(7.1·7.6). 인덱스가 없는 상태로 재면 앱 락이
 * 새더라도 아무도 막지 않아 그냥 초과 홀드가 생기고, 제약 위반 카운터는
 * 영원히 0으로 남아 <b>"락이 잘 동작한다"와 "방어선이 통째로 없다"를 구분할 수
 * 없게 된다.</b> 그래서 여기서는 인덱스를 그대로 두고, 위반이 한 번도
 * 일어나지 않는지를 함께 검증한다.
 *
 * <p>H2가 아니라 실제 Postgres를 쓴다 — {@code FOR UPDATE} 의미론이 달라
 * 동시성 테스트가 통과해도 아무것도 보장하지 않기 때문이다(8절).
 */
@SpringBootTest(properties = {
        "holdfast.strategy=pessimistic",
        // 7.3 고정 변수를 그대로 쓴다. none 테스트는 경합 재현을 위해 풀을 스레드
        // 수보다 넉넉히(40) 잡지만, 이쪽은 대기 중 커넥션을 쥐는 것 자체가 이
        // 전략의 성능 특성이므로(4.2) 측정 조건과 같은 30으로 둔다.
        "spring.datasource.hikari.maximum-pool-size=30",
        // 7.3 고정 변수. 락 대기 상한 1초.
        "holdfast.lock-timeout-ms=1000"
})
@Testcontainers
@DisplayName("pessimistic: 행 락이 초과 홀드와 초과 확정을 모두 막는다")
class PessimisticSeatHoldStrategyConcurrencyTest {

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
    private SeatInventoryRepository seatInventoryRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        // **U-2를 지우지 않는다.** V1__init_schema.sql이 만든 ux_seat_hold_active를
        // 그대로 둔다 — 이 전략의 실제 측정 환경과 같다(seed.sh는 none일 때만
        // u2-drop.sql을 실행한다). 위 클래스 주석에 이유를 적었다.
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

        // 좌석은 한 자리뿐이다. 한 명만 잡아야 한다.
        jdbc.update("""
                INSERT INTO seat_inventory (session_id, seat_id, status, version)
                VALUES (?, ?, 'AVAILABLE', 0)
                """, SESSION_ID, SEAT_ID);

        // CS-6 할당량 행은 사전 생성한다(concurrency-spec 1.1). 사용자를 스레드마다
        // 다르게 두어 할당량 경합이 좌석 경합을 가리지 않게 한다.
        for (int i = 1; i <= THREADS; i++) {
            jdbc.update("INSERT INTO user_session_quota (session_id, user_id, held_count) VALUES (?, ?, 0)",
                    SESSION_ID, (long) i);
        }
    }

    @Test
    @DisplayName("U-2 인덱스가 걸려 있다 — 지워졌다면 이 클래스의 검증이 무의미해진다")
    void uniqueIndexIsInPlace() {
        Integer indexes = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'ux_seat_hold_active'", Integer.class);

        assertThat(indexes)
                .as("""
                        ux_seat_hold_active(U-2)가 있어야 한다. none 테스트의 seed()를 복사하면서 \
                        DROP INDEX를 함께 가져오면 여기서 걸린다 — 제약이 없는 상태로 재면 \
                        제약 위반 카운터가 영원히 0이라 "락이 동작한다"와 "방어선이 없다"를 \
                        구분할 수 없다(erd.md 3.1, concurrency-spec 7.6)""")
                .isEqualTo(1);

        assertThat(strategy)
                .as("holdfast.strategy=pessimistic이면 pessimistic 구현체가 주입돼야 한다")
                .isInstanceOf(PessimisticSeatHoldStrategy.class);
    }

    @Test
    @DisplayName("30스레드가 좌석 1석을 동시에 홀드해도 HELD 홀드는 1건뿐이다 — 초과 홀드 0")
    void concurrentHoldsOnSingleSeatProduceExactlyOneHold() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(THREADS);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger lockGiveup = new AtomicInteger();
        AtomicInteger constraintViolation = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        for (int i = 1; i <= THREADS; i++) {
            long userId = i;
            pool.submit(() -> {
                try {
                    // 모든 스레드를 같은 순간에 풀어 FOR UPDATE를 겹치게 한다.
                    startGate.await();
                    seatHoldService.hold(SESSION_ID, userId, List.of(SEAT_ID), UUID.randomUUID().toString());
                    succeeded.incrementAndGet();
                } catch (ApiException e) {
                    // 락 포기는 정상 거절과 별도로 센다(7.6.1). 좌석이 남아
                    // 있었을 수도 있는데 기다리다 포기한 것이라 성격이 다르다.
                    if (e.getCode() == ErrorCode.LOCK_TIMEOUT) {
                        lockGiveup.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                } catch (DataIntegrityViolationException e) {
                    // U-2가 발동했다 = 행 락이 막지 못한 요청이 INSERT까지 갔다.
                    constraintViolation.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
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

        Integer heldRows = jdbc.queryForObject("""
                SELECT count(*) FROM seat_hold
                 WHERE session_id = ? AND seat_id = ? AND status = 'HELD'
                """, Integer.class, SESSION_ID, SEAT_ID);

        // V-2와 같은 쿼리 — 같은 (회차, 좌석)에 활성 홀드가 둘 이상인가.
        Integer oversoldHolds = jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT session_id, seat_id FROM seat_hold
                     WHERE status = 'HELD'
                     GROUP BY session_id, seat_id
                    HAVING count(*) > 1
                ) t
                """, Integer.class);

        System.out.printf(
                "[pessimistic 홀드] 스레드 %d · 성공 %d · 정상거절 %d · 락포기 %d · 제약위반 %d · 예외 %d "
                        + "→ HELD 홀드 %d건, 초과 홀드(V-2) %d건%n",
                THREADS, succeeded.get(), rejected.get(), lockGiveup.get(),
                constraintViolation.get(), failed.get(), heldRows, oversoldHolds);

        assertThat(failed.get())
                .as("예기치 못한 예외는 없어야 한다")
                .isZero();

        assertThat(constraintViolation.get())
                .as("""
                        U-2 제약 위반은 0이어야 한다. 0이 아니면 행 락이 막지 못한 요청이 \
                        INSERT까지 도달했다는 뜻이다 — 앱 락이 샜다(concurrency-spec 7.6)""")
                .isZero();

        assertThat(oversoldHolds)
                .as("초과 홀드(V-2)는 0이어야 한다")
                .isZero();

        assertThat(heldRows)
                .as("좌석 1석에 활성 홀드는 정확히 1건이어야 한다")
                .isEqualTo(1);

        assertThat(succeeded.get())
                .as("좌석을 잡았다고 믿는 스레드는 정확히 하나여야 한다")
                .isEqualTo(1);

        assertThat(succeeded.get() + rejected.get() + lockGiveup.get())
                .as("모든 스레드가 성공·정상거절·락포기 중 하나로 끝나야 한다")
                .isEqualTo(THREADS);
    }

    @Test
    @DisplayName("동시 확정 — 성공은 1건, 초과 확정(V-1)은 0이다")
    void concurrentConfirmsProduceNoOverConfirmation() throws Exception {
        // 홀드는 정상 경로로 하나만 만든다. 이 전략에서는 애초에 같은 좌석에
        // 홀드가 둘 생길 수 없으므로(위 테스트), 확정 단계에서 재현할 수 있는
        // 경합은 "같은 홀드를 동시에 확정" — 재시도·더블클릭 상황이다.
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

        // V-1과 같은 쿼리 — load-test/sql/verify.sql.
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

        String seatStatus = jdbc.queryForObject(
                "SELECT status FROM seat_inventory WHERE session_id = ? AND seat_id = ?",
                String.class, SESSION_ID, SEAT_ID);

        System.out.printf(
                "[pessimistic 확정] 스레드 %d · 확정성공 %d · 정상거절 %d · 예외 %d "
                        + "→ 확정된 예약 %d건, 재고 %s, 초과 확정(V-1) %d건%n",
                THREADS, confirmed.get(), rejected.get(), failed.get(),
                confirmedReservations, seatStatus, oversoldSeats);

        assertThat(failed.get())
                .as("예기치 못한 예외는 없어야 한다")
                .isZero();

        assertThat(oversoldSeats)
                .as("""
                        초과 확정(V-1)은 0이어야 한다 — 검수 기준(REQ-01, 국립 SFR-001). \
                        0이 아니면 이 전략이 markSoldUnconditionally를 쓰고 있는지 확인한다. \
                        그 메서드는 none 전용이며, 확정은 confirmIfStillHeld여야 한다""")
                .isZero();

        assertThat(confirmed.get())
                .as("확정에 성공한 스레드는 정확히 하나여야 한다")
                .isEqualTo(1);

        assertThat(confirmedReservations)
                .as("확정된 예약은 1건이어야 한다")
                .isEqualTo(1);

        assertThat(seatStatus).isEqualTo("SOLD");
    }

    @Test
    @DisplayName("confirmIfStillHeld는 동시 호출에서 정확히 한 번만 rowsAffected=1을 낸다")
    void confirmIfStillHeldIsAtomic() throws Exception {
        // 앞 테스트는 서비스의 사전 검사(HOLD_ALREADY_CONFIRMED)가 일부를
        // 걸러낼 수 있어, 확정 쿼리 자체의 원자성을 단독으로 증명하지는 못한다.
        // 여기서는 사전 검사를 건너뛰고 저장소 메서드를 직접 동시에 호출한다.
        String holdId = UUID.randomUUID().toString();
        seatHoldService.hold(SESSION_ID, 1L, List.of(SEAT_ID), holdId);

        TransactionTemplate tx = new TransactionTemplate(txManager);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(THREADS);
        AtomicInteger won = new AtomicInteger();
        AtomicInteger lost = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    Integer rows = tx.execute(status ->
                            seatInventoryRepository.confirmIfStillHeld(SESSION_ID, SEAT_ID, holdId));
                    if (rows != null && rows == 1) {
                        won.incrementAndGet();
                    } else {
                        lost.incrementAndGet();
                    }
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

        System.out.printf("[pessimistic 확정쿼리] 스레드 %d · rowsAffected=1 %d건 · 0 %d건 · 예외 %d%n",
                THREADS, won.get(), lost.get(), failed.get());

        assertThat(failed.get()).as("예기치 못한 예외는 없어야 한다").isZero();

        assertThat(won.get())
                .as("""
                        confirmIfStillHeld는 정확히 하나에만 1을 돌려줘야 한다. 둘 이상이 1을 \
                        받으면 같은 좌석이 여러 예약에 팔린다 — 조건(status·hold_id·held_until)이 \
                        UPDATE와 같은 문장 안에 있는지 확인한다(concurrency-spec 3절)""")
                .isEqualTo(1);

        assertThat(lost.get()).isEqualTo(THREADS - 1);
    }
}
