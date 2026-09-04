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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>좌석배치도 등록(#102)이 좌석맵이 그릴 수 있는 배치도를 만드는지 본다.</b>
 *
 * <p>이 화면의 결과물은 그 자체로 끝나지 않는다 — 회차 등록(#101)이 배치도를
 * 참조하고, 좌석맵 화면이 {@code rowIndex}·{@code colIndex}로 격자를 그린다.
 * 그래서 "행이 몇 개 들어갔나"가 아니라 <b>격자가 격자인가</b>를 본다.
 *
 * <p>{@link AdminCatalogFlowTest}와 같이 <b>시드가 들어 있는 DB에서 시작한다.</b>
 * 삭제 정책이 "회차가 참조하는가"로 갈리므로, 이미 회차가 쓰고 있는 배치도가
 * 있어야 그 분기를 지날 수 있다.
 */
@SpringBootTest(properties = {
        "holdfast.strategy=pessimistic",
        "holdfast.outbox.scheduler.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("관리자 좌석배치: 격자로 만들고, 회차가 쓰기 시작하면 고정한다")
class AdminSeatLayoutFlowTest {

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
    AdminSeatLayoutService service;

    @Autowired
    AdminSeatLayoutRepository repository;

    /** 배치도 1은 회차 1이 쓰고 있다(고정됨). 배치도 9는 아무도 안 쓴다(수정 가능). */
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
        jdbc.update("""
                INSERT INTO seat_inventory (session_id, seat_id, status, version)
                SELECT 1, s.id, 'AVAILABLE', 0 FROM seat s
                """);

        // 아무 회차도 쓰지 않는 배치도. 삭제·수정이 열려 있는 쪽이다.
        jdbc.update("INSERT INTO seat_layout (id, name, created_at) VALUES (9, '빈 배치도', now())");

        IdentitySequences.resync(jdbc);
    }

    // ── 격자 생성 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("구역을 만들면 행×열만큼 좌석이 생기고 번호가 행 우선으로 매겨진다")
    void createZoneFillsGrid() {
        long zoneId = service.createZone(9L, "A구역", 2, 6, "A");

        assertThat(seatCount(zoneId)).isEqualTo(12);

        // 1행: A-1..A-6, 2행: A-7..A-12. demo-seed.sql이 만드는 배열과 같다.
        assertThat(seatNoAt(zoneId, 1, 1)).isEqualTo("A-1");
        assertThat(seatNoAt(zoneId, 1, 6)).isEqualTo("A-6");
        assertThat(seatNoAt(zoneId, 2, 1)).isEqualTo("A-7");
        assertThat(seatNoAt(zoneId, 2, 6)).isEqualTo("A-12");
    }

    @Test
    @DisplayName("rowIndex·colIndex가 빠짐없이 채워진다 — 비면 좌석맵이 무너진다")
    void gridIndexesAreAlwaysPopulated() {
        long zoneId = service.createZone(9L, "A구역", 3, 4, "A");

        Integer holes = jdbc.queryForObject("""
                SELECT count(*) FROM seat
                 WHERE zone_id = ? AND (row_index IS NULL OR col_index IS NULL
                                        OR row_index < 1 OR col_index < 1)
                """, Integer.class, zoneId);
        assertThat(holes).isZero();

        // 3행 × 4열이 정확히 한 번씩 나온다 — 겹치거나 빠진 칸이 없다.
        Integer distinctCells = jdbc.queryForObject(
                "SELECT count(DISTINCT (row_index, col_index)) FROM seat WHERE zone_id = ?",
                Integer.class, zoneId);
        assertThat(distinctCells).isEqualTo(12);
    }

    @Test
    @DisplayName("접두어를 비우면 구역 이름 앞부분을 딴다 — B구역이면 B-1")
    void blankPrefixIsDerivedFromZoneName() {
        long zoneId = service.createZone(9L, "B구역", 1, 2, "  ");
        assertThat(seatNoAt(zoneId, 1, 1)).isEqualTo("B-1");

        assertThat(AdminSeatLayoutService.derivePrefix("A구역")).isEqualTo("A");
        assertThat(AdminSeatLayoutService.derivePrefix("VIP석")).isEqualTo("VIP");
        // 영문·숫자로 시작하지 않으면 이름을 그대로 쓴다.
        assertThat(AdminSeatLayoutService.derivePrefix("대강당")).isEqualTo("대강당");
    }

    @Test
    @DisplayName("구역 순서는 만든 차례로 붙고 위/아래 버튼으로 바뀐다")
    void zonesAreOrderedAndMovable() {
        long a = service.createZone(9L, "A구역", 1, 2, "A");
        long b = service.createZone(9L, "B구역", 1, 2, "B");
        long c = service.createZone(9L, "C구역", 1, 2, "C");

        assertThat(zoneOrder()).containsExactly(a, b, c);

        service.moveZone(c, true);
        assertThat(zoneOrder()).containsExactly(a, c, b);

        service.moveZone(a, false);
        assertThat(zoneOrder()).containsExactly(c, a, b);

        // 맨 위에서 위로 눌러도 아무 일이 없다. 화면은 그 버튼을 비활성으로 그린다.
        service.moveZone(c, true);
        assertThat(zoneOrder()).containsExactly(c, a, b);
    }

    @Test
    @DisplayName("순서 변경은 회차가 쓰는 배치도에서도 된다 — 그리는 차례일 뿐이다")
    void renameAndReorderAreAllowedOnFrozenLayout() {
        service.updateZone(1L, "1층 A");
        assertThat(jdbc.queryForObject(
                "SELECT name FROM zone WHERE id = 1", String.class)).isEqualTo("1층 A");

        service.moveZone(1L, true); // 혼자뿐이라 아무 일도 없지만 막히지도 않는다
    }

    @Test
    @DisplayName("좌석을 이어 넣으면 기존 최대 행 다음부터 붙는다 — 겹쳐 그려지지 않는다")
    void addSeatsContinuesBelowExistingRows() {
        long zoneId = service.createZone(9L, "A구역", 2, 6, "A");

        int added = service.addSeatRows(zoneId, 1);

        assertThat(added).isEqualTo(6);
        assertThat(seatCount(zoneId)).isEqualTo(18);
        // 3행이 새로 생겼고 번호는 13부터다 — 가장 큰 번호(A-12) 다음이다.
        assertThat(seatNoAt(zoneId, 3, 1)).isEqualTo("A-13");
        assertThat(seatNoAt(zoneId, 3, 6)).isEqualTo("A-18");
    }

    @Test
    @DisplayName("가운데 좌석을 지운 구역에 행을 더해도 번호가 겹치지 않는다")
    void addSeatRowsContinuesFromHighestNumber() {
        long zoneId = service.createZone(9L, "A구역", 1, 6, "A");
        Long hole = jdbc.queryForObject(
                "SELECT id FROM seat WHERE zone_id = ? AND seat_no = 'A-3'", Long.class, zoneId);
        service.deleteSeat(hole);

        // 좌석 수는 5인데 번호는 A-6까지 나가 있다. 수로 세면 A-6을 다시 발급해
        // U-4에 걸린다 — currentNumbering이 "가장 큰 번호 + 1"을 쓰는 이유다.
        service.addSeatRows(zoneId, 1);

        assertThat(seatNoAt(zoneId, 2, 1)).isEqualTo("A-7");
        assertThat(seatCount(zoneId)).isEqualTo(11);
    }

    @Test
    @DisplayName("행을 더할 때 열 수와 접두어는 그 구역에서 읽는다 — 묻지 않는다")
    void addSeatRowsReusesZoneNumbering() {
        long zoneId = service.createZone(9L, "특석", 2, 4, "VIP");

        service.addSeatRows(zoneId, 2);

        assertThat(seatCount(zoneId)).isEqualTo(16);
        assertThat(seatNoAt(zoneId, 3, 1)).isEqualTo("VIP-9");
        assertThat(seatNoAt(zoneId, 4, 4)).isEqualTo("VIP-16");
    }

    @Test
    @DisplayName("같은 배치도에 같은 이름의 구역은 만들 수 없다 (U-3)")
    void duplicateZoneNameIsRejected() {
        service.createZone(9L, "A구역", 1, 2, "A");

        assertThatThrownBy(() -> service.createZone(9L, "A구역", 1, 2, "B"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이미 있습니다");
    }

    @Test
    @DisplayName("한 번에 만들 수 있는 좌석 수를 넘기면 등록 전에 막는다")
    void gridSizeIsCapped() {
        assertThatThrownBy(() -> service.createZone(9L, "너무 큰 구역", 100, 100, "A"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(AdminSeatLayoutService.MAX_SEATS_PER_GRID));

        // 막았으면 구역도 남지 않아야 한다 — 좌석 없는 구역이 생기면 그 배치도로
        // 만든 회차의 좌석맵에 빈 자리가 뜬다.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM zone WHERE seat_layout_id = 9", Integer.class)).isZero();
    }

    // ── 삭제 정책 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("회차가 쓰는 배치도는 구역·좌석·배치도 무엇도 지울 수 없다")
    void layoutInUseIsFrozen() {
        assertThatThrownBy(() -> service.deleteZone(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("회차가 1개 있습니다");

        assertThatThrownBy(() -> service.deleteSeat(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("회차가 1개 있습니다");

        assertThatThrownBy(() -> service.deleteLayout(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("회차가 1개 있습니다");

        assertThatThrownBy(() -> service.createZone(1L, "새 구역", 1, 2, "B"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("회차가 1개 있습니다");
    }

    @Test
    @DisplayName("아무 회차도 쓰지 않는 배치도는 구역·좌석까지 함께 지운다")
    void unusedLayoutIsDeletedWithItsZonesAndSeats() {
        service.createZone(9L, "A구역", 2, 3, "A");

        service.deleteLayout(9L);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM seat_layout WHERE id = 9", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM zone WHERE seat_layout_id = 9", Integer.class)).isZero();
    }

    @Test
    @DisplayName("좌석 한 칸을 지우면 그 자리만 비고 번호는 다시 매기지 않는다")
    void deletingOneSeatLeavesAHole() {
        long zoneId = service.createZone(9L, "A구역", 2, 3, "A");
        Long seatId = jdbc.queryForObject(
                "SELECT id FROM seat WHERE zone_id = ? AND seat_no = 'A-2'", Long.class, zoneId);

        service.deleteSeat(seatId);

        assertThat(seatCount(zoneId)).isEqualTo(5);
        assertThat(seatNoAt(zoneId, 1, 2)).isNull();          // 구멍
        assertThat(seatNoAt(zoneId, 1, 3)).isEqualTo("A-3");  // 번호는 그대로
    }

    // ── 화면과 #101 연결 ────────────────────────────────────────────────

    @Test
    @DisplayName("화면으로 배치도를 만들면 바로 그 배치도의 구역 등록 화면으로 간다")
    void createLayoutThroughPage() throws Exception {
        mvc.perform(post("/admin/layouts").param("name", "화면에서 만든 배치도"))
                .andExpect(status().is3xxRedirection());

        Long id = jdbc.queryForObject(
                "SELECT id FROM seat_layout WHERE name = '화면에서 만든 배치도'", Long.class);
        assertThat(id).isNotNull();

        mvc.perform(get("/admin/layouts/" + id)).andExpect(status().isOk());
        mvc.perform(get("/admin/layouts")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("검증 실패는 400이 아니라 폼과 함께 다시 그린다 — 입력을 잃지 않는다")
    void validationFailureRendersTheFormAgain() throws Exception {
        // 회차가 쓰는 배치도(1)에 구역을 추가하려 한다 — 삭제 정책이 막는다.
        mvc.perform(post("/admin/layouts/1/zones")
                        .param("name", "새 구역")
                        .param("rows", "1")
                        .param("cols", "2"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("화면으로 만든 배치도로 회차를 등록하면 좌석재고가 그만큼 생긴다")
    void layoutBuiltHereWorksForSessionRegistration() throws Exception {
        mvc.perform(post("/admin/layouts").param("name", "회차용 배치도"))
                .andExpect(status().is3xxRedirection());
        Long layoutId = jdbc.queryForObject(
                "SELECT id FROM seat_layout WHERE name = '회차용 배치도'", Long.class);

        mvc.perform(post("/admin/layouts/" + layoutId + "/zones")
                        .param("name", "A구역")
                        .param("rows", "2")
                        .param("cols", "5")
                        .param("seatNoPrefix", "A"))
                .andExpect(status().is3xxRedirection());

        // 목록이 좌석 수를 세어 보여준다 — 회차 등록 드롭다운이 읽는 값이다.
        List<SeatLayoutOption> options = repository.options();
        assertThat(options)
                .filteredOn(o -> o.id().equals(layoutId))
                .singleElement()
                .satisfies(o -> assertThat(o.seatCount()).isEqualTo(10L));

        mvc.perform(post("/admin/programs/1/sessions")
                        .param("seatLayoutId", String.valueOf(layoutId))
                        .param("startsAt", form(3600))
                        .param("endsAt", form(7200))
                        .param("entryOpensAt", form(1800))
                        .param("entryClosesAt", form(7200))
                        .param("reserveOpensAt", form(-3600))
                        .param("maxPerUser", "4")
                        .param("status", "OPEN")
                        .param("userPoolSize", "5"))
                .andExpect(status().is3xxRedirection());

        Long sessionId = jdbc.queryForObject(
                "SELECT id FROM event_session WHERE seat_layout_id = ?", Long.class, layoutId);
        Integer inventory = jdbc.queryForObject(
                "SELECT count(*) FROM seat_inventory WHERE session_id = ?", Integer.class, sessionId);
        assertThat(inventory).isEqualTo(10);

        // 그 회차가 생긴 순간부터 이 배치도는 고정이다.
        assertThat(repository.rows())
                .filteredOn(r -> r.id().equals(layoutId))
                .singleElement()
                .satisfies(r -> assertThat(r.isDeletable()).isFalse());
    }

    // ── 도우미 ──────────────────────────────────────────────────────────

    private List<Long> zoneOrder() {
        return jdbc.queryForList(
                "SELECT id FROM zone WHERE seat_layout_id = 9 ORDER BY sort_order, id", Long.class);
    }

    private int seatCount(long zoneId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM seat WHERE zone_id = ?", Integer.class, zoneId);
        return n == null ? 0 : n;
    }

    /** 그 칸의 좌석번호. 좌석이 없으면 {@code null} — 격자의 구멍이다. */
    private String seatNoAt(long zoneId, int row, int col) {
        List<String> found = jdbc.queryForList(
                "SELECT seat_no FROM seat WHERE zone_id = ? AND row_index = ? AND col_index = ?",
                String.class, zoneId, row, col);
        return found.isEmpty() ? null : found.get(0);
    }

    /** {@code datetime-local} 폼 값. 컨트롤러가 한국 시각으로 해석한다. */
    private static String form(long plusSeconds) {
        return java.time.LocalDateTime.ofInstant(
                        Instant.now().plusSeconds(plusSeconds),
                        java.time.ZoneId.of("Asia/Seoul"))
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
    }
}
