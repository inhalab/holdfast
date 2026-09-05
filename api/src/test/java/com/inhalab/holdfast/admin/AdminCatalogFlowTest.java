package com.inhalab.holdfast.admin;

import com.inhalab.holdfast.support.IdentitySequences;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>관리자 등록이 시드가 들어 있는 DB에서 동작하는지 본다.</b>
 *
 * <h2>왜 이 테스트가 필요했나</h2>
 *
 * <p>{@code /admin/programs} 등록이 500을 냈다 —
 * {@code duplicate key value violates unique constraint "program_pkey"}.
 * <b>CI는 통과했다.</b> 등록 흐름에 테스트가 없었기 때문이다.
 *
 * <p><b>단위 테스트로는 잡히지 않는 종류의 결함이다.</b> {@code createProgram}의
 * SQL은 처음부터 옳았다. 틀린 것은 <b>DB의 상태</b>였다 — 시드 스크립트가 id를
 * 명시해 넣고 IDENTITY 시퀀스를 그대로 두어, 앱이 자동 생성으로 INSERT할 때
 * 이미 있는 id를 발급받았다({@link IdentitySequences}).
 *
 * <p>그래서 이 테스트는 <b>빈 DB에서 시작하지 않는다.</b> 시드 스크립트가 만드는
 * 상태를 그대로 재현한 뒤 등록한다. 아래 {@code seed()}가
 * {@code load-test/sql/seed.sql}·{@code infra/demo-seed.sql}과 <b>같은 모양</b>인
 * 것이 이 테스트의 전부다 — 빈 DB에 등록하면 이 결함은 영원히 안 나온다.
 *
 * <p>시드 스크립트 <b>파일</b>이 시퀀스를 맞추는지는
 * {@code SeedScriptSequenceTest}가 따로 본다. 이 테스트는 <b>앱이 그 상태에서
 * 동작하는가</b>를 본다.
 *
 * <h2>배경 워커를 끈다</h2>
 *
 * <p>{@code MinimumScopeFlowTest}와 같은 이유다 — 워커 트랜잭션이 살아 있으면
 * {@code seed()}의 {@code TRUNCATE}가 락을 기다린다.
 */
@SpringBootTest(properties = {
        "holdfast.strategy=pessimistic",
        "holdfast.outbox.scheduler.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("관리자 등록: 시드가 들어 있는 DB에서 프로그램·회차를 만든다")
class AdminCatalogFlowTest {

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

    @Autowired
    AdminCatalogService service;

    /**
     * <b>시드 스크립트와 같은 모양으로 만든다.</b> {@code TRUNCATE ... RESTART
     * IDENTITY} 뒤에 id를 명시해 INSERT하고, 마지막에 시퀀스를 맞춘다. 이 세
     * 단계가 {@code load-test/sql/seed.sql}이 하는 일이다.
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

        jdbc.update("INSERT INTO program (id, name, created_at) VALUES (1, '시드 프로그램', now())");
        jdbc.update("INSERT INTO seat_layout (id, name, created_at) VALUES (1, '시드 배치도', now())");
        jdbc.update("INSERT INTO zone (id, seat_layout_id, name, sort_order) VALUES (1, 1, 'A', 1)");
        jdbc.update("""
                INSERT INTO seat (id, zone_id, seat_no, row_index, col_index)
                SELECT 1 + g, 1, 'A-' || (g + 1), 1, g + 1
                FROM generate_series(0, 9) AS g
                """);
        jdbc.update("""
                INSERT INTO event_session (id, program_id, seat_layout_id, starts_at, ends_at,
                                           entry_opens_at, entry_closes_at, reserve_opens_at,
                                           max_per_user, status)
                VALUES (1, 1, 1, now() + interval '1 hour', now() + interval '3 hours',
                        now() - interval '10 minutes', now() + interval '3 hours',
                        now() - interval '1 hour', 4, 'OPEN')
                """);

        // 좌석재고·할당량도 시드가 만든다(concurrency-spec 0.4, 1.1). seed.sql과
        // 같은 모양이어야 "이미 판 회차를 수정한다"를 재현할 수 있다.
        jdbc.update("""
                INSERT INTO seat_inventory (session_id, seat_id, status, version)
                SELECT 1, s.id, 'AVAILABLE', 0 FROM seat s
                """);
        jdbc.update("""
                INSERT INTO user_session_quota (session_id, user_id, held_count)
                SELECT 1, u, 0 FROM generate_series(1, 10) AS u
                """);

        // 좌석 없는 배치도. 회차 등록의 거절 경로를 보기 위한 것이다.
        jdbc.update("INSERT INTO seat_layout (id, name, created_at) VALUES (9, '빈 배치도', now())");

        IdentitySequences.resync(jdbc);
    }

    @Test
    @DisplayName("시드된 DB에서 프로그램을 등록하면 시드 뒤 id를 받는다")
    void createProgramAfterSeed() {
        service.createProgram("새 프로그램", "설명");

        Long id = jdbc.queryForObject(
                "SELECT id FROM program WHERE name = '새 프로그램'", Long.class);

        // id=1은 시드가 쓰고 있다. 시퀀스가 맞지 않으면 여기까지 오지 못하고
        // duplicate key로 터진다 — 그것이 등록 화면이 낸 500이다.
        assertThat(id).isNotNull().isGreaterThan(1L);
    }

    @Test
    @DisplayName("등록 화면으로 POST해도 같다 — 목록에 새 프로그램이 보인다")
    void createProgramThroughPage() throws Exception {
        mvc.perform(post("/admin/programs").param("name", "화면에서 만든 프로그램"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/programs"));

        mvc.perform(get("/admin/programs")).andExpect(status().isOk());

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM program WHERE name = '화면에서 만든 프로그램'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("회차를 등록하면 좌석재고와 할당량 행이 함께 생긴다")
    void createSessionCreatesInventoryAndQuota() {
        long sessionId = service.createSession(
                1L, 1L,
                Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200),
                Instant.now(), Instant.now().plusSeconds(7200), Instant.now().minusSeconds(60),
                4, "OPEN", 25);

        // 시드가 session_id=1을 쓰고 있으므로 새 회차는 그 뒤여야 한다.
        assertThat(sessionId).isGreaterThan(1L);

        Integer inventory = jdbc.queryForObject(
                "SELECT count(*) FROM seat_inventory WHERE session_id = ?", Integer.class, sessionId);
        Integer quota = jdbc.queryForObject(
                "SELECT count(*) FROM user_session_quota WHERE session_id = ?", Integer.class, sessionId);

        assertThat(inventory).isEqualTo(10);   // 배치도 1의 좌석 수
        assertThat(quota).isEqualTo(25);
    }

    // ── 시각 관계 (erd.md 2.2의 T-1~T-4) ──────────────────────────────

    /**
     * 정상 회차를 기준으로 두고 테스트마다 <b>한 값만</b> 어긋나게 한다.
     * 다섯 개를 매번 나열하면 무엇이 다른지 읽히지 않는다.
     */
    private static final Instant BASE = Instant.parse("2026-10-01T00:00:00Z");

    private void createSessionWith(Instant starts, Instant ends,
                                   Instant entryOpens, Instant entryCloses,
                                   Instant reserveOpens) {
        service.createSession(1L, 1L, starts, ends, entryOpens, entryCloses, reserveOpens,
                4, "OPEN", 10);
    }

    /** 어느 값도 어긋나지 않은 조합. 아래 테스트들이 여기서 하나씩만 바꾼다. */
    private void createValidSession() {
        createSessionWith(BASE.plus(2, ChronoUnit.HOURS), BASE.plus(4, ChronoUnit.HOURS),
                BASE.plus(1, ChronoUnit.HOURS), BASE.plus(5, ChronoUnit.HOURS), BASE);
    }

    @Test
    @DisplayName("정상 조합은 통과한다 — 아래 거절들이 검증 때문임을 보인다")
    void validScheduleIsAccepted() {
        createValidSession();

        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM event_session WHERE program_id = 1", Long.class);
        assertThat(count).isEqualTo(2);   // 시드 1건 + 방금 만든 1건
    }

    @Test
    @DisplayName("T-1 회차 종료가 시작보다 빠르면 거절한다")
    void rejectsEndBeforeStart() {
        assertThatThrownBy(() -> createSessionWith(
                BASE.plus(4, ChronoUnit.HOURS), BASE.plus(2, ChronoUnit.HOURS),
                BASE.plus(1, ChronoUnit.HOURS), BASE.plus(5, ChronoUnit.HOURS), BASE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("종료가 시작보다");
        assertNoSessionAdded();
    }

    @Test
    @DisplayName("T-2 입장 종료가 입장 시작보다 빠르면 거절한다 — 아무도 입장할 수 없다")
    void rejectsEntryWindowInverted() {
        assertThatThrownBy(() -> createSessionWith(
                BASE.plus(2, ChronoUnit.HOURS), BASE.plus(4, ChronoUnit.HOURS),
                BASE.plus(5, ChronoUnit.HOURS), BASE.plus(1, ChronoUnit.HOURS), BASE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("입장");
        assertNoSessionAdded();
    }

    @Test
    @DisplayName("T-3 예약 오픈이 입장 종료보다 늦으면 거절한다")
    void rejectsReserveOpenAfterEntryClose() {
        assertThatThrownBy(() -> createSessionWith(
                BASE.plus(2, ChronoUnit.HOURS), BASE.plus(4, ChronoUnit.HOURS),
                BASE.plus(1, ChronoUnit.HOURS), BASE.plus(5, ChronoUnit.HOURS),
                BASE.plus(6, ChronoUnit.HOURS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("예약 오픈");
        assertNoSessionAdded();
    }

    @Test
    @DisplayName("T-4 입장 시작이 회차 종료보다 늦으면 거절한다")
    void rejectsEntryOpenAfterEnd() {
        assertThatThrownBy(() -> createSessionWith(
                BASE.plus(2, ChronoUnit.HOURS), BASE.plus(4, ChronoUnit.HOURS),
                BASE.plus(5, ChronoUnit.HOURS), BASE.plus(6, ChronoUnit.HOURS), BASE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("입장 시작");
        assertNoSessionAdded();
    }

    /**
     * <b>실제로 저장됐던 값이다.</b> 종료가 시작보다 17일 앞서고 예약 오픈이
     * 입장 종료보다 늦다. 이 회차는 아무도 예약할 수 없고 예약해도 입장할 수
     * 없는데 조용히 통과했다.
     */
    @Test
    @DisplayName("실제로 통과했던 조합을 이제는 거절한다")
    void rejectsTheCombinationThatSlippedThrough() {
        assertThatThrownBy(() -> createSessionWith(
                Instant.parse("2026-09-24T10:22:00Z"),   // 시작
                Instant.parse("2026-09-07T10:22:00Z"),   // 종료 — 시작보다 17일 앞
                Instant.parse("2026-09-07T10:22:00Z"),   // 입장 시작
                Instant.parse("2026-09-29T16:24:00Z"),   // 입장 종료
                Instant.parse("2026-09-30T10:25:00Z")))  // 예약 오픈 — 입장 종료보다 늦다
                .isInstanceOf(IllegalArgumentException.class);
        assertNoSessionAdded();
    }

    @Test
    @DisplayName("S-1 정의에 없는 상태는 거절한다")
    void rejectsUnknownStatus() {
        assertThatThrownBy(() -> service.createSession(1L, 1L,
                BASE.plus(2, ChronoUnit.HOURS), BASE.plus(4, ChronoUnit.HOURS),
                BASE.plus(1, ChronoUnit.HOURS), BASE.plus(5, ChronoUnit.HOURS), BASE,
                4, "OPENED", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("회차 상태는");
        assertNoSessionAdded();
    }

    @Test
    @DisplayName("1인 최대 매수 0은 거절한다")
    void rejectsZeroMaxPerUser() {
        assertThatThrownBy(() -> service.createSession(1L, 1L,
                BASE.plus(2, ChronoUnit.HOURS), BASE.plus(4, ChronoUnit.HOURS),
                BASE.plus(1, ChronoUnit.HOURS), BASE.plus(5, ChronoUnit.HOURS), BASE,
                0, "OPEN", 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertNoSessionAdded();
    }

    // ── 이미 판 뒤의 수정 (erd.md 2.2의 S-2~S-5) ───────────────────────

    @Test
    @DisplayName("S-2 예약을 받은 회차는 SCHEDULED로 되돌릴 수 없다")
    void rejectsRevertToScheduledWhenReserved() {
        givenConfirmedReservation();

        assertThatThrownBy(() -> updateSeededSession("SCHEDULED", 4, RESERVE_OPENED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SCHEDULED");

        assertThat(seededStatus()).isEqualTo("OPEN");
    }

    @Test
    @DisplayName("S-2 접수를 닫는 CLOSED는 예약이 있어도 허용한다")
    void allowsCloseWhenReserved() {
        givenConfirmedReservation();

        updateSeededSession("CLOSED", 4, RESERVE_OPENED);

        assertThat(seededStatus()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("S-3 판매된 좌석이 있으면 예약 오픈을 미래로 옮길 수 없다")
    void rejectsMovingReserveOpenToFutureWhenSold() {
        givenConfirmedReservation();   // 좌석 1을 SOLD로 만든다

        assertThatThrownBy(() ->
                updateSeededSession("OPEN", 4, Instant.now().plus(7, ChronoUnit.DAYS)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("판매된");
    }

    @Test
    @DisplayName("S-4 1인 최대 매수를 줄이는 것은 막지 않고 알린다")
    void warnsInsteadOfBlockingWhenReducingQuota() {
        jdbc.update("INSERT INTO user_session_quota (session_id, user_id, held_count)"
                + " VALUES (1, 77, 3)");

        List<String> warnings = updateSeededSession("OPEN", 2, RESERVE_OPENED);

        // 줄이는 것 자체는 통과한다 — 막으면 넉넉히 잡은 상한을 영영 못 고친다.
        Integer max = jdbc.queryForObject(
                "SELECT max_per_user FROM event_session WHERE id = 1", Integer.class);
        assertThat(max).isEqualTo(2);
        assertThat(warnings).anyMatch(w -> w.contains("1명"));
    }

    @Test
    @DisplayName("S-5 판매된 좌석이 있으면 입장 시간 변경을 알린다 — 막지는 않는다")
    void warnsWhenSoldSeatsExist() {
        givenConfirmedReservation();

        List<String> warnings = updateSeededSession("OPEN", 4, RESERVE_OPENED);

        assertThat(warnings).anyMatch(w -> w.contains("발권된 티켓"));
    }

    @Test
    @DisplayName("수정에도 같은 시각 검증이 걸린다")
    void updateValidatesScheduleToo() {
        assertThatThrownBy(() -> service.updateSession(1L,
                BASE.plus(4, ChronoUnit.HOURS), BASE.plus(2, ChronoUnit.HOURS),
                BASE.plus(1, ChronoUnit.HOURS), BASE.plus(5, ChronoUnit.HOURS), BASE,
                4, "OPEN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── 회차 삭제 (erd.md 2.2의 S-6) ──────────────────────────────────

    @Test
    @DisplayName("S-6 예약이 없으면 지워지고 재고·할당량 행도 함께 없다")
    void deletesSessionWithoutReservations() {
        service.deleteSession(1L);

        assertThat(count("SELECT count(*) FROM event_session WHERE id = 1")).isZero();
        assertThat(count("SELECT count(*) FROM seat_inventory WHERE session_id = 1")).isZero();
        assertThat(count("SELECT count(*) FROM user_session_quota WHERE session_id = 1")).isZero();
    }

    @Test
    @DisplayName("S-6 예약이 있으면 거절하고 아무것도 지우지 않는다")
    void rejectsDeleteWhenReserved() {
        givenConfirmedReservation();

        assertThatThrownBy(() -> service.deleteSession(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CLOSED");

        assertThat(count("SELECT count(*) FROM event_session WHERE id = 1")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM seat_inventory WHERE session_id = 1")).isEqualTo(10);
    }

    /**
     * <b>이 테스트가 S-6과 S-2를 가른다.</b> S-2의 조건
     * ({@code status NOT IN ('CANCELLED','EXPIRED')})을 삭제에 그대로 쓰면
     * 이 회차가 "지워도 된다"로 통과하고 {@code fk_reservation_session}에 걸린다.
     */
    @Test
    @DisplayName("S-6 만료된 예약만 남아 있어도 거절한다 — S-2 조건을 쓰면 안 되는 이유")
    void rejectsDeleteWhenOnlyExpiredReservationsRemain() {
        jdbc.update("""
                INSERT INTO reservation (session_id, user_id, hold_id, status)
                VALUES (1, 1, 'hold-expired', 'EXPIRED')
                """);

        assertThatThrownBy(() -> service.deleteSession(1L))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(count("SELECT count(*) FROM event_session WHERE id = 1")).isEqualTo(1);
    }

    @Test
    @DisplayName("없는 회차를 지우면 거절한다")
    void rejectsDeleteOfMissingSession() {
        assertThatThrownBy(() -> service.deleteSession(999L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── 회차 목록의 좌석 수 ────────────────────────────────────────────

    @Test
    @DisplayName("회차 목록이 배치도 이름과 그 회차의 좌석 수를 보여준다")
    void listShowsLayoutNameAndSeatCount() throws Exception {
        mvc.perform(get("/admin/programs/1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("시드 배치도")))
                .andExpect(content().string(containsString("10석")));
    }

    /**
     * <b>배치도의 좌석 수가 아니라 회차의 재고 수를 센다.</b> 시드가 배치도로
     * 거르지 않고 재고를 넣으면 둘이 갈리는데, 그때 맞는 것은 사용자가 실제로
     * 보는 좌석맵 쪽이다({@code SessionSeatInfo}).
     */
    @Test
    @DisplayName("배치도 좌석 수와 회차 재고 수가 갈리면 재고 쪽을 보여준다")
    void seatCountFollowsInventoryNotLayout() throws Exception {
        // 배치도에는 좌석이 10개인데 이 회차의 재고는 3개만 남긴다.
        jdbc.update("DELETE FROM seat_inventory WHERE session_id = 1 AND seat_id > 3");

        mvc.perform(get("/admin/programs/1"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("3석")));
    }

    // ── 화면 ───────────────────────────────────────────────────────────

    /**
     * <b>조용히 저장되면 관리자가 알 수 없다.</b> 실패는 리다이렉트하지 않고 그
     * 자리에서 상세 화면을 다시 그린다 — 이유가 보이고, 입력하던 폼도 남는다.
     *
     * <p><b>플래시 메시지를 쓰지 않는 이유는 앱이 2대이기 때문이다.</b> 플래시는
     * 세션에 담기는데 POST를 받은 인스턴스와 리다이렉트된 GET을 받는 인스턴스가
     * 달라 유실된다. 앱에 직접 붙이면 뜨고 nginx를 거치면 안 뜬다.
     */
    @Test
    @DisplayName("화면에서 잘못된 값을 넣으면 이유가 그 자리에 뜬다")
    void pageShowsWhyItFailed() throws Exception {
        mvc.perform(post("/admin/programs/1/sessions")
                        .param("seatLayoutId", "1")
                        .param("startsAt", "2026-09-24T19:22")
                        .param("endsAt", "2026-09-07T19:22")      // 시작보다 앞
                        .param("entryOpensAt", "2026-09-07T19:22")
                        .param("entryClosesAt", "2026-09-30T01:24")
                        .param("reserveOpensAt", "2026-09-30T19:25")
                        .param("maxPerUser", "4")
                        .param("status", "OPEN"))
                .andExpect(status().isOk())              // 리다이렉트가 아니다
                .andExpect(view().name("admin/program-detail"))
                .andExpect(model().attributeExists("error"))
                // 폼을 다시 그리는 데 필요한 것이 모두 실려 있어야 한다
                .andExpect(model().attributeExists("program", "sessions", "layouts"))
                .andExpect(content().string(containsString("종료가 시작보다")));

        assertNoSessionAdded();
    }

    @Test
    @DisplayName("성공은 리다이렉트한다 — 새로고침으로 다시 등록되지 않는다")
    void successStillRedirects() throws Exception {
        mvc.perform(post("/admin/programs/1/sessions")
                        .param("seatLayoutId", "1")
                        .param("startsAt", "2026-10-01T21:00")
                        .param("endsAt", "2026-10-01T23:00")
                        .param("entryOpensAt", "2026-10-01T20:00")
                        .param("entryClosesAt", "2026-10-02T00:00")
                        .param("reserveOpensAt", "2026-09-01T10:00")
                        .param("maxPerUser", "4")
                        .param("status", "OPEN"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/programs/1"));
    }

    /**
     * 삭제 거절도 리다이렉트하지 않고 <b>그 자리에서 목록을 다시 그린다</b>.
     * 플래시는 못 쓴다 — 앱이 2대라 세션이 로드밸런서를 못 넘는다
     * ({@code design-spec.md} 5.5). 그래서 "플래시가 살아 있나"가 아니라
     * <b>"실패 응답이 그 자리에서 그린 화면인가"</b>를 본다.
     */
    @Test
    @DisplayName("삭제가 거절되면 이유가 그 자리에 뜬다")
    void deleteRejectionShowsWhy() throws Exception {
        givenConfirmedReservation();

        mvc.perform(post("/admin/sessions/1/delete").param("programId", "1"))
                .andExpect(status().isOk())              // 리다이렉트가 아니다
                .andExpect(view().name("admin/program-detail"))
                .andExpect(model().attributeExists("error"))
                .andExpect(model().attributeExists("program", "sessions", "layouts"))
                .andExpect(content().string(containsString("지울 수 없습니다")));

        assertThat(count("SELECT count(*) FROM event_session WHERE id = 1")).isEqualTo(1);
    }

    @Test
    @DisplayName("삭제가 성공하면 상대 경로로 리다이렉트한다")
    void deleteSuccessRedirectsRelatively() throws Exception {
        mvc.perform(post("/admin/sessions/1/delete").param("programId", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/programs/1"));

        assertThat(count("SELECT count(*) FROM event_session WHERE id = 1")).isZero();
    }

    // ── 도우미 ─────────────────────────────────────────────────────────

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0L : n;
    }

    /** 시드가 만든 회차 1건 말고는 늘어나지 않았다. */
    private void assertNoSessionAdded() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM event_session", Long.class);
        assertThat(count).isEqualTo(1);
    }

    private String seededStatus() {
        return jdbc.queryForObject("SELECT status FROM event_session WHERE id = 1", String.class);
    }

    /**
     * <b>이미 지난</b> 예약 오픈. S-3은 판매된 좌석이 있을 때 예약 오픈을 미래로
     * 옮기는 것만 막으므로, 그 규칙을 건드리지 않으려는 테스트는 이 값을 쓴다.
     */
    private static final Instant RESERVE_OPENED = Instant.now().minus(1, ChronoUnit.DAYS);

    /** 시드 회차(1번)를 상태·상한·예약오픈만 바꿔 수정한다. 시각은 늘 유효하다. */
    private List<String> updateSeededSession(String status, int maxPerUser, Instant reserveOpens) {
        return service.updateSession(1L,
                BASE.plus(2, ChronoUnit.HOURS), BASE.plus(4, ChronoUnit.HOURS),
                BASE.plus(1, ChronoUnit.HOURS), BASE.plus(5, ChronoUnit.HOURS),
                reserveOpens, maxPerUser, status);
    }

    /** 시드 회차에 확정 예약 1건과 판매된 좌석 1석을 만든다. */
    private void givenConfirmedReservation() {
        jdbc.update("""
                INSERT INTO reservation (session_id, user_id, hold_id, status, confirmed_at)
                VALUES (1, 1, 'hold-test', 'CONFIRMED', now())
                """);
        jdbc.update("UPDATE seat_inventory SET status = 'SOLD'"
                + " WHERE session_id = 1 AND seat_id = 1");
    }

    @Test
    @DisplayName("좌석 없는 배치도로 회차를 만들면 거절한다")
    void createSessionRejectsEmptyLayout() {
        assertThatThrownBy(() -> service.createSession(
                1L, 9L,
                Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200),
                Instant.now(), Instant.now().plusSeconds(7200), Instant.now().minusSeconds(60),
                4, "OPEN", 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("등록을 두 번 해도 id가 계속 앞으로 간다")
    void sequenceKeepsAdvancing() {
        service.createProgram("첫 번째", null);
        service.createProgram("두 번째", null);

        Long first = jdbc.queryForObject("SELECT id FROM program WHERE name = '첫 번째'", Long.class);
        Long second = jdbc.queryForObject("SELECT id FROM program WHERE name = '두 번째'", Long.class);

        assertThat(second).isGreaterThan(first);
    }

    /**
     * 브라우저는 화면을 열 때마다 {@code /favicon.ico}를 요청한다. 500이면 로그가
     * 스택트레이스로 덮여 진짜 오류를 찾기 어려워진다.
     */
    @Test
    @DisplayName("없는 정적 자원은 500이 아니라 404다")
    void missingStaticResourceIs404() throws Exception {
        mvc.perform(get("/favicon.ico")).andExpect(status().isNotFound());
    }
}
