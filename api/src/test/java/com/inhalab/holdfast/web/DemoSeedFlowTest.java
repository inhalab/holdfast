package com.inhalab.holdfast.web;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>시연용 시드가 다른 시드 위에서도 돈다.</b> 이슈 #154.
 *
 * <h2>무엇이 났나</h2>
 *
 * <p>{@code POST /demo/seed}의 멱등 가드가 <b>{@code seat.id}만 보고</b>
 * "이미 있으니 건너뛴다"를 판정했는데, 실제 유니크 제약은
 * U-4 {@code (zone_id, seat_no)}다. {@code infra/demo-seed.sql}이 같은 구역에
 * {@code A-1}~{@code A-12}를 <b>다른 id(100~111)로</b> 넣어 두므로 id는 안
 * 겹치는데 좌석번호가 겹쳐 <b>500이 났다.</b>
 *
 * <p><b>대본이 권하는 흐름에서 재현된다.</b> {@code demo-script.md} 2절이
 * {@code demo-seed.sql}을 쓰고 1절이 이 화면을 쓰므로, 2절 → 1절 순서로 가면
 * 시드 버튼이 항상 500이었다. 그때 빠져나오려면 터미널로 나가야 했는데,
 * {@code scope-m4.md} 7절이 <b>"시연 중에 터미널로 나갈 필요가 없다"</b>고 적은
 * 것과 어긋난다.
 *
 * <h2>이 검사가 지키는 범위</h2>
 *
 * <p><b>좁다.</b> 지키는 것은 셋뿐이다 — 다른 배치도가 A구역을 차지한 상태에서
 * 시드가 돌아가는가, 두 번 눌러도 같은가, 지울 수 없는 좌석이 있을 때 읽을 수
 * 있는 문장이 나오는가.
 *
 * <p><b>지키지 않는 것:</b> 이 테스트는 {@code infra/demo-seed.sql}을 <b>실행하지
 * 않는다.</b> 그 파일은 {@code DO $$} 블록을 담고 있어 JDBC로 쪼개 돌리기가
 * 취약하다. 대신 그 파일이 만드는 <b>모양</b>을 자바로 깔고, 그 모양이 실제
 * 파일과 같은지는 아래 {@code demoSeedSqlStillFillsZoneOneWithAPrefix}가 파일을
 * 읽어 확인한다. <b>둘이 함께 있어야 뜻이 있다</b> — 앞엣것만 있으면 시드
 * 파일이 좌석번호 체계를 바꿔도 초록불이 유지된다.
 */
@SpringBootTest(properties = {
        "holdfast.strategy=pessimistic",
        "holdfast.outbox.scheduler.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("시연 시드: demo-seed.sql이 깔린 위에서도 좌석을 다시 만든다")
class DemoSeedFlowTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");


    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    /**
     * <b>{@code infra/demo-seed.sql}이 남기는 모양을 깐다.</b> 핵심은 하나다 —
     * 같은 구역에 {@code A-1}…이 <b>1이 아닌 id로</b> 들어 있는 상태.
     */
    @BeforeEach
    void seedLikeDemoSql() {
        jdbc.execute("""
                TRUNCATE TABLE ticket_scan, ticket, outbox, idempotency_record,
                               payment, reservation_seat, reservation,
                               seat_hold, seat_inventory, user_session_quota,
                               seat, zone, seat_layout, event_session, program
                RESTART IDENTITY CASCADE
                """);
        jdbc.update("INSERT INTO program (id, name, created_at) VALUES (1, '데모 공연', now())");
        jdbc.update("INSERT INTO seat_layout (id, name, created_at) VALUES (1, '데모 배치도', now())");
        jdbc.update("INSERT INTO zone (id, seat_layout_id, name, sort_order) VALUES (1, 1, 'A구역', 1)");
        jdbc.update("""
                INSERT INTO seat (id, zone_id, seat_no, row_index, col_index)
                SELECT 100 + g, 1, 'A-' || (g + 1), (g / 6) + 1, (g % 6) + 1
                FROM generate_series(0, 11) AS g
                """);
    }

    private JsonNode seed(int seats, int users) throws Exception {
        MvcResult r = mvc.perform(post("/demo/seed")
                        .param("seats", String.valueOf(seats))
                        .param("users", String.valueOf(users)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    /**
     * <b>이 이슈를 직접 막는 검사다.</b> 고치기 전에는 여기서 500이 났다 —
     * id는 안 겹치는데 {@code (zone_id, seat_no)}가 겹쳤다.
     */
    @Test
    @DisplayName("demo-seed.sql의 A-1..A-12 위에서 눌러도 500이 아니다")
    void seedsOverDemoSeedSqlLayout() throws Exception {
        JsonNode body = seed(5, 20);

        assertThat(body.get("seatCount").asInt()).isEqualTo(5);

        // **화면이 seatId를 1부터 세어 쏜다**(race.html의 발사 루프). id 축이
        // 맞지 않으면 화면은 있지도 않은 좌석에 쏘게 된다.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM seat WHERE zone_id = 1 AND id BETWEEN 1 AND 5", Long.class))
                .isEqualTo(5);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM seat_inventory WHERE session_id = 1", Long.class))
                .isEqualTo(5);

        // 자리를 비웠으므로 옛 좌석은 남지 않는다 — 남으면 같은 구역에
        // 좌석번호가 두 체계로 섞인다.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM seat WHERE zone_id = 1 AND id >= 100", Long.class))
                .isZero();
    }

    /**
     * <b>발표 중에 한 번 더 누르는 일이 생긴다.</b> 좌석 수를 바꿔 다시 누르는
     * 것도 이 화면의 정상 사용이다 — 그것이 시드 버튼을 둔 이유다.
     */
    @Test
    @DisplayName("두 번 눌러도 되고, 좌석 수를 바꿔 눌러도 그 수로 맞는다")
    void pressingAgainIsSafe() throws Exception {
        seed(5, 20);
        JsonNode again = seed(5, 20);
        assertThat(again.get("seatCount").asInt()).isEqualTo(5);

        JsonNode fewer = seed(3, 10);
        assertThat(fewer.get("seatCount").asInt()).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM seat WHERE zone_id = 1", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_session_quota WHERE session_id = 1", Long.class))
                .isEqualTo(10);
    }

    /**
     * 관리자 화면에서 같은 배치도로 다른 회차를 만들면 그 회차의 재고가 A구역
     * 좌석을 붙잡는다. <b>그 좌석은 남의 것이라 지우지 않는다.</b>
     *
     * <p>그때 나가는 것이 {@code INTERNAL_ERROR}면 화면에 무엇을 하라는 말이
     * 없어 좌석 수를 바꿔 가며 다시 누르게 된다 — 이 이슈에서 실제로 그랬다.
     */
    @Test
    @DisplayName("남의 회차가 A구역을 물고 있으면 읽을 수 있는 문장으로 막는다")
    void blockedSeatsGiveReadableMessage() throws Exception {
        jdbc.update("""
                INSERT INTO event_session (id, program_id, seat_layout_id, starts_at, ends_at,
                                           entry_opens_at, entry_closes_at, reserve_opens_at,
                                           max_per_user, status)
                VALUES (9, 1, 1, now() + interval '1 hour', now() + interval '3 hours',
                        now() - interval '10 minutes', now() + interval '3 hours',
                        now() - interval '1 hour', 4, 'OPEN')
                """);
        jdbc.update("""
                INSERT INTO seat_inventory (session_id, seat_id, status, version)
                SELECT 9, s.id, 'AVAILABLE', 0 FROM seat s WHERE s.zone_id = 1
                """);

        mvc.perform(post("/demo/seed").param("seats", "5").param("users", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("다른 회차")));
    }

    /**
     * <b>위 시드가 자바로 흉내 낸 모양이 실제 파일과 같은지 묶는다.</b>
     *
     * <p>{@code SeedScriptSequenceTest}와 같은 형태의 정적 검사다 — 실행하지
     * 않고 읽는다. 보는 것은 하나뿐이다: <b>{@code demo-seed.sql}이 구역 1에
     * {@code 'A-'} 접두 좌석번호를 넣는가.</b> 그것이 이 클래스의
     * {@code seedLikeDemoSql}이 전제한 충돌 조건이다.
     *
     * <p><b>이 검사가 놓치는 것:</b> id 범위(100~111)나 좌석 수는 보지 않는다.
     * 충돌을 만드는 것은 <b>구역과 좌석번호 접두어</b>뿐이고, id가 무엇이든
     * 1이 아니면 같은 문제가 된다. 시드가 접두어를 바꾸면 이 검사가 먼저
     * 깨지고, 그때 위 흐름 테스트의 전제도 함께 고쳐야 한다.
     */
    @Test
    @DisplayName("demo-seed.sql은 여전히 구역 1에 'A-' 접두 좌석번호를 넣는다")
    void demoSeedSqlStillFillsZoneOneWithAPrefix() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("infra"))) {
            root = root.getParent();
        }
        assertThat(root).as("infra/ 가 있는 저장소 루트").isNotNull();

        String sql = Files.readString(root.resolve("infra/demo-seed.sql"), StandardCharsets.UTF_8);
        assertThat(sql)
                .as("구역 1에 'A-' 접두 좌석번호를 넣는 INSERT — 이 충돌 조건이 사라지면 위 테스트의 전제가 바뀐다")
                .contains("'A-' || (g + 1), (g / 6) + 1");
    }
}
