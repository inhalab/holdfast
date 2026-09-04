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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
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
