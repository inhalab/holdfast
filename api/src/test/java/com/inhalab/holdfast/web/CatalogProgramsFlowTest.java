package com.inhalab.holdfast.web;

import com.inhalab.holdfast.support.IdentitySequences;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>프로그램 목록이 들어가 보지 않아도 답하는지 본다.</b> 이슈 #192.
 *
 * <p>이 화면은 시스템의 진입점이고, 예전에는 카드에 이름과 설명뿐이라 매진인지
 * 오픈 전인지 알려면 눌러 봐야 했다. 카드가 스스로 답하게 만들었으므로 <b>그
 * 답이 맞는지</b>를 본다.
 *
 * <p><b>그리고 쿼리 수를 센다.</b> 프로그램마다 회차를 묻기 시작하면 이 화면은
 * 프로그램이 늘수록 느려진다 — #140이 관리자 예약 현황에서 겪은 것과 같은
 * 모양이고, 그쪽은 화면이 이미 느려진 뒤에 발견했다. 진입 화면에서 같은 일을
 * 반복하지 않도록 여기서 고정한다.
 *
 * <p>접는 규칙 자체는 {@code ProgramCardTest}가 DB 없이 본다. 이 테스트는
 * <b>실제 조회가 그 규칙에 맞는 값을 만들어 오는지</b>를 본다.
 */
@SpringBootTest(properties = {
        "holdfast.strategy=pessimistic",
        "holdfast.outbox.scheduler.enabled=false",
        // 아래 쿼리 수 테스트가 읽는 값이다. 이 속성이 없으면 통계가 0으로 남아
        // 테스트가 조용히 통과한다.
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("프로그램 목록: 카드가 스스로 답하고, 쿼리는 프로그램 수와 무관하다")
class CatalogProgramsFlowTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    /** 화면에 실제로 찍힌 "잔여 N석". 템플릿 주석의 같은 낱말과 구분하려고 숫자를 요구한다. */
    private static final Pattern SEAT_COUNT = Pattern.compile("잔여 \\d+석");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    /**
     * 프로그램 셋을 서로 다른 상태로 둔다.
     *
     * <ul>
     *   <li>1 «파는 프로그램» — 회차 둘, 하나는 자리가 있고 하나는 매진
     *   <li>2 «오픈 전» — 예약 시작 시각이 아직 안 됐다
     *   <li>3 «회차 없음» — 만들어만 두고 회차를 안 넣었다
     * </ul>
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

        jdbc.update("INSERT INTO program (id, name, description, created_at) VALUES "
                + "(1, '파는 프로그램', '설명 1', now()), "
                + "(2, '오픈 전 프로그램', '설명 2', now()), "
                + "(3, '회차 없는 프로그램', '설명 3', now())");

        jdbc.update("INSERT INTO seat_layout (id, name, created_at) VALUES (1, '배치도', now())");
        jdbc.update("INSERT INTO zone (id, seat_layout_id, name, sort_order) VALUES (1, 1, 'A구역', 1)");
        jdbc.update("""
                INSERT INTO seat (id, zone_id, seat_no, row_index, col_index)
                SELECT g, 1, 'A-' || g, 1, g
                FROM generate_series(1, 6) AS g
                """);

        // 회차 1: 3시간 뒤, 예약 열림. 회차 2: 1시간 뒤(더 이르다), 매진시킬 것.
        jdbc.update("""
                INSERT INTO event_session (id, program_id, seat_layout_id, starts_at, ends_at,
                                           entry_opens_at, entry_closes_at, reserve_opens_at,
                                           max_per_user, status)
                VALUES (1, 1, 1, now() + interval '3 hours', now() + interval '5 hours',
                        now(), now() + interval '5 hours', now() - interval '1 hour', 4, 'OPEN'),
                       (2, 1, 1, now() + interval '1 hour', now() + interval '2 hours',
                        now(), now() + interval '2 hours', now() - interval '1 hour', 4, 'OPEN'),
                       (3, 2, 1, now() + interval '9 hours', now() + interval '10 hours',
                        now() + interval '8 hours', now() + interval '10 hours',
                        now() + interval '8 hours', 4, 'SCHEDULED')
                """);

        // 회차 1은 6석 전부 남았고, 회차 2는 전부 팔렸다(매진), 회차 3은 6석.
        jdbc.update("INSERT INTO seat_inventory (session_id, seat_id, status, version) "
                + "SELECT 1, s.id, 'AVAILABLE', 0 FROM seat s");
        jdbc.update("INSERT INTO seat_inventory (session_id, seat_id, status, version) "
                + "SELECT 2, s.id, 'SOLD', 0 FROM seat s");
        jdbc.update("INSERT INTO seat_inventory (session_id, seat_id, status, version) "
                + "SELECT 3, s.id, 'AVAILABLE', 0 FROM seat s");

        IdentitySequences.resync(jdbc);
    }

    @Test
    @DisplayName("파는 회차가 하나라도 있으면 판매중이고, 잔여는 그 회차의 것만 센다")
    void 판매중인_프로그램은_잔여를_보여준다() throws Exception {
        String html = body();

        // 회차 1(6석 AVAILABLE)만 세고 매진된 회차 2는 빼야 6이다.
        assertThat(html).contains("판매중").contains("잔여 6석").contains("회차 2개");
    }

    @Test
    @DisplayName("다음 회차는 더 이른 쪽이다 — 매진이어도 시각은 보여준다")
    void 다음_회차는_더_이른_쪽이다() throws Exception {
        // 회차 2가 1시간 뒤로 더 이르고 매진이다. 그래도 «다음 회차»는 그것이다.
        String next = jdbc.queryForObject(
                "SELECT to_char(starts_at AT TIME ZONE 'Asia/Seoul', 'MM/DD HH24:MI') "
                        + "FROM event_session WHERE id = 2", String.class);

        assertThat(body()).contains("다음 회차").contains(next);
    }

    @Test
    @DisplayName("예약 시작 전인 프로그램은 «오픈 전»이고 잔여를 적지 않는다")
    void 오픈_전_프로그램은_잔여를_숨긴다() throws Exception {
        String html = body();

        assertThat(html).contains("오픈 전");

        // 회차 3에도 6석이 남아 있지만 살 수 없다 — 그 숫자를 적으면 거짓말이 된다.
        // **찍힌 숫자만 센다.** 이 화면의 템플릿 주석에도 "잔여"라는 낱말이 있어
        // 맨 문자열로 세면 주석까지 세게 된다.
        assertThat(SEAT_COUNT.matcher(html).results().count())
                .as("잔여 석을 적는 카드는 판매중인 하나뿐이어야 한다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("회차가 없는 프로그램은 그렇다고 말한다")
    void 회차가_없으면_그렇게_적는다() throws Exception {
        assertThat(body()).contains("등록된 회차가 없습니다.");
    }

    /**
     * <b>쿼리는 셋이다.</b> 프로그램 전체 → 그 프로그램들의 회차 전체 →
     * 회차들의 좌석 집계.
     *
     * <p>프로그램이 셋이든 서른이든 같아야 한다. 프로그램마다 회차를 물으면
     * 3 + N이 되고, 회차마다 좌석을 물으면 거기서 또 늘어난다.
     */
    @Test
    @DisplayName("쿼리 수가 프로그램 수에 비례하지 않는다 (#140의 N+1을 진입 화면에 만들지 않는다)")
    void 쿼리는_프로그램_수와_무관하다() throws Exception {
        long withThree = statementsWhileLoadingPrograms();

        // 프로그램을 넷 더 넣는다. 회차는 그대로다 — 늘어나는 것은 바깥 반복뿐이다.
        jdbc.update("INSERT INTO program (id, name, created_at) VALUES "
                + "(4, 'ㄱ', now()), (5, 'ㄴ', now()), (6, 'ㄷ', now()), (7, 'ㄹ', now())");
        IdentitySequences.resync(jdbc);

        long withSeven = statementsWhileLoadingPrograms();

        assertThat(withThree).isEqualTo(3);
        assertThat(withSeven)
                .as("프로그램이 3개에서 7개가 됐는데 쿼리가 늘었다면 프로그램마다 묻고 있는 것이다")
                .isEqualTo(withThree);
    }

    private long statementsWhileLoadingPrograms() throws Exception {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        mvc.perform(get("/programs")).andExpect(status().isOk());
        return stats.getPrepareStatementCount();
    }

    private String body() throws Exception {
        return mvc.perform(get("/programs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andReturn().getResponse().getContentAsString();
    }
}
