package com.inhalab.holdfast.reservation;

import com.inhalab.holdfast.api.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
 * {@code none} 베이스라인이 <b>실제로 깨지는지</b> 확인한다.
 * concurrency-spec.md 4.1·8절.
 *
 * <p><b>이 테스트는 초과 홀드가 발생해야 통과한다.</b> 보통의 테스트와 방향이
 * 반대다. 2개월차 산출물이 "락 없이 N명이 동시에 요청하면 초과 예약 M건"이라는
 * 실패 데이터이고, 이 테스트가 그 M이 0이 아님을 보증한다. 여기서 초과가 나오지
 * 않으면 어딘가에 의도치 않은 방어가 남아 있다는 뜻이다.
 *
 * <p>H2가 아니라 실제 Postgres를 쓴다 — {@code FOR UPDATE} 의미론이 달라
 * 동시성 테스트가 통과해도 아무것도 보장하지 않기 때문이다(8절).
 */
@SpringBootTest(properties = {
        "holdfast.strategy=none",
        // 스레드가 커넥션을 기다리며 줄 서면 동시에 조회하는 구간이 줄어든다.
        // 경합을 재현하려는 테스트이므로 풀을 스레드 수보다 넉넉히 잡는다.
        "spring.datasource.hikari.maximum-pool-size=40"
})
@Testcontainers
@DisplayName("none 베이스라인: 락이 없어 초과 홀드가 발생한다")
class NoneSeatHoldStrategyConcurrencyTest {

    private static final long SESSION_ID = 1L;
    private static final long SEAT_ID = 1L;
    private static final int THREADS = 30;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    // Redis 자체는 none 전략이 쓰지 않지만, Redisson 자동설정이 기동 시 접속을
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
        // U-2를 지운다. none 베이스라인의 실제 측정 환경과 같게 만들기 위해서다 —
        // load-test/scripts/seed.sh가 none일 때 u2-drop.sql을 실행하는 것과 동일하다
        // (erd.md 3.1). 이 인덱스가 남아 있으면 두 번째 HELD INSERT가 제약 위반으로
        // 막혀 초과 홀드가 관측되지 않는다. 그러면 "락이 없어도 안전하다"는 잘못된
        // 결론이 나온다.
        jdbc.execute("DROP INDEX IF EXISTS ux_seat_hold_active");

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

        // 좌석은 한 자리뿐이다. 정상이라면 한 명만 잡아야 한다.
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
    @DisplayName("확정까지 가면 같은 좌석이 여러 예약에 확정된다 — 초과 확정(V-1)")
    void concurrentConfirmsOnSingleSeatProduceExcessConfirmations() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(THREADS);
        AtomicInteger heldOk = new AtomicInteger();
        AtomicInteger confirmedOk = new AtomicInteger();
        AtomicInteger phantom = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        for (int i = 1; i <= THREADS; i++) {
            long userId = i;
            pool.submit(() -> {
                String holdId = UUID.randomUUID().toString();
                try {
                    startGate.await();
                    seatHoldService.hold(SESSION_ID, userId, List.of(SEAT_ID), holdId);
                    heldOk.incrementAndGet();
                } catch (ApiException e) {
                    finished.countDown();
                    return; // 홀드 단계에서 거절됨. 팬텀 홀드의 분모에 들어가지 않는다.
                } catch (Exception e) {
                    failed.incrementAndGet();
                    finished.countDown();
                    return;
                }

                // 홀드에 성공한 요청만 확정을 시도한다 — 팬텀 홀드율(7.6.3)의 분모다.
                try {
                    reservationService.confirm(holdId, userId);
                    confirmedOk.incrementAndGet();
                } catch (ApiException e) {
                    // "좌석을 잡았다"는 응답을 받고도 확정에서 잃었다 = 팬텀 홀드.
                    phantom.incrementAndGet();
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

        // V-1: 같은 재고 행이 두 건 이상의 확정된 예약에 팔렸는가.
        // load-test/sql/verify.sql의 V-1과 같은 쿼리다.
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
                "[none 확정] 홀드성공 %d · 확정성공 %d · 팬텀홀드 %d · 예외 %d "
                        + "→ 확정된 예약 %d건, 초과 확정(V-1) %d건%n",
                heldOk.get(), confirmedOk.get(), phantom.get(), failed.get(),
                confirmedReservations, oversoldSeats);

        assertThat(failed.get())
                .as("예기치 못한 예외는 없어야 한다. 있으면 실패 증거가 아니라 결함이다")
                .isZero();

        // 이 테스트의 핵심. 좌석은 하나인데 확정된 예약이 둘 이상이면 초과 확정이다.
        assertThat(oversoldSeats)
                .as("""
                        초과 확정(V-1)이 발생해야 한다. 0이라면 확정 경로에 방어가 남아 있다는                         뜻이다 — none이 원자적 조건부 UPDATE(confirmIfStillHeld)를 쓰고 있거나,                         재고의 hold_id를 대조하고 있는지 확인한다                        (state-transitions.md 2.1, NoneSeatHoldStrategy#confirmSeat)""")
                .isPositive();

        assertThat(confirmedOk.get())
                .as("두 명 이상이 같은 좌석을 확정하는 데 성공해야 한다")
                .isGreaterThan(1);
    }

    @Test
    @DisplayName("30스레드가 좌석 1석을 동시에 홀드하면 HELD 홀드 행이 2건 이상 남는다")
    void concurrentHoldsOnSingleSeatProduceExcessHolds() throws Exception {
        assertThat(strategy)
                .as("holdfast.strategy=none이면 none 구현체가 주입돼야 한다")
                .isInstanceOf(NoneSeatHoldStrategy.class);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(THREADS);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        for (int i = 1; i <= THREADS; i++) {
            long userId = i;
            pool.submit(() -> {
                try {
                    // 모든 스레드를 같은 순간에 풀어 조회 구간을 겹치게 한다.
                    startGate.await();
                    seatHoldService.hold(SESSION_ID, userId, List.of(SEAT_ID), UUID.randomUUID().toString());
                    succeeded.incrementAndGet();
                } catch (ApiException e) {
                    // 조회 시점에 이미 HELD를 본 스레드. 정상 거절이다.
                    rejected.incrementAndGet();
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

        System.out.printf(
                "[none 베이스라인] 스레드 %d · 성공 %d · 정상거절 %d · 예외 %d → 좌석 1석에 HELD 홀드 %d건%n",
                THREADS, succeeded.get(), rejected.get(), failed.get(), heldRows);

        assertThat(failed.get())
                .as("예기치 못한 예외는 없어야 한다. 있으면 실패 증거가 아니라 결함이다")
                .isZero();

        // 이 테스트의 핵심. 좌석은 하나인데 홀드가 둘 이상 남았다면 초과 홀드다.
        assertThat(heldRows)
                .as("""
                        초과 홀드가 발생해야 한다. 1건뿐이라면 어딘가에 방어가 남아 있다는 뜻이다 \
                        — U-2 인덱스가 지워지지 않았거나, 전략에 락·조건부 UPDATE·만료 정리가 \
                        섞여 들어갔는지 확인한다(concurrency-spec 4.1, erd.md 4.1)""")
                .isGreaterThan(1);

        assertThat(succeeded.get())
                .as("두 스레드 이상이 '내가 좌석을 잡았다'고 믿어야 한다")
                .isGreaterThan(1);
    }
}
