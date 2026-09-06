package com.inhalab.holdfast.web;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * <b>예약 조회 — 비회원 조회와 마이페이지.</b> 이슈 #103, REQ-14.
 *
 * <h2>무엇을 보나</h2>
 *
 * <p><b>두 화면이 같은 소유권 검사를 탄다는 것</b>이 이 이슈의 본체다
 * ({@code ReservationLookupPageController} 클래스 주석). 그래서 여기서 보는 것도
 * 그것이다 — 비회원 조회가 검사를 <b>우회하지 않는지</b>, 마이페이지가 그
 * 사용자의 것만 모으는지.
 *
 * <h2>무엇을 보지 않나</h2>
 *
 * <p><b>마이페이지가 남의 {@code userId}로 열리는 것을 막는 테스트는 넣지
 * 않는다.</b> 그것은 결함이 아니라 <b>의도된 동작</b>이다
 * ({@code scope-m4.md} 6절) — API가 그대로 열려 있어 화면만 고정해도 막지
 * 못하고, 사용자를 바꿔 보이는 것이 시연의 수단이다. <b>막는 테스트를 넣으면
 * 그 판단을 뒤집는 것이 된다.</b>
 *
 * <h2>실패 응답의 모양</h2>
 *
 * <p>{@code design-spec.md} 5.5 — 앱이 2대라 플래시가 로드밸런서를 못 넘는다.
 * 그래서 <b>"플래시가 살아 있나"가 아니라 "실패 응답이 그 자리에서 그린
 * 화면인가"</b>를 본다. 성공은 상대 경로 리다이렉트다(5.4).
 */
@SpringBootTest(properties = {
        "holdfast.strategy=pessimistic",
        "holdfast.outbox.scheduler.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("예약 조회: 비회원 조회와 마이페이지가 같은 소유권 검사를 탄다")
class ReservationLookupFlowTest {

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

    /** 사용자 7이 예약 둘, 사용자 8이 하나. 목록이 사용자로 갈리는지 보려면 둘 이상이 필요하다. */
    @BeforeEach
    void seed() {
        jdbc.execute("""
                TRUNCATE TABLE ticket_scan, ticket, outbox, idempotency_record,
                               payment, reservation_seat, reservation,
                               seat_hold, seat_inventory, user_session_quota,
                               seat, zone, seat_layout, event_session, program
                RESTART IDENTITY CASCADE
                """);
        jdbc.update("INSERT INTO program (id, name, created_at) VALUES (1, '조회 테스트', now())");
        jdbc.update("INSERT INTO seat_layout (id, name, created_at) VALUES (1, '배치도', now())");
        jdbc.update("INSERT INTO zone (id, seat_layout_id, name, sort_order) VALUES (1, 1, 'A', 1)");
        jdbc.update("""
                INSERT INTO seat (id, zone_id, seat_no, row_index, col_index)
                SELECT 1 + g, 1, 'A-' || (g + 1), 1, g + 1 FROM generate_series(0, 4) AS g
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
                INSERT INTO seat_inventory (id, session_id, seat_id, status, version)
                SELECT s.id, 1, s.id, 'AVAILABLE', 0 FROM seat s
                """);

        // 예약 1·2 → 사용자 7, 예약 3 → 사용자 8
        jdbc.update("""
                INSERT INTO reservation (id, session_id, user_id, hold_id, status, confirmed_at)
                VALUES (1, 1, 7, 'hold-a', 'CONFIRMED', now()),
                       (2, 1, 7, 'hold-b', 'CANCELLED', now()),
                       (3, 1, 8, 'hold-c', 'CONFIRMED', now())
                """);
        // 예약 1은 2석, 예약 3은 1석. 예약 2는 취소돼 좌석이 없다.
        jdbc.update("""
                INSERT INTO reservation_seat (reservation_id, seat_inventory_id)
                VALUES (1, 1), (1, 2), (3, 3)
                """);
    }

    // ── 비회원 조회 ────────────────────────────────────────────────────

    @Test
    @DisplayName("파라미터가 없으면 폼만 그린다")
    void showsFormWithoutParameters() throws Exception {
        mvc.perform(get("/reservations/lookup"))
                .andExpect(status().isOk())
                .andExpect(view().name("reservations/lookup"))
                .andExpect(model().attributeDoesNotExist("error"));
    }

    @Test
    @DisplayName("예약번호와 예약자 번호가 맞으면 상세 화면으로 상대 경로 리다이렉트한다")
    void redirectsToDetailWhenBothMatch() throws Exception {
        mvc.perform(get("/reservations/lookup")
                        .param("reservationId", "1").param("userId", "7"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/reservations/1?userId=7"));
    }

    /**
     * <b>이 테스트가 "검사를 우회하지 않는다"를 지킨다.</b> 예약번호가 맞아도
     * 예약자 번호가 다르면 열리지 않는다 — {@code ReservationService#get}의
     * 검사를 그대로 타기 때문이다.
     */
    @Test
    @DisplayName("예약번호가 맞아도 예약자 번호가 다르면 열리지 않는다")
    void rejectsWhenOwnerDiffers() throws Exception {
        mvc.perform(get("/reservations/lookup")
                        .param("reservationId", "3").param("userId", "7"))
                .andExpect(status().isOk())              // 리다이렉트가 아니다
                .andExpect(view().name("reservations/lookup"))
                .andExpect(model().attributeExists("error"));
    }

    @Test
    @DisplayName("없는 예약번호도 같은 화면 같은 사유다 — 존재 여부를 흘리지 않는다")
    void missingReservationLooksTheSame() throws Exception {
        mvc.perform(get("/reservations/lookup")
                        .param("reservationId", "999").param("userId", "7"))
                .andExpect(status().isOk())
                .andExpect(view().name("reservations/lookup"))
                .andExpect(model().attributeExists("error"));
    }

    /**
     * 실패해도 입력값이 폼에 남아야 고칠 수 있다. 리다이렉트했다면 잃는다
     * ({@code design-spec.md} 5.5).
     */
    @Test
    @DisplayName("실패 화면에 입력값이 남는다")
    void keepsInputOnFailure() throws Exception {
        mvc.perform(get("/reservations/lookup")
                        .param("reservationId", "3").param("userId", "7"))
                .andExpect(model().attribute("reservationId", 3L))
                .andExpect(model().attribute("userId", 7L));
    }

    @Test
    @DisplayName("접근 제어가 아니라는 것을 화면이 적는다")
    void pageStatesItIsNotAccessControl() throws Exception {
        mvc.perform(get("/reservations/lookup"))
                .andExpect(content().string(containsString("접근 제어를 하지 않습니다")));
    }

    // ── 마이페이지 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("그 사용자의 예약만 모은다 — 남의 것은 목록에 없다")
    void listsOnlyThatUsersReservations() throws Exception {
        mvc.perform(get("/my/reservations").param("userId", "7"))
                .andExpect(status().isOk())
                .andExpect(view().name("reservations/my"))
                .andExpect(content().string(containsString("CONFIRMED")))
                .andExpect(content().string(containsString("CANCELLED")))
                // 예약 3은 사용자 8의 것이라 이 목록의 링크에 없어야 한다
                .andExpect(content().string(not(containsString("/reservations/3?userId=7"))));
    }

    @Test
    @DisplayName("좌석 수를 함께 보여준다")
    void showsSeatCount() throws Exception {
        mvc.perform(get("/my/reservations").param("userId", "7"))
                .andExpect(content().string(containsString("2석")));
    }

    @Test
    @DisplayName("상세로 가는 링크가 userId를 싣는다 — 안 실으면 기본값 1로 열려 404다")
    void detailLinkCarriesUserId() throws Exception {
        mvc.perform(get("/my/reservations").param("userId", "7"))
                .andExpect(content().string(containsString("/reservations/1?userId=7")));
    }

    @Test
    @DisplayName("예약이 없는 사용자도 오류가 아니다")
    void emptyListIsNotAnError() throws Exception {
        mvc.perform(get("/my/reservations").param("userId", "99"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("예약이 없습니다")));
    }

    /**
     * <b>막는 테스트가 아니라 열리는 것을 확인하는 테스트다.</b> 남의 번호로
     * 여는 것은 의도된 동작이므로({@code scope-m4.md} 6절) 그것이 200인 것을
     * 못박아 둔다 — 나중에 누가 "보안"으로 막으면 여기서 걸린다.
     */
    @Test
    @DisplayName("다른 예약자 번호로도 열린다 — 의도된 동작이다")
    void anyUserIdOpens() throws Exception {
        mvc.perform(get("/my/reservations").param("userId", "8"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/reservations/3?userId=8")));
    }

    @Test
    @DisplayName("userId를 안 주면 기본값 1로 연다")
    void defaultsToUserOne() throws Exception {
        mvc.perform(get("/my/reservations"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("userId", 1L));
    }

    // ── 경로가 상세 화면과 겹치지 않는다 ────────────────────────────────

    /**
     * {@code /reservations/lookup}이 {@code /reservations/{reservationId}}에
     * 먹히면 숫자가 아니라 400이 난다. 리터럴 패턴이 이기는 것을 못박아 둔다.
     */
    @Test
    @DisplayName("lookup 경로가 예약 상세 경로에 먹히지 않는다")
    void lookupPathWinsOverDetailPattern() throws Exception {
        mvc.perform(get("/reservations/lookup"))
                .andExpect(status().isOk())
                .andExpect(view().name("reservations/lookup"));
    }

    @Test
    @DisplayName("상세 화면은 그대로 동작한다")
    void detailStillWorks() throws Exception {
        mvc.perform(get("/reservations/1").param("userId", "7"))
                .andExpect(status().isOk())
                .andExpect(view().name("reservations/confirm"));

        Long count = jdbc.queryForObject("SELECT count(*) FROM reservation", Long.class);
        assertThat(count).isEqualTo(3);
    }
}
