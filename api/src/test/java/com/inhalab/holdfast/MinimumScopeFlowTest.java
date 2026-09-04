package com.inhalab.holdfast;

import com.inhalab.holdfast.payment.MockPaymentGateway;
import com.inhalab.holdfast.payment.PaymentStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>최소 완결선 검증.</b> 좌석 선택 → 선점 → 확정 → Mock 결제 → QR 발급 → 검표가
 * 끊김 없이 통과하는지 확인한다.
 *
 * <p>이 선은 이슈 #66이 M3 종료 시점에 정한 것이다 — "예매 → 결제(Mock) → QR
 * 발급 → 검표까지 한 흐름이 <b>화면에서</b> 돈다". design-spec.md 4.1의 "반드시
 * 구현" 목록 중 그 흐름에 해당하는 항목들이 대상이다.
 *
 * <h2>어디까지 보나 — API 흐름 + 각 단계의 화면</h2>
 *
 * <p><b>상태를 바꾸는 것은 전부 JSON API다.</b> 화면(Thymeleaf)은 그 상태를
 * 비추는 투영이고, 클릭을 요청으로 바꾸는 것은 브라우저의
 * {@code static/js/seatmap.js}다. 그래서 흐름 자체는 API로 몰고, <b>각 단계에서
 * 사용자가 볼 화면이 그 상태를 렌더하는지</b>를 함께 본다.
 *
 * <p>브라우저를 띄워 JS까지 돌리는 방법도 있지만 쓰지 않는다. 최소 완결선이
 * 묻는 것은 "흐름이 끊기지 않는가"이고, 그 흐름의 각 마디는 API 계약이다.
 * 브라우저 자동화는 그 마디가 아니라 <b>마디를 잇는 JS</b>를 검증하는 것이라
 * 목적이 다르고, 도구(Playwright 등)와 CI 비용이 함께 늘어난다.
 *
 * <p>대신 화면을 아예 빼지도 않는다. 세 페이지를 실제로 렌더시켜 Thymeleaf
 * 표현식 오류와 fragment include 실패를 잡는다 — {@code SeatMapPageControllerTest}가
 * 좌석맵 하나에 대해 하던 것을 흐름 전체로 넓힌 셈이다.
 *
 * <h2>확정은 결제 안에 있다 — 흐름의 마디가 하나 적다</h2>
 *
 * <p>최소 완결선을 "선점 → 확정 → 결제"로 적었지만 <b>구현은 확정을 결제 안에
 * 둔다.</b> {@code PaymentService#pay}가 승인 직후
 * {@code ReservationService#confirm}을 부르고 발권까지 같은 트랜잭션에서
 * 끝낸다(#79·#80). 그래서 결제 전에 {@code POST /api/reservations}를 먼저
 * 부르면 두 번째 확정이 되어 409 {@code HOLD_ALREADY_CONFIRMED}로 막힌다.
 *
 * <p><b>확정 경로가 둘인 것은 의도된 설계다.</b>
 * {@code POST /api/reservations}는 결제 없는 확정이며 M3 측정 시나리오가 쓰는
 * 경로이고({@code load-test/scenarios/reservation.js}),
 * {@code POST /api/payments}는 결제를 거친 확정이다. 둘 중 하나를 쓰지 둘 다
 * 쓰지 않는다. 아래 두 테스트가 각 경로를 하나씩 지난다.
 *
 * <h2>배경 워커를 끈다</h2>
 *
 * <p>{@code holdfast.outbox.scheduler.enabled=false}. 확정이 알림 행을 넣으므로
 * 워커가 배경에서 그 행을 집는데, 그 트랜잭션이 살아 있는 동안
 * {@code seed()}의 {@code TRUNCATE}가 락을 기다린다. M3 증거 테스트 다섯 개를
 * 같은 이유로 껐다(이슈 #78). <b>이 테스트가 보는 것은 흐름이지 알림 발송이
 * 아니다</b> — Outbox의 동시성은 {@code OutboxConcurrencyTest}가 따로 본다.
 *
 * <h2>전략은 {@code pessimistic} 하나로 고정한다</h2>
 *
 * <p>최소 완결선은 전략과 무관하다. 다섯 전략에서 반복하면 실행 시간만 다섯
 * 배가 되고, 전략 간 차이는 이미 전략별 경합 테스트가 본다.
 */
@SpringBootTest(properties = {
        "holdfast.strategy=pessimistic",
        // 배경 워커가 TRUNCATE와 얽히지 않게 한다 — 클래스 주석 참조.
        "holdfast.outbox.scheduler.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("최소 완결선: 좌석 선택 → 선점 → 확정 → 결제 → QR → 검표")
class MinimumScopeFlowTest {

    private static final long SESSION_ID = 1L;
    /** 예약 오픈 전 회차. REQ-08 거절 경로 전용이다. */
    private static final long NOT_OPEN_SESSION_ID = 2L;
    private static final long SEAT_ID = 1L;
    /**
     * <b>1이어야 한다.</b> 인증이 아직 없어 페이지 컨트롤러들이
     * {@code DEV_USER_ID = 1}을 하드코딩하고 있고({@code ReservationPageController}),
     * 예약 확인 화면은 그 사용자의 예약만 보여준다(남의 예약이면 404). 다른 값을
     * 쓰면 API 흐름은 통과하는데 화면만 404가 되어, 최소 완결선이 "화면에서
     * 돈다"인 이상 그 자체가 흐름이 끊긴 것이다.
     */
    private static final long USER_ID = 1L;

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

    /**
     * Mock PG를 목으로 바꾼다. {@code holdfast.mock-pg.outcome}은 {@code @Value}라
     * 컨텍스트 생성 시 고정되어 테스트마다 승인/거절을 바꿀 수 없다. 여기서
     * 검증하는 것은 결과 선택 로직이 아니라 <b>승인·거절에 대한 흐름의 반응</b>이다.
     */
    @MockitoBean
    MockPaymentGateway gateway;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void seed() {
        jdbc.execute("""
                TRUNCATE TABLE ticket_scan, ticket, outbox, idempotency_record,
                               payment, reservation_seat, reservation,
                               seat_hold, seat_inventory, user_session_quota,
                               seat, zone, seat_layout, event_session, program
                RESTART IDENTITY CASCADE
                """);

        jdbc.update("INSERT INTO program (id, name, created_at) VALUES (1, '최소 완결선', now())");
        jdbc.update("INSERT INTO seat_layout (id, name, created_at) VALUES (1, '최소 완결선', now())");
        jdbc.update("INSERT INTO zone (id, seat_layout_id, name, sort_order) VALUES (1, 1, 'A', 1)");
        jdbc.update("INSERT INTO seat (id, zone_id, seat_no, row_index, col_index) VALUES (?, 1, 'A-1', 1, 1)",
                SEAT_ID);

        // 지금 예약할 수 있고 지금 입장할 수 있는 회차.
        jdbc.update("""
                INSERT INTO event_session (id, program_id, seat_layout_id, starts_at, ends_at,
                                           entry_opens_at, entry_closes_at, reserve_opens_at,
                                           max_per_user, status)
                VALUES (?, 1, 1, now() + interval '1 hour', now() + interval '3 hours',
                        now() - interval '10 minutes', now() + interval '3 hours',
                        now() - interval '1 hour', 4, 'OPEN')
                """, SESSION_ID);
        // 아직 예약이 열리지 않은 회차(REQ-08).
        jdbc.update("""
                INSERT INTO event_session (id, program_id, seat_layout_id, starts_at, ends_at,
                                           entry_opens_at, entry_closes_at, reserve_opens_at,
                                           max_per_user, status)
                VALUES (?, 1, 1, now() + interval '1 day', now() + interval '1 day 2 hours',
                        now() + interval '1 day', now() + interval '1 day 2 hours',
                        now() + interval '1 hour', 4, 'OPEN')
                """, NOT_OPEN_SESSION_ID);

        for (long s : new long[]{SESSION_ID, NOT_OPEN_SESSION_ID}) {
            jdbc.update("""
                    INSERT INTO seat_inventory (session_id, seat_id, status, version)
                    VALUES (?, ?, 'AVAILABLE', 0)
                    """, s, SEAT_ID);
            jdbc.update("INSERT INTO user_session_quota (session_id, user_id, held_count) VALUES (?, ?, 0)",
                    s, USER_ID);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 1. 정상 흐름 — 이 테스트가 최소 완결선 그 자체다
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("좌석 선택부터 검표까지 한 흐름이 끊기지 않고, 각 단계의 상태 전이가 문서대로다")
    void wholeFlowPassesEndToEnd() throws Exception {
        when(gateway.decide()).thenReturn(PaymentStatus.APPROVED);

        // ── 좌석 선택: 화면이 좌석을 그린다 ──────────────────────────────
        mvc.perform(get("/sessions/{id}", SESSION_ID))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-seat-id")))
                .andExpect(content().string(containsString("is-available")));

        assertSeatInventory("AVAILABLE");

        // ── 선점 ────────────────────────────────────────────────────────
        String holdId = createHold(SESSION_ID).get("holdId").asText();

        // state-transitions 2절·3절: 재고 HELD, 홀드 행 HELD, 예약 행은 홀드
        // 시점에 생기고 status=HELD다(erd 4절, api-spec 1.2).
        assertSeatInventory("HELD");
        assertThat(one("SELECT status FROM seat_hold WHERE hold_id = ?", holdId)).isEqualTo("HELD");
        assertThat(one("SELECT status FROM reservation WHERE hold_id = ?", holdId)).isEqualTo("HELD");

        // ── Mock 결제 = 확정 ────────────────────────────────────────────
        // 확정을 따로 부르지 않는다. PaymentService가 승인 직후 confirm을
        // 호출하고 발권까지 같은 트랜잭션에서 끝낸다(클래스 주석).
        JsonNode payment = pay(holdId);
        long reservationId = payment.get("reservationId").asLong();

        assertThat(payment.get("status").asText()).isEqualTo("APPROVED");
        assertThat(payment.get("reservationStatus").asText()).isEqualTo("CONFIRMED");

        assertSeatInventory("SOLD");
        assertThat(one("SELECT status FROM seat_hold WHERE hold_id = ?", holdId)).isEqualTo("CONFIRMED");
        assertThat(one("SELECT status FROM reservation WHERE id = ?", reservationId)).isEqualTo("CONFIRMED");
        assertThat(one("SELECT status FROM payment WHERE reservation_id = ?", reservationId))
                .isEqualTo("APPROVED");
        // 확정 트랜잭션이 알림을 큐에 넣는다(REQ-05). 워커는 꺼져 있으므로 PENDING으로 남는다.
        assertThat(one("SELECT status FROM outbox WHERE reservation_id = ?", reservationId))
                .isEqualTo("PENDING");

        // ── QR 발급: 결제 승인과 같은 트랜잭션에서 발급된다(#80) ─────────
        JsonNode tickets = getJson(get("/api/reservations/{id}/tickets", reservationId)
                .header("X-User-Id", USER_ID));
        assertThat(tickets).hasSize(1);
        String qrToken = tickets.get(0).get("qrToken").asText();
        assertThat(qrToken).isNotBlank();
        assertThat(tickets.get(0).get("status").asText()).isEqualTo("ISSUED");

        // 예약 확인 화면이 좌석과 티켓을 함께 보여준다.
        mvc.perform(get("/reservations/{id}", reservationId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("A-1")))
                .andExpect(content().string(containsString(qrToken)));

        // ── 검표 ────────────────────────────────────────────────────────
        // 검표 화면이 먼저 뜬다(게이트 단말이 여는 페이지).
        mvc.perform(get("/scan")).andExpect(status().isOk());

        JsonNode scan = scan(qrToken);
        assertThat(scan.get("result").asText()).isEqualTo("ADMITTED");

        // state-transitions 4절: ISSUED → USED, 그리고 입장 이력이 남는다.
        assertThat(one("SELECT status FROM ticket WHERE qr_token = ?", qrToken)).isEqualTo("USED");
        assertThat(one("SELECT result FROM ticket_scan WHERE ticket_id = ?",
                jdbc.queryForObject("SELECT id FROM ticket WHERE qr_token = ?", Long.class, qrToken)))
                .isEqualTo("ADMITTED");
    }

    // ─────────────────────────────────────────────────────────────────────
    // 2. 실패 경로
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("결제가 거절되면 예약은 확정된 채로 두고 좌석을 되돌리지 않는다")
    void declinedPaymentLeavesTheReservationIntact() throws Exception {
        when(gateway.decide()).thenReturn(PaymentStatus.DECLINED);

        String holdId = createHold(SESSION_ID).get("holdId").asText();
        JsonNode declined = pay(holdId);
        long reservationId = declined.get("reservationId").asLong();

        assertThat(declined.get("status").asText()).isEqualTo("DECLINED");
        assertThat(one("SELECT status FROM payment WHERE reservation_id = ?", reservationId))
                .isEqualTo("DECLINED");

        // **거절은 오류가 아니라 결과다**(PaymentResponse). 확정이 일어나지 않으므로
        // 홀드가 그대로 유지되고, 사용자는 TTL 안에 다시 시도할 수 있다.
        assertThat(declined.get("reservationStatus").asText()).isEqualTo("HELD");
        assertSeatInventory("HELD");
        assertThat(one("SELECT status FROM seat_hold WHERE hold_id = ?", holdId)).isEqualTo("HELD");
        assertThat(one("SELECT status FROM reservation WHERE id = ?", reservationId)).isEqualTo("HELD");

        // 거절이면 발권도 알림도 일어나지 않는다 — 확정이 없었기 때문이다.
        assertThat(count("SELECT count(*) FROM ticket")).isZero();
        assertThat(count("SELECT count(*) FROM outbox")).isZero();

        // 재시도는 되돌리지 않고 **새 payment 행**을 만든다(state-transitions 5절).
        when(gateway.decide()).thenReturn(PaymentStatus.APPROVED);
        assertThat(pay(holdId).get("status").asText()).isEqualTo("APPROVED");
        assertThat(count("SELECT count(*) FROM payment WHERE reservation_id = " + reservationId))
                .isEqualTo(2);
        assertSeatInventory("SOLD");
        assertThat(count("SELECT count(*) FROM ticket")).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 티켓을 두 번 스캔하면 두 번째는 REJECTED_DUPLICATE다 (U-11)")
    void secondScanOfTheSameTicketIsRejected() throws Exception {
        when(gateway.decide()).thenReturn(PaymentStatus.APPROVED);
        String qrToken = issueTicket();

        assertThat(scan(qrToken).get("result").asText()).isEqualTo("ADMITTED");
        assertThat(scan(qrToken).get("result").asText()).isEqualTo("REJECTED_DUPLICATE");

        // **U-11은 성공 입장만 티켓당 1건으로 제한한다**(erd 3절). 거절은 몇 번이든
        // 이력으로 남으므로 스캔 2회에 행 2건, 그중 ADMITTED는 1건이다.
        long ticketId = jdbc.queryForObject(
                "SELECT id FROM ticket WHERE qr_token = ?", Long.class, qrToken);
        assertThat(count("SELECT count(*) FROM ticket_scan WHERE ticket_id = " + ticketId)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM ticket_scan WHERE ticket_id = " + ticketId
                + " AND result = 'ADMITTED'")).isEqualTo(1);
    }

    @Test
    @DisplayName("회차의 입장 가능 시간 밖에서는 검표가 REJECTED_TIME이다 (REQ-06)")
    void scanOutsideTheEntryWindowIsRejected() throws Exception {
        when(gateway.decide()).thenReturn(PaymentStatus.APPROVED);
        String qrToken = issueTicket();

        // 입장 창을 과거로 밀어 "이미 닫힌 회차"를 만든다. 티켓은 그대로 ISSUED다.
        jdbc.update("""
                UPDATE event_session
                   SET entry_opens_at = now() - interval '3 hours',
                       entry_closes_at = now() - interval '1 hour'
                 WHERE id = ?
                """, SESSION_ID);

        assertThat(scan(qrToken).get("result").asText()).isEqualTo("REJECTED_TIME");

        // 거절이므로 티켓은 쓰이지 않은 채 남는다 — 시간이 맞을 때 다시 올 수 있다.
        assertThat(one("SELECT status FROM ticket WHERE qr_token = ?", qrToken)).isEqualTo("ISSUED");
    }

    @Test
    @DisplayName("예약 오픈 전에는 홀드가 409 RESERVATION_NOT_OPEN으로 거절된다 (REQ-08)")
    void holdBeforeReservationOpensIsRejected() throws Exception {
        mvc.perform(post("/api/holds")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("sessionId", NOT_OPEN_SESSION_ID,
                                "seatIds", List.of(SEAT_ID)))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_OPEN"));

        // 거절이 상태를 남기지 않는다 — 재고도 홀드도 그대로다.
        assertThat(one("SELECT status FROM seat_inventory WHERE session_id = ? AND seat_id = ?",
                NOT_OPEN_SESSION_ID, SEAT_ID)).isEqualTo("AVAILABLE");
        assertThat(count("SELECT count(*) FROM seat_hold")).isZero();
    }

    @Test
    @DisplayName("결제 없는 확정 경로(M3 측정 경로)도 좌석을 SOLD로 만든다")
    void paymentFreeConfirmationAlsoSellsTheSeat() throws Exception {
        // 아래 마지막 단계에서 결제가 확정을 다시 시도해야 하므로 승인으로 둔다.
        // 스텁이 없으면 decide()가 null을 돌려주고 거절 분기로 빠져 confirm을
        // 아예 부르지 않는다 — 그러면 이 테스트가 보려는 충돌이 일어나지 않는다.
        when(gateway.decide()).thenReturn(PaymentStatus.APPROVED);

        String holdId = createHold(SESSION_ID).get("holdId").asText();

        long reservationId = confirm(holdId).get("reservationId").asLong();

        assertSeatInventory("SOLD");
        assertThat(one("SELECT status FROM reservation WHERE id = ?", reservationId))
                .isEqualTo("CONFIRMED");
        // 알림은 확정 경로에 붙어 있으므로 이쪽에서도 큐에 들어간다(REQ-05).
        assertThat(one("SELECT status FROM outbox WHERE reservation_id = ?", reservationId))
                .isEqualTo("PENDING");

        // **발권은 결제 경로에만 있다.** 이 경로로 확정하면 티켓이 없다 —
        // 결제 없이 티켓을 주지 않는다는 뜻이고, 최소 완결선이 결제를 지나는 이유다.
        assertThat(count("SELECT count(*) FROM ticket")).isZero();

        // 이미 확정된 홀드로 결제하면 두 번째 확정이 되어 막힌다.
        mvc.perform(post("/api/payments")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("holdId", holdId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_ALREADY_CONFIRMED"));
    }

    @Test
    @DisplayName("없는 QR 토큰은 REJECTED_INVALID이고 이력을 남기지 않는다")
    void unknownTokenIsRejectedAsInvalid() throws Exception {
        JsonNode scan = scan("존재하지-않는-토큰");
        assertThat(scan.get("result").asText()).isEqualTo("REJECTED_INVALID");
        // 티켓을 특정할 수 없으므로 ticket_scan에 남길 수 없다(FK가 ticket_id다).
        assertThat(count("SELECT count(*) FROM ticket_scan")).isZero();
    }

    // ─────────────────────────────────────────────────────────────────────
    // 도구
    // ─────────────────────────────────────────────────────────────────────

    /** 선점 → 확정 → 승인 결제까지 몰고 QR 토큰을 돌려준다. */
    private String issueTicket() throws Exception {
        String holdId = createHold(SESSION_ID).get("holdId").asText();
        long reservationId = pay(holdId).get("reservationId").asLong();
        JsonNode tickets = getJson(get("/api/reservations/{id}/tickets", reservationId)
                .header("X-User-Id", USER_ID));
        return tickets.get(0).get("qrToken").asText();
    }

    private JsonNode createHold(long sessionId) throws Exception {
        return postJson("/api/holds", Map.of("sessionId", sessionId, "seatIds", List.of(SEAT_ID)));
    }

    private JsonNode confirm(String holdId) throws Exception {
        return postJson("/api/reservations", Map.of("holdId", holdId));
    }

    private JsonNode pay(String holdId) throws Exception {
        return postJson("/api/payments", Map.of("holdId", holdId));
    }

    private JsonNode scan(String qrToken) throws Exception {
        MvcResult r = mvc.perform(post("/api/tickets/scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("qrToken", qrToken))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    private JsonNode postJson(String path, Map<String, Object> body) throws Exception {
        MvcResult r = mvc.perform(post(path)
                        // 상태를 바꾸는 요청은 전부 Idempotency-Key를 요구한다(api-spec 6절).
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    private JsonNode getJson(org.springframework.test.web.servlet.RequestBuilder rb) throws Exception {
        MvcResult r = mvc.perform(rb).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    private String json(Map<String, Object> body) {
        return objectMapper.writeValueAsString(body);
    }

    private void assertSeatInventory(String expected) {
        assertThat(one("SELECT status FROM seat_inventory WHERE session_id = ? AND seat_id = ?",
                SESSION_ID, SEAT_ID)).isEqualTo(expected);
    }

    private String one(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }
}
