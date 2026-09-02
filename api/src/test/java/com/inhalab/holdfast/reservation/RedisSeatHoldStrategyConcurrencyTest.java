package com.inhalab.holdfast.reservation;

import com.inhalab.holdfast.api.ApiException;
import com.inhalab.holdfast.api.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code redis} 전략. concurrency-spec.md 4.5·4.5.1·5.2·7.5.1·8절.
 *
 * <p>7.7.2가 이 전략에서 보라고 한 넷을 그대로 옮겼다.
 *
 * <ol>
 *   <li><b>V-1·V-2가 0인가</b></li>
 *   <li><b>제약 위반이 0인가</b> — 0이 아니면 분산락이 샜다는 신호다</li>
 *   <li><b>락 해제가 커밋 이후인가</b> — 5.2가 "단위 테스트로는 잡히지 않는다"고
 *       한 실수를 단위 테스트로 잡는다</li>
 *   <li><b>실패 모드</b> — {@code leaseTime}이 트랜잭션보다 짧으면 무슨 일이
 *       생기는가. 분산락의 대표적 실패 모드이므로 의도적으로 재현한다</li>
 * </ol>
 *
 * <h2>4번이 이 전략의 핵심이다</h2>
 *
 * <p>4.5.1은 <b>"분산락은 성능 최적화이지 정합성 보장이 아니다"</b>를 측정으로
 * 증명할 명제로 세웠다. 정상 조건에서는 락이 새지 않아 그 명제가 드러나지
 * 않는다 — 그래서 리스를 아주 짧게 만들어 <b>새는 조건을 인위적으로 재현하고</b>,
 * 그때 U-2가 받아내는지 확인한다.
 *
 * <p>부하 측정에서 제약 위반이 0으로 나와도 <b>반증이 아니다</b>(7.5.1). 락이
 * 새는 조건이 120초 실행에서 재현된다는 보장이 없다. 이 테스트는 그 조건을
 * 만들어 "샐 수 있고, 새면 제약이 받아낸다"를 보인다.
 *
 * <h2>U-2를 지우지 않는다</h2>
 *
 * <p>{@code none} 테스트의 {@code seed()}를 복사할 때 {@code DROP INDEX}를
 * 가져오면 안 된다. 이 전략에서 U-2는 <b>계층 방어의 아래층</b>이고(4.5.1),
 * 없으면 4번 테스트가 검증하려는 것 자체가 사라진다.
 */
@SpringBootTest(properties = {
        "holdfast.strategy=redis",
        // 7.3 고정 변수를 그대로 쓴다.
        "spring.datasource.hikari.maximum-pool-size=30",
        "holdfast.redis.wait-time-ms=1000",
        "holdfast.redis.lease-time-ms=10000"
})
@Testcontainers
@DisplayName("redis: 분산락으로 막되, 정합성의 최종 책임은 DB 제약이 진다")
class RedisSeatHoldStrategyConcurrencyTest {

    private static final long SESSION_ID = 1L;
    private static final long SEAT_ID = 1L;
    private static final int THREADS = 30;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    // 이 전략은 Redis를 실제로 쓴다. 다른 전략 테스트에서는 컨텍스트 기동용이지만
    // 여기서는 측정 대상 그 자체다.
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
    private RedissonClient redisson;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        // **U-2를 지우지 않는다.** 계층 방어의 아래층이다(4.5.1).
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

        // 인덱스 존재를 매 테스트마다 보장한다. TRUNCATE가 seat_hold를 비우므로
        // 항상 성공한다 — 순서 의존을 만들지 않기 위한 것이다.
        jdbc.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS ux_seat_hold_active
                  ON seat_hold (session_id, seat_id) WHERE status = 'HELD'
                """);

        // 앞 테스트가 남긴 락이 있으면 다음 테스트가 오염된다.
        redisson.getLock(RedisSeatHoldStrategy.lockKey(SESSION_ID, SEAT_ID)).forceUnlock();
    }

    private static final class Outcome {
        final AtomicInteger succeeded = new AtomicInteger();
        final AtomicInteger rejected = new AtomicInteger();
        final AtomicInteger lockGiveup = new AtomicInteger();
        final AtomicInteger constraintViolation = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
    }

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
                    // **분산락이 샜고 U-2가 받아냈다**(4.5.1).
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
        assertThat(finished.await(120, TimeUnit.SECONDS)).as("모든 스레드가 끝나야 한다").isTrue();
        pool.shutdown();
        return o;
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
    @DisplayName("U-2 인덱스가 걸려 있다 — 계층 방어의 아래층이다")
    void uniqueIndexIsInPlace() {
        Integer indexes = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'ux_seat_hold_active'", Integer.class);
        assertThat(indexes)
                .as("""
                        ux_seat_hold_active(U-2)가 있어야 한다. 이 전략에서 U-2는 계층 방어의 \
                        아래층이며(4.5.1), 없으면 락이 샐 때 받아낼 것이 없다""")
                .isEqualTo(1);
        assertThat(strategy)
                .as("holdfast.strategy=redis면 redis 구현체가 주입돼야 한다")
                .isInstanceOf(RedisSeatHoldStrategy.class);
    }

    @Test
    @DisplayName("30스레드가 좌석 1석을 동시에 홀드해도 HELD 홀드는 1건뿐이다")
    void concurrentHoldsProduceExactlyOneHold() throws Exception {
        Outcome o = raceForOneSeat();
        int held = activeHolds();

        System.out.printf(
                "[redis 홀드] 스레드 %d · 성공 %d · 정상거절 %d · 락포기 %d · 제약위반 %d · 예외 %d "
                        + "→ HELD 홀드 %d건, 초과 홀드(V-2) %d건%n",
                THREADS, o.succeeded.get(), o.rejected.get(), o.lockGiveup.get(),
                o.constraintViolation.get(), o.failed.get(), held, oversoldHolds());

        assertThat(o.failed.get()).as("예기치 못한 예외는 없어야 한다").isZero();
        assertThat(oversoldHolds()).as("초과 홀드(V-2)는 0이어야 한다").isZero();
        assertThat(held).as("좌석 1석에 활성 홀드는 정확히 1건이어야 한다").isEqualTo(1);
        assertThat(o.succeeded.get()).as("좌석을 잡았다고 믿는 스레드는 정확히 하나여야 한다").isEqualTo(1);

        // 정상 조건에서는 락이 새지 않아야 한다. 0이 아니면 leaseTime이 트랜잭션보다
        // 짧거나 해제가 커밋보다 이르다는 뜻이다.
        assertThat(o.constraintViolation.get())
                .as("""
                        정상 조건에서 제약 위반은 0이어야 한다. 0이 아니면 분산락이 샌 것이고 \
                        (4.5.1) leaseTime이 트랜잭션보다 짧은지, 해제가 커밋보다 이른지(5.2) \
                        확인한다""")
                .isZero();
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
                    SELECT rs.seat_inventory_id FROM reservation_seat rs
                      JOIN reservation r ON r.id = rs.reservation_id
                     WHERE r.status = 'CONFIRMED'
                     GROUP BY rs.seat_inventory_id HAVING count(*) > 1
                ) t
                """, Integer.class);
        Integer confirmedReservations = jdbc.queryForObject(
                "SELECT count(*) FROM reservation WHERE status = 'CONFIRMED'", Integer.class);

        System.out.printf(
                "[redis 확정] 스레드 %d · 확정성공 %d · 정상거절 %d · 예외 %d "
                        + "→ 확정된 예약 %d건, 초과 확정(V-1) %d건%n",
                THREADS, confirmed.get(), rejected.get(), failed.get(),
                confirmedReservations, oversoldSeats);

        assertThat(failed.get()).as("예기치 못한 예외는 없어야 한다").isZero();
        assertThat(oversoldSeats)
                .as("""
                        초과 확정(V-1)은 0이어야 한다. 확정에는 분산락을 잡지 않으므로 이것을 \
                        막는 것은 confirmIfStillHeld의 원자성이다 — 4.5.1이 말한 '정합성의 최종 \
                        책임은 DB에 있다'가 확정 경로에도 그대로 적용된다""")
                .isZero();
        assertThat(confirmed.get()).as("확정에 성공한 스레드는 정확히 하나여야 한다").isEqualTo(1);
        assertThat(confirmedReservations).isEqualTo(1);
    }

    /**
     * 5.2 — <b>락 해제는 커밋 이후여야 한다.</b>
     *
     * <p>5.2는 이 실수가 "단위 테스트로는 잡히지 않고 부하 테스트에서만 드러난다"고
     * 적었다. 트랜잭션 경계를 테스트가 직접 쥐면 잡을 수 있다 —
     * {@code TransactionTemplate} 안에서 홀드를 잡고, <b>커밋 전에</b> 락 상태를
     * 확인한다.
     *
     * <p>다른 스레드의 {@code tryLock}까지 확인하는 이유는
     * {@code isLocked()}만으로는 "내가 쥔 것"과 "누가 쥔 것"을 구분하지 못하기
     * 때문이다. 실제로 막히는지를 봐야 한다.
     */
    @Test
    @DisplayName("락 해제가 커밋 이후다 — 커밋 전에는 다른 스레드가 못 얻는다")
    void lockIsHeldUntilCommit() throws Exception {
        String key = RedisSeatHoldStrategy.lockKey(SESSION_ID, SEAT_ID);
        AtomicBoolean lockedDuringTx = new AtomicBoolean();
        AtomicBoolean otherThreadAcquiredDuringTx = new AtomicBoolean();

        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            seatHoldService.hold(SESSION_ID, 1L, List.of(SEAT_ID), UUID.randomUUID().toString());
            // 아직 커밋 전이다. 락이 살아 있어야 한다.
            lockedDuringTx.set(redisson.getLock(key).isLocked());
            otherThreadAcquiredDuringTx.set(tryLockFromAnotherThread(key));
        });

        boolean lockedAfterCommit = redisson.getLock(key).isLocked();

        System.out.printf("[redis 커밋경계] 커밋 전 잠김 %s · 다른 스레드 획득 %s · 커밋 후 잠김 %s%n",
                lockedDuringTx.get(), otherThreadAcquiredDuringTx.get(), lockedAfterCommit);

        assertThat(lockedDuringTx.get())
                .as("""
                        커밋 전에 락이 살아 있어야 한다. 풀려 있다면 커밋 전에 해제한 것이고, \
                        그러면 다른 요청이 커밋되지 않은 상태를 읽는다 — 락을 걸지 않은 것과 \
                        같다(5.2)""")
                .isTrue();
        assertThat(otherThreadAcquiredDuringTx.get())
                .as("커밋 전에는 다른 스레드가 같은 좌석의 락을 얻지 못해야 한다")
                .isFalse();
        assertThat(lockedAfterCommit)
                .as("""
                        커밋 후에는 락이 풀려야 한다. 남아 있으면 afterCompletion이 등록되지 \
                        않았거나 해제에 실패한 것이고, leaseTime이 끝날 때까지 그 좌석이 막힌다""")
                .isFalse();
    }

    /** 짧은 대기로 다른 스레드에서 락 획득을 시도한다. 얻었으면 즉시 반납한다. */
    private boolean tryLockFromAnotherThread(String key) {
        ExecutorService one = Executors.newSingleThreadExecutor();
        try {
            return one.submit(() -> {
                var lock = redisson.getLock(key);
                boolean got = lock.tryLock(200, 5000, TimeUnit.MILLISECONDS);
                if (got) {
                    lock.unlock();
                }
                return got;
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("다른 스레드 락 시도 실패", e);
        } finally {
            one.shutdown();
        }
    }

    /**
     * 7.7.2 ④ 실패 모드 — <b>{@code leaseTime}이 트랜잭션보다 짧으면 락이 샌다.</b>
     *
     * <p>4.5.1이 측정으로 증명하겠다고 한 명제를 재현한다. 정상 조건에서는 락이
     * 새지 않아 명제가 드러나지 않으므로, 리스를 1ms로 낮춰 <b>획득 직후 만료</b>
     * 시킨다. GC 정지나 네트워크 지연으로 리스가 만료되는 상황과 같은 결과다.
     *
     * <p>그때 드러나야 하는 것은 둘이다.
     *
     * <ul>
     *   <li><b>제약 위반이 발생한다</b> — 여러 요청이 동시에 락을 보유했다고 믿고
     *       INSERT까지 도달했다는 뜻이다. <b>분산락이 샜다.</b></li>
     *   <li><b>그럼에도 초과 홀드는 0이다</b> — U-2가 받아냈다. <b>계층 방어가
     *       작동했고, 정합성의 최종 책임은 락이 아니라 DB에 있다.</b></li>
     * </ul>
     *
     * <p>이 둘이 함께 나오는 것이 "분산락은 성능 최적화이지 정합성 보장이
     * 아니다"의 실증이다.
     */
    @Test
    @DisplayName("실패 모드 — leaseTime이 트랜잭션보다 짧으면 락이 새고, U-2가 받아낸다")
    void whenLeaseExpiresBeforeCommitTheConstraintCatchesIt() throws Exception {
        var field = RedisSeatHoldStrategy.class.getDeclaredField("leaseTimeMs");
        field.setAccessible(true);
        long original = (long) field.get(strategy);
        field.set(strategy, 1L);   // 획득 직후 만료된다
        try {
            Outcome o = raceForOneSeat();
            int held = activeHolds();

            System.out.printf(
                    "[redis 리스만료] 스레드 %d · 성공 %d · 정상거절 %d · 락포기 %d · 제약위반 %d · 예외 %d "
                            + "→ HELD 홀드 %d건, 초과 홀드(V-2) %d건%n",
                    THREADS, o.succeeded.get(), o.rejected.get(), o.lockGiveup.get(),
                    o.constraintViolation.get(), o.failed.get(), held, oversoldHolds());

            assertThat(o.failed.get()).as("예기치 못한 예외는 없어야 한다").isZero();

            // 4.5.1 전반부 — 락이 샌다.
            assertThat(o.constraintViolation.get())
                    .as("""
                            리스가 만료되면 제약 위반이 발생해야 한다. 0이라면 락이 새지 않았다는 \
                            뜻이므로 leaseTime 주입이 실제로 반영됐는지 확인한다 — 이 테스트가 \
                            재현하려는 것이 바로 '분산락이 정합성을 보장하지 못하는 순간'이다""")
                    .isPositive();

            // 4.5.1 후반부 — 그래도 초과는 없다. 계층 방어가 받아낸다.
            assertThat(oversoldHolds())
                    .as("""
                            락이 샜어도 초과 홀드는 0이어야 한다. U-2가 최후 방어선으로 받아낸다 — \
                            이것이 '정합성의 최종 책임은 락이 아니라 제약이 진다'의 실증이다""")
                    .isZero();
            assertThat(held).as("활성 홀드는 여전히 1건이어야 한다").isEqualTo(1);
            assertThat(o.succeeded.get()).as("성공은 여전히 하나여야 한다").isEqualTo(1);
        } finally {
            field.set(strategy, original);
        }
    }
}
