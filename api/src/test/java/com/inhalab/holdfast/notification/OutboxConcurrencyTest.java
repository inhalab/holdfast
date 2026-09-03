package com.inhalab.holdfast.notification;

import com.inhalab.holdfast.api.ApiException;
import com.inhalab.holdfast.reservation.ReservationService;
import com.inhalab.holdfast.reservation.SeatHoldService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 알림 Outbox의 동시성. REQ-05(국립 SFR-003), 이슈 #78.
 *
 * <p>이슈가 요구한 검증 셋과 범위 첫 항목(같은 트랜잭션 INSERT)을 그대로 옮겼다.
 *
 * <ol>
 *   <li><b>워커 둘이 동시에 도는 상태에서 중복 발송 0건</b> — 이 이슈의 핵심</li>
 *   <li>발송 실패 시 재시도가 실제로 일어나는가</li>
 *   <li>재시도 상한 소진 시 어떻게 되는가</li>
 *   <li>확정과 알림 INSERT가 같은 트랜잭션인가 — 롤백으로 확인한다</li>
 * </ol>
 *
 * <h2>왜 실제 Postgres인가</h2>
 *
 * <p>이 테스트가 확인하는 것은 자바 코드가 아니라 <b>{@code FOR UPDATE SKIP
 * LOCKED}와 조건부 UPDATE의 직렬화 의미론</b>이다. H2로는 아무것도 보장하지
 * 않는다(concurrency-spec.md 8절). 좌석 전략 테스트와 같은 이유다.
 *
 * <h2>스케줄러를 끈다</h2>
 *
 * <p>{@code holdfast.outbox.scheduler.enabled=false}. 배경 워커가 함께 돌면
 * 테스트가 만든 행을 그것이 먼저 집어가, 무엇이 무엇을 처리했는지 알 수 없게
 * 된다. 워커 자체는 살아 있고 테스트가 {@link OutboxWorker#pollOnce}를 직접
 * 부른다.
 */
@SpringBootTest(properties = {
        "holdfast.strategy=pessimistic",
        // 워커를 테스트가 직접 몬다 — 클래스 주석 참조.
        "holdfast.outbox.scheduler.enabled=false",
        // 큐(200건)보다 작게 잡아 워커들이 같은 행 뭉치를 두고 겨루게 한다.
        "holdfast.outbox.batch-size=20",
        // 상한 소진 테스트가 3번의 시도로 끝나게 한다(최초 1 + 재시도 2).
        "holdfast.outbox.max-retries=2",
        // 재시도 예약 간격. 실제 값 1초를 쓰면 테스트가 그만큼 기다린다.
        "holdfast.outbox.backoff-base-ms=1",
        "holdfast.outbox.claim-timeout-seconds=30",
        "spring.datasource.hikari.maximum-pool-size=30"
})
@Import(OutboxConcurrencyTest.TestBeans.class)
@Testcontainers
@DisplayName("outbox: 워커 둘이 동시에 돌아도 같은 알림을 두 번 보내지 않는다")
class OutboxConcurrencyTest {

    private static final long SESSION_ID = 1L;
    private static final long SEAT_ID = 1L;
    private static final int QUEUED = 200;
    private static final int WORKERS = 8;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    // Redisson 자동설정이 기동 시 접속을 시도하므로 컨텍스트를 띄우려면 필요하다.
    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private OutboxWorker worker;

    @Autowired
    private RecordingSender sender;

    @Autowired
    private RollbackProbe rollbackProbe;

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

        jdbc.update("INSERT INTO program (id, name, created_at) VALUES (1, '알림 테스트', now())");
        jdbc.update("INSERT INTO seat_layout (id, name, created_at) VALUES (1, '알림 테스트', now())");
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
        jdbc.update("INSERT INTO user_session_quota (session_id, user_id, held_count) VALUES (?, 1, 0)",
                SESSION_ID);

        sender.reset();
    }

    // ─────────────────────────────────────────────────────────────────────
    // 1. 이 이슈의 핵심 — 중복 발송 0건
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("워커 여덟이 동시에 돌아도 알림 200건이 정확히 한 번씩만 발송된다")
    void concurrentWorkersSendEachNotificationExactlyOnce() throws Exception {
        queueNotifications(QUEUED);
        // 발송을 조금 느리게 만들어 클레임과 결과 기록 사이의 창을 넓힌다.
        // 즉시 끝나면 경합이 일어날 틈 자체가 좁아 통과가 우연이 된다.
        sender.delayMillis = 2;

        runWorkersUntilDrained();

        Map<Long, AtomicInteger> counts = sender.sendCounts;
        assertThat(counts).as("200건이 모두 발송됐다").hasSize(QUEUED);
        assertThat(counts.values())
                .as("**중복 발송 0건** — 어느 행도 두 번 보내지지 않았다")
                .allSatisfy(count -> assertThat(count.get()).isEqualTo(1));

        assertThat(countByStatus("SENT")).as("DB에도 200건이 SENT로 남는다").isEqualTo(QUEUED);
        assertThat(countByStatus("PENDING")).isZero();
        assertThat(countByStatus("SENDING")).as("클레임이 남아 있지 않다").isZero();

        // **이 단언이 없으면 위가 공허하다.** 워커 하나만 실제로 돌고 나머지
        // 일곱이 빈손으로 끝나도 "중복 0건"은 그대로 통과한다. 경합이 실제로
        // 있었다는 것을 확인해야 앞의 결과가 증거가 된다.
        assertThat(sender.sendingThreads)
                .as("여러 워커가 실제로 나눠 처리했다 — 경합이 있었다는 증거")
                .hasSizeGreaterThan(1);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 2. 재시도가 실제로 일어나는가
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("발송에 실패하면 PENDING으로 되돌리고 다음 재시도 시각을 예약한다")
    void failedSendSchedulesRetry() {
        queueNotifications(1);
        sender.failAlways = true;

        worker.pollOnce();

        Map<String, Object> row = onlyRow();
        assertThat(row.get("status")).as("클레임을 놓아준다").isEqualTo("PENDING");
        assertThat(row.get("retry_count")).isEqualTo(1);
        assertThat(row.get("claimed_at")).as("SENDING이 아니므로 클레임 시각도 지운다").isNull();
        assertThat(row.get("next_retry_at"))
                .as("다음 시각이 예약된다 — 즉시 다시 때리지 않는다")
                .isNotNull();

        // **곧바로 다시 집히지 않는다.** next_retry_at이 지나야 대상이 된다.
        assertThat(sender.totalAttempts()).isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 3. 상한 소진
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("재시도 상한을 소진하면 FAILED로 남고 워커가 다시 집지 않는다")
    void exhaustedRetriesEndInFailed() throws Exception {
        queueNotifications(1);
        sender.failAlways = true;

        // max-retries=2 → 최초 1회 + 재시도 2회 = 3번 시도하고 포기한다.
        pollUntilStatus("FAILED", 30);

        Map<String, Object> row = onlyRow();
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("retry_count")).as("상한 2를 소진하고 마지막 실패까지 3").isEqualTo(3);
        assertThat(row.get("next_retry_at")).as("다시 예약하지 않는다").isNull();
        assertThat(sender.totalAttempts()).as("세 번 시도했다").isEqualTo(3);

        // **종착이다.** 더 돌려도 집히지 않는다 — 영원히 실패하는 행에 워커가
        // 매달리면 뒤에 쌓인 정상 행의 발송이 밀린다(OutboxStatus.FAILED).
        int before = sender.totalAttempts();
        for (int i = 0; i < 5; i++) {
            worker.pollOnce();
        }
        assertThat(sender.totalAttempts()).isEqualTo(before);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 4. 확정과 같은 트랜잭션인가
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("확정하면 알림이 정확히 한 건 큐에 들어간다")
    void confirmingEnqueuesExactlyOneNotification() {
        String holdId = UUID.randomUUID().toString();
        seatHoldService.hold(SESSION_ID, 1L, List.of(SEAT_ID), holdId);
        reservationService.confirm(holdId, 1L);

        Map<String, Object> row = onlyRow();
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("notification_type"))
                .isEqualTo(NotificationType.RESERVATION_CONFIRMED.name());
        assertThat((String) row.get("payload"))
                .as("페이로드는 확정 시점에 손에 있던 값만 담는다")
                .contains("\"reservationId\"")
                .contains("\"sessionId\":1");
    }

    @Test
    @DisplayName("확정이 롤백되면 알림 행도 함께 사라진다 — 같은 트랜잭션이다")
    void rollingBackConfirmationRemovesTheNotification() {
        String holdId = UUID.randomUUID().toString();
        seatHoldService.hold(SESSION_ID, 1L, List.of(SEAT_ID), holdId);

        assertThatThrownBy(() -> rollbackProbe.confirmThenFail(holdId, 1L))
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class))
                .as("**확정과 함께 되돌아간다.** Outbox 패턴의 전제가 이 원자성이다")
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM reservation WHERE hold_id = ?", String.class, holdId))
                .as("확정 자체도 되돌아갔다 — 둘이 같은 트랜잭션이었다는 뜻이다")
                .isNotEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("같은 홀드를 두 번 확정하려 하면 U-12가 아니라 확정 가드가 먼저 막는다")
    void doubleConfirmIsBlockedBeforeTheUniqueIndex() {
        String holdId = UUID.randomUUID().toString();
        seatHoldService.hold(SESSION_ID, 1L, List.of(SEAT_ID), holdId);
        reservationService.confirm(holdId, 1L);

        // U-12 위반이 아니라 HOLD_ALREADY_CONFIRMED로 막혀야 한다. 제약까지
        // 갔다면 확정 가드가 샌 것이고, 그것을 카운터로 알 수 있어야 한다
        // (OutboxConfirmationNotifier — 제약 위반을 잡지 않는 이유).
        assertThatThrownBy(() -> reservationService.confirm(holdId, 1L))
                .isInstanceOf(ApiException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class)).isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 도구
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 발송 대기 중인 알림 {@code n}건을 만든다.
     *
     * <p>확정을 {@code n}번 반복하지 않고 행을 직접 넣는다. 이 테스트가 보는
     * 것은 워커의 경합이지 확정 경로가 아니고, 좌석이 1석뿐이라 확정으로는
     * 200건을 만들 수도 없다. U-12가 {@code (reservation_id, notification_type)}
     * 이므로 예약도 200건이 필요하다.
     */
    private void queueNotifications(int n) {
        jdbc.update("""
                INSERT INTO reservation (session_id, user_id, hold_id, status, created_at, confirmed_at)
                SELECT ?::bigint, 1, 'seed-' || g, 'CONFIRMED', now(), now()
                  FROM generate_series(1, ?::int) g
                """, SESSION_ID, n);
        jdbc.update("""
                INSERT INTO outbox (reservation_id, notification_type, status, retry_count,
                                    payload, created_at)
                SELECT id, ?::varchar, 'PENDING', 0, '{}', now() FROM reservation
                """, NotificationType.RESERVATION_CONFIRMED.name());
    }

    /** 워커 여덟을 동시에 돌려 큐가 빌 때까지 반복한다. */
    private void runWorkersUntilDrained() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(WORKERS);
        long deadline = System.currentTimeMillis() + 60_000;

        for (int i = 0; i < WORKERS; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    while (System.currentTimeMillis() < deadline && countByStatus("SENT") < QUEUED) {
                        worker.pollOnce();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            });
        }

        startGate.countDown();
        assertThat(finished.await(90, TimeUnit.SECONDS)).as("워커들이 제때 끝났다").isTrue();
        pool.shutdownNow();
    }

    private void pollUntilStatus(String status, int maxPolls) throws Exception {
        for (int i = 0; i < maxPolls; i++) {
            if (status.equals(onlyRow().get("status"))) {
                return;
            }
            worker.pollOnce();
            // next_retry_at이 지나야 다시 집힌다. backoff-base-ms=1이라 짧다.
            Thread.sleep(20);
        }
        assertThat(onlyRow().get("status")).as("%d회 안에 %s에 도달했다", maxPolls, status)
                .isEqualTo(status);
    }

    private int countByStatus(String status) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM outbox WHERE status = ?", Integer.class, status);
        return n == null ? 0 : n;
    }

    private Map<String, Object> onlyRow() {
        return jdbc.queryForMap("SELECT * FROM outbox ORDER BY id LIMIT 1");
    }

    // ─────────────────────────────────────────────────────────────────────
    // 테스트 전용 빈
    // ─────────────────────────────────────────────────────────────────────

    @TestConfiguration
    static class TestBeans {

        /**
         * {@link MockNotificationSender}를 대신한다.
         *
         * <p>{@code outcome} 프로퍼티로는 "무엇이 몇 번 발송됐는지"를 셀 수 없다.
         * 중복 발송 0건은 그 수를 세야만 확인되므로 기록하는 구현이 필요하다.
         */
        @Bean
        @Primary
        RecordingSender recordingSender() {
            return new RecordingSender();
        }

        @Bean
        RollbackProbe rollbackProbe(ReservationService reservationService) {
            return new RollbackProbe(reservationService);
        }
    }

    /** 발송된 outbox id를 세고, 필요하면 실패하거나 느려진다. */
    static class RecordingSender implements NotificationSender {

        final Map<Long, AtomicInteger> sendCounts = new ConcurrentHashMap<>();
        /** 실제로 발송을 수행한 스레드 이름. 경합이 있었는지 확인하는 데 쓴다. */
        final Set<String> sendingThreads = ConcurrentHashMap.newKeySet();
        volatile boolean failAlways = false;
        volatile long delayMillis = 0;

        void reset() {
            sendCounts.clear();
            sendingThreads.clear();
            failAlways = false;
            delayMillis = 0;
        }

        int totalAttempts() {
            return sendCounts.values().stream().mapToInt(AtomicInteger::get).sum();
        }

        @Override
        public void send(Outbox row) {
            // **실패해도 시도는 센다.** 상한 소진 테스트가 시도 횟수를 본다.
            sendCounts.computeIfAbsent(row.getId(), id -> new AtomicInteger()).incrementAndGet();
            sendingThreads.add(Thread.currentThread().getName());
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failAlways) {
                throw new IllegalStateException("테스트가 주입한 발송 실패");
            }
        }
    }

    /**
     * 확정을 부른 뒤 예외를 던져 트랜잭션을 되돌린다.
     *
     * <p>{@code confirm()}이 {@code REQUIRED}이므로 이 메서드의 트랜잭션에
     * 합류한다. 여기서 던지면 확정과 알림 INSERT가 <b>함께</b> 되돌아가야 하고,
     * 그것이 둘이 같은 트랜잭션이라는 증거다.
     */
    static class RollbackProbe {

        private final ReservationService reservationService;

        RollbackProbe(ReservationService reservationService) {
            this.reservationService = reservationService;
        }

        @Transactional
        public void confirmThenFail(String holdId, long userId) {
            reservationService.confirm(holdId, userId);
            throw new IllegalStateException("의도적 롤백");
        }
    }
}
