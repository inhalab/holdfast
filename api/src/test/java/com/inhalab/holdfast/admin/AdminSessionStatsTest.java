package com.inhalab.holdfast.admin;

import com.inhalab.holdfast.catalog.SessionCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>회차별 예약 현황 통계.</b> 이슈 #104 — SFR-002의 통계 축.
 *
 * <h2>이 테스트가 지키는 것은 잔여석의 정의다</h2>
 *
 * <p>이 이슈의 판단은 <b>"잔여석은 {@code AVAILABLE}만 센다"</b>이고, 그 근거는
 * <b>회차 목록이 같은 방식으로 세어 매진을 가른다</b>는 것이다
 * ({@link SessionStats} 클래스 주석). <b>그 판단이 코드에만 있으면 다음 사람이
 * 다르게 고친다</b> — 만료된 홀드를 잔여석에 넣는 것은 얼핏 더 정확해 보이기
 * 때문이다.
 *
 * <p>그래서 시드는 <b>살아 있는 홀드와 만료된 홀드를 모두 깔고</b>, 두 화면이
 * 같은 수를 말하는지까지 본다. 한쪽만 고치면 이 테스트가 깨진다.
 *
 * <h2>무엇을 보지 않나</h2>
 *
 * <p><b>만료 홀드가 회수되는지는 보지 않는다.</b> 회수는 홀드 획득 경로의 일이고
 * ({@code state-transitions.md}의 {@code HELD → RELEASED}), 이 화면은 <b>지금 DB에
 * 있는 것을 세기만 한다.</b> 여기서 회수까지 확인하면 조회 화면의 테스트가 예약
 * 경로의 테스트를 겸하게 된다.
 */
@SpringBootTest(properties = {
        "holdfast.strategy=pessimistic",
        "holdfast.outbox.scheduler.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("회차별 예약 현황: 잔여석은 AVAILABLE만 세고 만료 홀드는 따로 적는다")
class AdminSessionStatsTest {

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
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    /**
     * 회차 1은 좌석 7석 — <b>AVAILABLE 2 / 살아 있는 홀드 1 / 만료된 홀드 2 /
     * SOLD 2.</b> 홀드를 한 종류만 깔면 "만료를 어떻게 셀 것인가"가 시드에서
     * 사라져 판단을 고정하지 못한다.
     *
     * <p>회차 2는 <b>좌석만 있고 예약이 없다.</b> 그 회차가 목록에서 빠지지
     * 않는지 보려는 것이다.
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
        jdbc.update("INSERT INTO program (id, name, created_at) VALUES (1, '통계 테스트', now())");
        jdbc.update("INSERT INTO seat_layout (id, name, created_at) VALUES (1, '배치도', now())");
        jdbc.update("INSERT INTO zone (id, seat_layout_id, name, sort_order) VALUES (1, 1, 'A', 1)");
        jdbc.update("""
                INSERT INTO seat (id, zone_id, seat_no, row_index, col_index)
                SELECT 1 + g, 1, 'A-' || (g + 1), 1, g + 1 FROM generate_series(0, 9) AS g
                """);
        jdbc.update("""
                INSERT INTO event_session (id, program_id, seat_layout_id, starts_at, ends_at,
                                           entry_opens_at, entry_closes_at, reserve_opens_at,
                                           max_per_user, status)
                SELECT g, 1, 1, now() + interval '1 hour', now() + interval '3 hours',
                       now() - interval '10 minutes', now() + interval '3 hours',
                       now() - interval '1 hour', 4, 'OPEN'
                FROM generate_series(1, 2) AS g
                """);

        // 회차 1: 잔여 2 / 홀드 3(그중 2는 만료) / 판매 2
        jdbc.update("""
                INSERT INTO seat_inventory (id, session_id, seat_id, status, hold_id, held_until, version)
                VALUES (1, 1, 1, 'AVAILABLE', NULL, NULL, 0),
                       (2, 1, 2, 'AVAILABLE', NULL, NULL, 0),
                       (3, 1, 3, 'HELD', 'hold-live', now() + interval '5 minutes', 0),
                       (4, 1, 4, 'HELD', 'hold-old-a', now() - interval '5 minutes', 0),
                       (5, 1, 5, 'HELD', 'hold-old-b', now() - interval '1 hour', 0),
                       (6, 1, 6, 'SOLD', NULL, NULL, 0),
                       (7, 1, 7, 'SOLD', NULL, NULL, 0)
                """);
        // 회차 2: 좌석만 있고 예약이 없다
        jdbc.update("""
                INSERT INTO seat_inventory (id, session_id, seat_id, status, version)
                VALUES (8, 2, 8, 'AVAILABLE', 0),
                       (9, 2, 9, 'AVAILABLE', 0),
                       (10, 2, 10, 'AVAILABLE', 0)
                """);

        // 예약 네 건이 상태별로 하나씩. 취소·만료를 세는 열이 실제로 그 상태를
        // 집는지 보려면 넷이 모두 있어야 한다.
        jdbc.update("""
                INSERT INTO reservation (id, session_id, user_id, hold_id, status, confirmed_at, cancelled_at)
                VALUES (1, 1, 7, 'hold-sold', 'CONFIRMED', now(), NULL),
                       (2, 1, 7, 'hold-cx',   'CANCELLED', NULL, now()),
                       (3, 1, 8, 'hold-old-a','EXPIRED',   NULL, NULL),
                       (4, 1, 9, 'hold-live', 'HELD',      NULL, NULL)
                """);
        jdbc.update("""
                INSERT INTO reservation_seat (id, reservation_id, seat_inventory_id)
                VALUES (1, 1, 6), (2, 1, 7)
                """);
        // 발권 2장 중 1장이 검표를 통과했다.
        jdbc.update("""
                INSERT INTO ticket (id, reservation_seat_id, qr_token, status, issued_at, used_at)
                VALUES (1, 1, 'qr-1', 'ISSUED', now(), NULL),
                       (2, 2, 'qr-2', 'USED',   now(), now())
                """);
    }

    @SuppressWarnings("unchecked")
    private Map<Long, SessionStats> stats() throws Exception {
        Object attribute = mvc.perform(get("/admin/reservations"))
                .andExpect(status().isOk())
                .andReturn().getModelAndView().getModel().get("sessionStats");
        return (Map<Long, SessionStats>) attribute;
    }

    /**
     * <b>이 이슈의 판단을 고정하는 테스트다.</b> 홀드 3석 중 2석이 만료됐지만
     * 잔여석은 그대로 2다 — 만료 홀드는 <b>{@code HELD}로 남아 있는 한 잔여석이
     * 아니다.</b>
     */
    @Test
    @DisplayName("잔여석은 AVAILABLE만 센다 — 만료된 홀드를 되돌려 놓지 않는다")
    void availableCountsOnlyAvailableStatus() throws Exception {
        SessionStats s = stats().get(1L);

        assertThat(s.available()).isEqualTo(2);
        assertThat(s.held()).isEqualTo(3);        // 만료된 것까지 포함한 HELD 전부
        assertThat(s.sold()).isEqualTo(2);
        assertThat(s.totalSeats()).isEqualTo(7);
    }

    /**
     * 만료 홀드를 잔여석에 넣지 않는 대신 <b>옆에 적는다.</b> 그것이 없으면
     * 운영자는 "잔여 2석인데 왜 4석이 잡히지"를 화면에서 풀 수 없다.
     */
    @Test
    @DisplayName("만료된 홀드는 잔여석과 별개로 센다 — 시각이 지난 것만 잡는다")
    void expiredHoldsCountedSeparately() throws Exception {
        SessionStats s = stats().get(1L);

        // 홀드 3석 중 시각이 지난 2석만. 살아 있는 홀드는 여기 들어오지 않는다.
        assertThat(s.expiredHolds()).isEqualTo(2);
        assertThat(s.expiredHolds()).isLessThan(s.held());
    }

    /**
     * <b>정의가 갈리면 여기서 깨진다.</b> 회차 목록의 잔여석은 매진 판정을
     * 가르는 값이므로({@code SaleState}), 관리자 화면이 다른 수를 말하면 두
     * 화면이 서로 다른 사실을 주장하게 된다.
     */
    @Test
    @DisplayName("회차 목록과 관리자 화면이 같은 잔여석을 말한다")
    @SuppressWarnings("unchecked")
    void catalogAndAdminAgreeOnAvailable() throws Exception {
        List<SessionCard> cards = (List<SessionCard>) mvc.perform(get("/programs/1"))
                .andExpect(status().isOk())
                .andReturn().getModelAndView().getModel().get("sessions");
        SessionCard card = cards.stream().filter(c -> c.sessionId() == 1L).findFirst().orElseThrow();

        SessionStats s = stats().get(1L);
        assertThat(s.available()).isEqualTo(card.available());
        assertThat(s.totalSeats()).isEqualTo(card.total());
    }

    @Test
    @DisplayName("예약 건수를 상태별로 나눠 센다 — 취소·만료가 서로 섞이지 않는다")
    void reservationCountsSplitByStatus() throws Exception {
        SessionStats s = stats().get(1L);

        assertThat(s.reservationsHeld()).isEqualTo(1);
        assertThat(s.reservationsConfirmed()).isEqualTo(1);
        assertThat(s.reservationsCancelled()).isEqualTo(1);
        assertThat(s.reservationsExpired()).isEqualTo(1);
    }

    /**
     * 발권은 {@code ISSUED + USED}다 — <b>검표를 통과했다고 발권이 취소되는
     * 것이 아니므로</b> 검표 완료분도 발권에 든다. 검표 완료는
     * {@code USED}뿐이다({@code state-transitions.md} 4절).
     */
    @Test
    @DisplayName("발권은 검표 완료분을 포함하고, 검표 완료는 USED만 센다")
    void ticketCountsIncludeUsedInIssued() throws Exception {
        SessionStats s = stats().get(1L);

        assertThat(s.ticketsIssued()).isEqualTo(2);
        assertThat(s.ticketsUsed()).isEqualTo(1);
    }

    /**
     * 예약이 하나도 없는 회차가 목록에서 빠지면 <b>"아직 한 장도 안 팔린
     * 회차"를 볼 수 없다.</b> 그것이야말로 운영자가 찾는 줄이다.
     */
    @Test
    @DisplayName("예약이 없는 회차도 줄이 남는다 — 0을 감추지 않는다")
    void sessionWithoutReservationsStillListed() throws Exception {
        SessionStats s = stats().get(2L);

        assertThat(s).isNotNull();
        assertThat(s.available()).isEqualTo(3);
        assertThat(s.reservationsConfirmed()).isZero();
        assertThat(s.ticketsIssued()).isZero();
    }

    /**
     * 정의를 코드에만 두면 화면을 보는 사람은 알 수 없다. 이 문장이 사라지면
     * 만료 홀드 열이 무엇인지 설명할 것이 화면에 없어진다.
     */
    @Test
    @DisplayName("잔여석의 정의를 화면이 적는다")
    void pageStatesTheDefinition() throws Exception {
        mvc.perform(get("/admin/reservations"))
                .andExpect(content().string(containsString("잔여석은")))
                .andExpect(content().string(containsString("만료 홀드")));
    }
}
