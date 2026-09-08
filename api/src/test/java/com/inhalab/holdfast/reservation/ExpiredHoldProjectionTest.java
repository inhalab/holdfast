package com.inhalab.holdfast.reservation;

import com.inhalab.holdfast.admin.AdminSeatSummaryRepository;
import com.inhalab.holdfast.admin.SeatSummaryRow;
import com.inhalab.holdfast.catalog.CatalogSessionRepository;
import com.inhalab.holdfast.catalog.SessionAvailabilityRow;
import com.inhalab.holdfast.seat.SeatMapRow;
import com.inhalab.holdfast.seat.SeatStatusRow;
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
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>만료된 홀드를 조회가 드러낸다 — 회수하지는 않는다.</b> 이슈 #157.
 *
 * <h2>무엇이 났나</h2>
 *
 * <p>좌석 조회 두 경로가 {@code seat_inventory.status}만 읽어, <b>TTL이 지난
 * 홀드가 화면에서 계속 회색이었다.</b> 네 전략은 홀드 경로가 그 좌석을 인수하므로
 * <b>실제로는 잡을 수 있는데 아무도 누르지 않았다.</b>
 *
 * <p>명세는 반대로 적혀 있었다 — {@code concurrency-spec.md} 3절 표의
 * "조회·확정 시점", {@code api-spec.md}의 "서버가 자동 회수". <b>명세가 반나절
 * 먼저였고 구현이 절반(확정·홀드 경로)만 따랐다.</b>
 *
 * <h2>왜 {@code AVAILABLE}이 아니라 {@code HELD_EXPIRED}인가</h2>
 *
 * <p><b>그 좌석을 잡을 수 있는지가 전략마다 다르기 때문이다.</b> {@code none}은
 * 회수 경로가 아예 없어(erd.md 4.1) 눌러도 거절된다. {@code AVAILABLE}로 덮으면
 * {@code none}에서 <b>못 잡는 좌석을 잡을 수 있다고 말하게 된다</b> — 지금 결함의
 * 방향만 뒤집은 같은 거짓말이다. {@code HELD_EXPIRED}는 다섯 모두에 참인 사실만
 * 말한다.
 *
 * <h2>이 검사가 지키는 범위</h2>
 *
 * <p>셋이다 — 조회가 만료를 <b>드러내는가</b>, 조회가 DB를 <b>바꾸지 않는가</b>,
 * 그리고 <b>집계는 그대로인가</b>(#104의 잔여석 정의가 흔들리지 않아야 한다).
 *
 * <p><b>지키지 않는 것:</b> 전략별로 그 좌석이 실제로 잡히는지는 보지 않는다.
 * 그것은 홀드 경로의 성질이고 전략별 경합 테스트가 이미 덮는다. 여기서 그것까지
 * 보려면 컨텍스트를 다섯 벌 띄워야 하는데, 이 테스트가 묻는 것은 <b>조회가
 * 무엇을 말하는가</b>이지 그 말대로 되는가가 아니다.
 */
@SpringBootTest(properties = {
        "holdfast.strategy=pessimistic",
        "holdfast.outbox.scheduler.enabled=false"
})
@Testcontainers
@DisplayName("만료 홀드: 조회가 HELD_EXPIRED로 드러내되 되돌리지는 않는다")
class ExpiredHoldProjectionTest {

    private static final long SESSION_ID = 1L;

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
    SeatInventoryRepository seatInventoryRepository;

    @Autowired
    AdminSeatSummaryRepository adminSeatSummaryRepository;

    @Autowired
    CatalogSessionRepository catalogSessionRepository;

    @Autowired
    JdbcTemplate jdbc;

    /**
     * 좌석 넷 — <b>살아 있는 홀드와 만료된 홀드를 함께</b> 깐다. 하나만 깔면
     * "만료를 본다"와 "HELD를 전부 바꾼다"가 구분되지 않는다.
     */
    @BeforeEach
    void seed() {
        jdbc.execute("""
                TRUNCATE TABLE ticket_scan, ticket, outbox, idempotency_record,
                               payment, reservation_seat, reservation,
                               seat_hold, seat_inventory, user_session_quota,
                               seat, zone, seat_layout, event_session, program
                RESTART IDENTITY CASCADE
                """);
        jdbc.update("INSERT INTO program (id, name, created_at) VALUES (1, '만료 테스트', now())");
        jdbc.update("INSERT INTO seat_layout (id, name, created_at) VALUES (1, '배치도', now())");
        jdbc.update("INSERT INTO zone (id, seat_layout_id, name, sort_order) VALUES (1, 1, 'A', 1)");
        jdbc.update("""
                INSERT INTO seat (id, zone_id, seat_no, row_index, col_index)
                SELECT g, 1, 'A-' || g, 1, g FROM generate_series(1, 4) AS g
                """);
        jdbc.update("""
                INSERT INTO event_session (id, program_id, seat_layout_id, starts_at, ends_at,
                                           entry_opens_at, entry_closes_at, reserve_opens_at,
                                           max_per_user, status)
                VALUES (1, 1, 1, now() + interval '1 hour', now() + interval '3 hours',
                        now() - interval '10 minutes', now() + interval '3 hours',
                        now() - interval '1 hour', 4, 'OPEN')
                """);
        jdbc.update("""
                INSERT INTO seat_inventory (id, session_id, seat_id, status, hold_id, held_until, version)
                VALUES (1, 1, 1, 'AVAILABLE', NULL, NULL, 0),
                       (2, 1, 2, 'HELD', 'live', now() + interval '5 minutes', 0),
                       (3, 1, 3, 'HELD', 'stale', now() - interval '5 minutes', 0),
                       (4, 1, 4, 'SOLD', NULL, NULL, 0)
                """);
    }

    private Map<Long, String> statusBySeat() {
        return seatInventoryRepository.findStatusRows(SESSION_ID).stream()
                .collect(Collectors.toMap(SeatStatusRow::seatId, SeatStatusRow::status));
    }

    /** <b>이 이슈를 직접 막는 검사다.</b> 만료된 것만 갈리고 살아 있는 것은 그대로다. */
    @Test
    @DisplayName("폴링 조회가 만료된 홀드만 HELD_EXPIRED로 내보낸다")
    void statusRowsRevealExpiredHold() {
        assertThat(statusBySeat())
                .containsEntry(1L, "AVAILABLE")
                .containsEntry(2L, "HELD")           // 아직 살아 있다
                .containsEntry(3L, "HELD_EXPIRED")   // TTL이 지났다
                .containsEntry(4L, "SOLD");
    }

    @Test
    @DisplayName("좌석맵 조회도 같은 값을 말한다 — 두 경로가 갈리지 않는다")
    void seatMapRowsAgree() {
        Map<Long, String> map = seatInventoryRepository.findSeatMapRows(SESSION_ID).stream()
                .collect(Collectors.toMap(SeatMapRow::seatId, SeatMapRow::status));
        assertThat(map).isEqualTo(statusBySeat());
    }

    /**
     * <b>조회는 읽기만 한다.</b> 되돌리면 {@code none}의 "회수 경로가 없다"가 깨져
     * 7.2.2의 지속 경합 결과가 설명을 잃고, 3초 폴링이 매번 쓰기를 하게 되어
     * 측정 경로에 닿는다.
     */
    @Test
    @DisplayName("조회해도 DB의 status는 HELD 그대로다 — 회수하지 않는다")
    void readingDoesNotReclaim() {
        seatInventoryRepository.findStatusRows(SESSION_ID);
        seatInventoryRepository.findSeatMapRows(SESSION_ID);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM seat_inventory WHERE id = 3", String.class)).isEqualTo("HELD");
        assertThat(jdbc.queryForObject(
                "SELECT hold_id FROM seat_inventory WHERE id = 3", String.class)).isEqualTo("stale");
    }

    /**
     * <b>#104의 잔여석 정의가 흔들리지 않아야 한다.</b> 그 이슈는 잔여석을
     * {@code AVAILABLE}만으로 정하고 두 화면이 같은 수를 말하는지를 테스트로
     * 못 박았다. 이 PR은 <b>화면에 사실을 하나 더 적을 뿐</b> 집계 정의를 건드리지
     * 않는다 — 여기서 그것을 지킨다.
     */
    @Test
    @DisplayName("집계는 그대로다 — 만료 홀드는 여전히 HELD로 세고 잔여석에 안 든다")
    void aggregatesAreUnchanged() {
        Map<String, Long> catalog = catalogSessionRepository.availabilityOf(List.of(SESSION_ID))
                .stream().collect(Collectors.toMap(SessionAvailabilityRow::status,
                        SessionAvailabilityRow::count));
        assertThat(catalog)
                .as("회차 목록의 잔여석 — 만료 홀드가 AVAILABLE로 새면 안 된다")
                .containsEntry("AVAILABLE", 1L)
                .containsEntry("HELD", 2L)
                .containsEntry("SOLD", 1L)
                .doesNotContainKey("HELD_EXPIRED");

        Map<String, Long> admin = adminSeatSummaryRepository.summarize().stream()
                .filter(r -> SESSION_ID == r.sessionId())
                .collect(Collectors.toMap(SeatSummaryRow::status, SeatSummaryRow::count,
                        (a, b) -> a, java.util.LinkedHashMap::new));
        assertThat(admin)
                .as("관리자 화면의 좌석 집계 — 회차 목록과 같은 수를 말해야 한다(#104)")
                .isEqualTo(catalog);
    }

    /**
     * {@code held_until}이 비어 있는 {@code HELD} 행은 만료로 보지 않는다.
     * 그런 행은 정상 경로에서 생기지 않지만, 조건이 {@code NULL}을 만나 참이 되면
     * <b>살아 있는 홀드가 만료로 보인다.</b>
     */
    @Test
    @DisplayName("held_until이 NULL인 HELD는 만료로 보지 않는다")
    void nullHeldUntilIsNotExpired() {
        jdbc.update("UPDATE seat_inventory SET held_until = NULL WHERE id = 2");

        Function<Long, String> status = id -> statusBySeat().get(id);
        assertThat(status.apply(2L)).isEqualTo("HELD");
    }
}
