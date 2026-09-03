package com.inhalab.holdfast.reservation;

import com.inhalab.holdfast.api.ApiException;
import com.inhalab.holdfast.api.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
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
 * {@code optimistic} 전략이 <b>충돌을 재시도로 흡수하고 초과를 막는지</b>
 * 확인한다. concurrency-spec.md 4.3·7.7.2·8절.
 *
 * <p><b>7.7.2가 이 전략에서 보라고 한 것은 p95가 아니다.</b> 여기서 검증하는
 * 것은 넷이다.
 *
 * <ol>
 *   <li>재시도가 실제로 발생하는가 — 이 전략 고유 비용이다</li>
 *   <li>{@code RETRY_EXHAUSTED}가 락 포기로 분류되는가 (정상 거절이 아니다)</li>
 *   <li>제약 위반이 0인가 — 앱 레벨 방어가 작동했는가</li>
 *   <li>실패 모드 — 재시도 상한이 낮으면 무엇이 드러나는가</li>
 * </ol>
 *
 * <h2>U-2를 지우지 않는다</h2>
 *
 * <p>{@code none} 테스트의 {@code seed()}는 {@code ux_seat_hold_active}를
 * 지운다(erd.md 3.1). 그 코드를 복사해 오면 <b>이 테스트가 의미를 잃는다.</b>
 * 제약 위반 횟수는 "앱 락이 샜다"만 세는 지표라(7.1·7.6), 인덱스가 없으면 앱
 * 방어가 새더라도 아무도 막지 않아 카운터가 영원히 0으로 남는다. 그러면
 * <b>"재시도가 잘 막았다"와 "방어선이 통째로 없다"를 구분할 수 없다.</b>
 *
 * <p>H2가 아니라 실제 Postgres를 쓴다 — 조건부 UPDATE의 {@code rowsAffected}
 * 의미론과 동시 쓰기 직렬화가 달라 아무것도 보장하지 않기 때문이다(8절).
 */
@SpringBootTest(properties = {
        "holdfast.strategy=optimistic",
        // 7.3 고정 변수를 그대로 쓴다. none 테스트는 경합 재현을 위해 풀을
        // 스레드 수보다 넉넉히(40) 잡지만, 측정 조건과 같은 30으로 둔다.
        // 알림 Outbox 워커를 끈다(이슈 #78). 배경에서 1초마다 도는 워커가
        // 확정으로 생긴 outbox 행을 집고 있으면 seed()의 TRUNCATE가 그 락을
        // 기다린다. 이 테스트가 보는 것은 좌석 경합이지 알림이 아니다.
        "holdfast.outbox.scheduler.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=30",
        // 4.3의 시작값 그대로. 실패 모드 테스트만 이 값을 따로 낮춘다.
        "holdfast.optimistic.max-retries=3",
        "holdfast.optimistic.backoff-base-ms=2"
})
@Testcontainers
@DisplayName("optimistic: 충돌을 재시도로 흡수하고 초과 홀드·초과 확정을 막는다")
class OptimisticSeatHoldStrategyConcurrencyTest {

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
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        // **U-2를 지우지 않는다.** V1__init_schema.sql이 만든 ux_seat_hold_active를
        // 그대로 둔다 — 이 전략의 실제 측정 환경과 같다(seed.sh는 none일 때만
        // u2-drop.sql을 실행한다). 클래스 주석에 이유를 적었다.
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

        for (int i = 1; i <= THREADS; i++) {
            jdbc.update("INSERT INTO user_session_quota (session_id, user_id, held_count) VALUES (?, ?, 0)",
                    SESSION_ID, (long) i);
        }
    }

    private double counter(String name) {
        var c = meterRegistry.find(name).counter();
        return c == null ? 0.0 : c.count();
    }

    private double constraintViolations() {
        return meterRegistry.find("holdfast.constraint.violations").counters()
                .stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    @Test
    @DisplayName("U-2 인덱스가 걸려 있다 — 지워졌다면 제약 위반 검증이 무의미해진다")
    void uniqueIndexIsInPlace() {
        Integer indexes = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'ux_seat_hold_active'", Integer.class);

        assertThat(indexes)
                .as("""
                        ux_seat_hold_active(U-2)가 있어야 한다. none 테스트의 seed()를 복사하면서 \
                        DROP INDEX를 함께 가져오면 여기서 걸린다 — 제약이 없으면 제약 위반 \
                        카운터가 영원히 0이라 "재시도가 막았다"와 "방어선이 없다"를 구분할 수 \
                        없다(erd.md 3.1, concurrency-spec 7.6)""")
                .isEqualTo(1);

        assertThat(strategy)
                .as("holdfast.strategy=optimistic이면 optimistic 구현체가 주입돼야 한다")
                .isInstanceOf(OptimisticSeatHoldStrategy.class);
    }

    @Test
    @DisplayName("30스레드가 좌석 1석을 동시에 홀드해도 HELD 홀드는 1건뿐이다 — 초과 홀드 0")
    void concurrentHoldsProduceExactlyOneHold() throws Exception {
        double retriesBefore = counter("holdfast.optimistic.retries");
        double exhaustedBefore = counter("holdfast.optimistic.retry-exhausted");
        double violationsBefore = constraintViolations();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(THREADS);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger retryExhausted = new AtomicInteger();
        AtomicInteger constraintViolation = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        for (int i = 1; i <= THREADS; i++) {
            long userId = i;
            pool.submit(() -> {
                try {
                    // 모든 스레드를 같은 순간에 풀어 조회-UPDATE 구간을 겹치게 한다.
                    startGate.await();
                    seatHoldService.hold(SESSION_ID, userId, List.of(SEAT_ID), UUID.randomUUID().toString());
                    succeeded.incrementAndGet();
                } catch (ApiException e) {
                    // 락 포기는 정상 거절과 별도로 센다(7.6.1). 좌석이 남아
                    // 있었을 수도 있는데 재시도를 소진한 것이라 성격이 다르다.
                    if (e.getCode() == ErrorCode.RETRY_EXHAUSTED) {
                        retryExhausted.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                } catch (DataIntegrityViolationException e) {
                    // U-2가 발동했다 = version 조건부 UPDATE가 막지 못한 요청이
                    // INSERT까지 갔다.
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

        // V-2와 같은 쿼리.
        Integer oversoldHolds = jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT session_id, seat_id FROM seat_hold
                     WHERE status = 'HELD'
                     GROUP BY session_id, seat_id
                    HAVING count(*) > 1
                ) t
                """, Integer.class);

        double retries = counter("holdfast.optimistic.retries") - retriesBefore;
        double exhausted = counter("holdfast.optimistic.retry-exhausted") - exhaustedBefore;
        double violations = constraintViolations() - violationsBefore;

        System.out.printf(
                "[optimistic 홀드] 스레드 %d · 성공 %d · 정상거절 %d · 재시도소진 %d · 제약위반 %d · 예외 %d "
                        + "→ HELD 홀드 %d건, 초과 홀드(V-2) %d건, 재시도 %.0f회, 소진 %.0f건, 제약위반 메트릭 %.0f%n",
                THREADS, succeeded.get(), rejected.get(), retryExhausted.get(),
                constraintViolation.get(), failed.get(), heldRows, oversoldHolds,
                retries, exhausted, violations);

        assertThat(failed.get()).as("예기치 못한 예외는 없어야 한다").isZero();

        assertThat(constraintViolation.get())
                .as("""
                        U-2 제약 위반은 0이어야 한다. 0이 아니면 version 조건부 UPDATE가 막지 \
                        못한 요청이 INSERT까지 도달했다는 뜻이다 — 앱 락이 샜다(7.6)""")
                .isZero();
        assertThat(violations)
                .as("앱 메트릭 holdfast.constraint.violations도 0이어야 한다")
                .isZero();

        assertThat(oversoldHolds).as("초과 홀드(V-2)는 0이어야 한다").isZero();
        assertThat(heldRows).as("좌석 1석에 활성 홀드는 정확히 1건이어야 한다").isEqualTo(1);
        assertThat(succeeded.get()).as("좌석을 잡았다고 믿는 스레드는 정확히 하나여야 한다").isEqualTo(1);

        assertThat(succeeded.get() + rejected.get() + retryExhausted.get())
                .as("모든 스레드가 성공·정상거절·재시도소진 중 하나로 끝나야 한다")
                .isEqualTo(THREADS);

        // 7.7.2 — 이 전략 고유 비용이 실제로 발생하는지. 좌석 1석에 30스레드가
        // 동시에 달려들면 version 충돌이 나지 않을 수 없다.
        //
        // **재시도와 소진은 단위가 다르다**(7.6.1). 재시도는 누적 횟수이고
        // 소진은 포기한 요청 수다. 더하거나 환산하지 않는다.
        assertThat(retries)
                .as("""
                        재시도가 발생해야 한다. 0이라면 충돌이 감지되지 않았다는 뜻이므로 \
                        takeHeldIfVersionMatches의 WHERE에 version 비교가 있는지 확인한다(4.3)""")
                .isPositive();
        assertThat(exhausted)
                .as("소진 메트릭과 RETRY_EXHAUSTED 응답 수가 일치해야 한다")
                .isEqualTo(retryExhausted.get());
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
                "[optimistic 확정] 스레드 %d · 확정성공 %d · 정상거절 %d · 예외 %d "
                        + "→ 확정된 예약 %d건, 초과 확정(V-1) %d건%n",
                THREADS, confirmed.get(), rejected.get(), failed.get(),
                confirmedReservations, oversoldSeats);

        assertThat(failed.get()).as("예기치 못한 예외는 없어야 한다").isZero();
        assertThat(oversoldSeats)
                .as("""
                        초과 확정(V-1)은 0이어야 한다 — 검수 기준(REQ-01). 0이 아니면 이 전략이 \
                        markSoldUnconditionally를 쓰고 있는지 확인한다. 그 메서드는 none 전용이며 \
                        확정은 confirmIfStillHeld여야 한다""")
                .isZero();
        assertThat(confirmed.get()).as("확정에 성공한 스레드는 정확히 하나여야 한다").isEqualTo(1);
        assertThat(confirmedReservations).isEqualTo(1);
    }

    /**
     * 만료된 홀드를 남겨 둔다. 재고 행과 {@code seat_hold} 행 모두 만료 상태로
     * 두어 실제 TTL 경과 상황과 같게 만든다 — 두 {@code held_until}은 홀드
     * 트랜잭션의 같은 {@code now()}에서 계산되므로 실제로도 함께 만료된다.
     */
    private void seedExpiredHold(String staleHoldId) {
        jdbc.update("""
                INSERT INTO seat_hold (session_id, seat_id, hold_id, user_id, held_until, status)
                VALUES (?, ?, ?, ?, now() - interval '1 minute', 'HELD')
                """, SESSION_ID, SEAT_ID, staleHoldId, 99L);
        jdbc.update("""
                UPDATE seat_inventory
                   SET status = 'HELD', hold_id = ?, held_until = now() - interval '1 minute',
                       version = version + 1
                 WHERE session_id = ? AND seat_id = ?
                """, staleHoldId, SESSION_ID, SEAT_ID);
    }

    @Test
    @DisplayName("만료된 홀드가 있으면 넘겨받는다 — 재고 인수와 홀드 정리가 함께 일어난다")
    void takesOverExpiredHold() {
        String staleHoldId = UUID.randomUUID().toString();
        seedExpiredHold(staleHoldId);
        double violationsBefore = constraintViolations();

        String newHoldId = UUID.randomUUID().toString();
        seatHoldService.hold(SESSION_ID, 1L, List.of(SEAT_ID), newHoldId);

        String staleStatus = jdbc.queryForObject(
                "SELECT status FROM seat_hold WHERE hold_id = ?", String.class, staleHoldId);
        Integer activeHolds = jdbc.queryForObject("""
                SELECT count(*) FROM seat_hold
                 WHERE session_id = ? AND seat_id = ? AND status = 'HELD'
                """, Integer.class, SESSION_ID, SEAT_ID);
        String inventoryHoldId = jdbc.queryForObject(
                "SELECT hold_id FROM seat_inventory WHERE session_id = ? AND seat_id = ?",
                String.class, SESSION_ID, SEAT_ID);

        System.out.printf("[optimistic 만료인수] 옛 홀드 %s · 활성 홀드 %d건 · 재고 hold_id 일치 %s · 제약위반 %.0f%n",
                staleStatus, activeHolds, newHoldId.equals(inventoryHoldId), constraintViolations() - violationsBefore);

        // 정리가 없으면 여기서 U-2가 INSERT를 막는다. 만료됐어도 status='HELD'면
        // 부분 인덱스가 자리를 차지하고 있기 때문이다.
        assertThat(staleStatus)
                .as("만료된 홀드는 RELEASED로 정리돼야 한다 — 안 그러면 U-2가 새 INSERT를 막는다")
                .isEqualTo("RELEASED");
        assertThat(activeHolds).as("활성 홀드는 새 것 하나뿐이어야 한다").isEqualTo(1);
        assertThat(inventoryHoldId).as("재고 행도 새 홀드를 가리켜야 한다").isEqualTo(newHoldId);
        assertThat(constraintViolations() - violationsBefore)
                .as("제약 위반 없이 넘겨받아야 한다")
                .isZero();
    }

    @Test
    @DisplayName("만료 홀드를 여러 스레드가 동시에 넘겨받으려 해도 하나만 성공한다")
    void concurrentTakeoverOfExpiredHoldLetsOnlyOneWin() throws Exception {
        String staleHoldId = UUID.randomUUID().toString();
        seedExpiredHold(staleHoldId);
        double violationsBefore = constraintViolations();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(THREADS);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger retryExhausted = new AtomicInteger();
        AtomicInteger constraintViolation = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        for (int i = 1; i <= THREADS; i++) {
            long userId = i;
            pool.submit(() -> {
                try {
                    startGate.await();
                    seatHoldService.hold(SESSION_ID, userId, List.of(SEAT_ID), UUID.randomUUID().toString());
                    succeeded.incrementAndGet();
                } catch (ApiException e) {
                    if (e.getCode() == ErrorCode.RETRY_EXHAUSTED) {
                        retryExhausted.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                } catch (DataIntegrityViolationException e) {
                    constraintViolation.incrementAndGet();
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

        Integer activeHolds = jdbc.queryForObject("""
                SELECT count(*) FROM seat_hold
                 WHERE session_id = ? AND seat_id = ? AND status = 'HELD'
                """, Integer.class, SESSION_ID, SEAT_ID);

        System.out.printf(
                "[optimistic 만료인수 경합] 스레드 %d · 성공 %d · 정상거절 %d · 재시도소진 %d · 제약위반 %d · 예외 %d "
                        + "→ 활성 홀드 %d건, 제약위반 메트릭 %.0f%n",
                THREADS, succeeded.get(), rejected.get(), retryExhausted.get(),
                constraintViolation.get(), failed.get(), activeHolds,
                constraintViolations() - violationsBefore);

        assertThat(failed.get()).as("예기치 못한 예외는 없어야 한다").isZero();

        // 재고 인수(version 조건부 UPDATE)가 배타성을 확보하므로, 정리와 INSERT는
        // 항상 단독으로 실행된다. U-2가 발동하면 그 전제가 깨진 것이다.
        assertThat(constraintViolation.get())
                .as("U-2 제약 위반은 0이어야 한다 — version 조건부 UPDATE가 배타성을 확보한다")
                .isZero();
        assertThat(constraintViolations() - violationsBefore)
                .as("앱 메트릭도 0이어야 한다")
                .isZero();

        assertThat(succeeded.get())
                .as("만료 좌석을 넘겨받는 데 성공한 스레드는 정확히 하나여야 한다")
                .isEqualTo(1);
        assertThat(activeHolds).as("활성 홀드는 1건이어야 한다").isEqualTo(1);

        Integer staleStillHeld = jdbc.queryForObject(
                "SELECT count(*) FROM seat_hold WHERE hold_id = ? AND status = 'HELD'",
                Integer.class, staleHoldId);
        assertThat(staleStillHeld).as("옛 만료 홀드는 남아 있으면 안 된다").isZero();
    }

    /**
     * 7.7.2의 "실패 모드 — 재시도 상한이 낮으면 무엇이 드러나는가".
     *
     * <p>상한을 0으로 낮추면 <b>충돌한 요청이 한 번도 다시 시도하지 않고</b>
     * 즉시 포기한다. 그때 드러나야 하는 것은 두 가지다.
     *
     * <ul>
     *   <li>실패가 <b>정상 거절이 아니라 락 포기</b>로 분류된다 —
     *       좌석이 남아 있었을 수도 있는데 포기한 것이기 때문이다(7.6.1)</li>
     *   <li>그럼에도 <b>초과 홀드는 여전히 0</b>이다 — 재시도는 성공률을 높이는
     *       장치이지 정합성을 지키는 장치가 아니다. 정합성은 version 조건부
     *       UPDATE가 지킨다</li>
     * </ul>
     *
     * <p>이 구분이 중요한 이유는 상한을 조정할 때(4.3 "시작값") 무엇이 나빠지고
     * 무엇이 그대로인지 알아야 하기 때문이다. 상한을 낮추면 사용자가 더 자주
     * 실패하지만 좌석이 두 번 팔리지는 않는다.
     */
    @Test
    @DisplayName("실패 모드 — 재시도 상한 0이면 포기가 늘지만 정합성은 그대로다")
    void withoutRetriesFailuresBecomeLockGiveupButCorrectnessHolds() throws Exception {
        // 프로퍼티가 아니라 필드를 직접 낮춘다. 상한만 다른 컨텍스트를 하나 더
        // 띄우면 Testcontainers가 한 벌 더 뜨고 실행이 배로 길어진다.
        var field = OptimisticSeatHoldStrategy.class.getDeclaredField("maxRetries");
        field.setAccessible(true);
        int original = (int) field.get(strategy);
        field.set(strategy, 0);
        try {
            ExecutorService pool = Executors.newFixedThreadPool(THREADS);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(THREADS);
            AtomicInteger succeeded = new AtomicInteger();
            AtomicInteger normalRejection = new AtomicInteger();
            AtomicInteger lockGiveup = new AtomicInteger();
            AtomicInteger failed = new AtomicInteger();

            for (int i = 1; i <= THREADS; i++) {
                long userId = i;
                pool.submit(() -> {
                    try {
                        startGate.await();
                        seatHoldService.hold(SESSION_ID, userId, List.of(SEAT_ID), UUID.randomUUID().toString());
                        succeeded.incrementAndGet();
                    } catch (ApiException e) {
                        if (e.getCode() == ErrorCode.RETRY_EXHAUSTED) {
                            lockGiveup.incrementAndGet();
                        } else {
                            normalRejection.incrementAndGet();
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

            Integer heldRows = jdbc.queryForObject("""
                    SELECT count(*) FROM seat_hold
                     WHERE session_id = ? AND seat_id = ? AND status = 'HELD'
                    """, Integer.class, SESSION_ID, SEAT_ID);

            System.out.printf(
                    "[optimistic 상한0] 스레드 %d · 성공 %d · 정상거절 %d · 락포기(RETRY_EXHAUSTED) %d · 예외 %d "
                            + "→ HELD 홀드 %d건%n",
                    THREADS, succeeded.get(), normalRejection.get(), lockGiveup.get(), failed.get(), heldRows);

            assertThat(failed.get()).as("예기치 못한 예외는 없어야 한다").isZero();

            // 정합성은 재시도와 무관하다. 이것이 이 테스트의 핵심이다.
            assertThat(heldRows)
                    .as("재시도를 없애도 좌석 1석에 활성 홀드는 1건이어야 한다 — 정합성은 version 조건부 UPDATE가 지킨다")
                    .isEqualTo(1);
            assertThat(succeeded.get()).isEqualTo(1);

            assertThat(ErrorCode.RETRY_EXHAUSTED.category())
                    .as("RETRY_EXHAUSTED는 정상 거절이 아니라 락 포기로 분류돼야 한다(7.6.1)")
                    .isEqualTo(ErrorCode.LOCK_TIMEOUT.category());
        } finally {
            field.set(strategy, original);
        }
    }
}
