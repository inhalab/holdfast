package com.inhalab.holdfast.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>동시 요청 시연 화면.</b> 좌석에 요청 N개를 한 번에 보내 전략별 차이를
 * 화면으로 보인다.
 *
 * <h2>왜 필요한가 — 손으로는 동시성을 못 보여준다</h2>
 *
 * <p>창을 둘 띄워 사람이 클릭하는 것으로는 아무것도 재현되지 않는다. 홀드
 * 트랜잭션이 밀리초라 손으로 누른 두 요청은 <b>겹치지 않고</b>, 순차 요청은
 * {@code none}에서도 깨지지 않는다.
 *
 * <p>이 화면은 브라우저가 {@code Promise.all}로 동시에 발사한다. 실측에서
 * {@code none}은 요청 수만큼 홀드가 생기고(6개 보내면 6건), 전략을 걸면 좌석당
 * 1건이다. <b>7.6 표의 초과 홀드 열이 화면에서 재현되는 셈이다.</b>
 *
 * <h2>사용자를 전부 다르게 보낸다</h2>
 *
 * <p>{@code SeatHoldService}는 좌석보다 <b>먼저</b>
 * {@code user_session_quota (session_id, user_id)} 행을 {@code FOR UPDATE}로
 * 잠근다(concurrency-spec 1.1 — CS-6). 같은 사용자로 N개를 보내면 그 행에서
 * 전부 직렬화되어 <b>좌석 경합이 아예 일어나지 않는다.</b> 화면이 요청마다 다른
 * {@code X-User-Id}를 싣는 이유가 이것이다.
 *
 * <h2>전략은 여기서 바꾸지 않는다</h2>
 *
 * <p>전략 빈은 {@code @ConditionalOnProperty}로 기동 시 하나만 만들어지고
 * {@code SeatHoldService}·{@code ReservationService}에 직접 주입된다.
 * <b>런타임 교체는 그 두 클래스의 생성자를 바꾸는 일이고, 둘 다 측정 경로다.</b>
 * 시연 편의를 위해 M3가 잰 코드를 건드리지 않는다 — 전략 전환은
 * {@code ./holdfast strategy <이름>}이 앱을 다시 띄워서 한다(약 13초).
 * 이 화면은 전략이 바뀌는 것을 <b>감지해서</b> 표시만 한다.
 *
 * <h2>시연 전용이다</h2>
 *
 * <p>{@code holdfast.demo.enabled=false}로 끌 수 있다. 아래 초기화와 좌석 수
 * 변경은 한 회차의 데이터를 지우므로 <b>로컬 시연 밖에서 켜 두지 않는다.</b>
 * 이 프로젝트는 로컬 Docker Compose가 측정·시연 환경이고(infra-decision.md 2절)
 * AWS 배포는 잘라낸 항목이라 기본값을 켬으로 둔다.
 */
@Controller
@ConditionalOnProperty(name = "holdfast.demo.enabled", havingValue = "true", matchIfMissing = true)
public class DemoRaceController {

    /** 시연 회차. 시드가 만든 1번을 쓴다. */
    private static final long SESSION_ID = 1L;
    /** 좌석 수 상한. 화면에서 늘릴 수 있는 범위를 좁게 둔다 — 시연용이다. */
    private static final int MAX_SEATS = 10;

    private final JdbcTemplate jdbc;

    /** 지금 앱이 어느 전략으로 떠 있는지. 화면 상단에 그대로 보여준다. */
    @Value("${holdfast.strategy}")
    private String strategy;

    public DemoRaceController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/demo/race")
    public String page(Model model) {
        model.addAttribute("strategy", strategy);
        model.addAttribute("state", state());
        return "demo/race";
    }

    /**
     * 좌석별 상태와 홀드 수. <b>판정은 응답이 아니라 DB에서 읽는다</b> —
     * 초과 예약을 k6가 셀 수 없는 것과 같은 이유다(concurrency-spec 7.1).
     * 브라우저가 받은 201 개수만으로는 "같은 좌석이었는지"를 알 수 없다.
     */
    @GetMapping("/demo/race/state")
    @ResponseBody
    public Map<String, Object> state() {
        List<Map<String, Object>> seats = jdbc.queryForList("""
                SELECT si.seat_id AS "seatId",
                       si.status,
                       (SELECT count(*) FROM seat_hold sh
                         WHERE sh.session_id = si.session_id AND sh.seat_id = si.seat_id
                           AND sh.status = 'HELD') AS "activeHolds"
                  FROM seat_inventory si
                 WHERE si.session_id = ?
                 ORDER BY si.seat_id
                """, SESSION_ID);

        long totalHolds = seats.stream().mapToLong(s -> ((Number) s.get("activeHolds")).longValue()).sum();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("strategy", strategy);
        body.put("seats", seats);
        body.put("seatCount", seats.size());
        body.put("totalActiveHolds", totalHolds);
        // 초과 홀드는 "좌석 하나에 활성 홀드가 둘 이상"이다(7.6.2 V-2).
        body.put("seatsWithExcess", seats.stream()
                .filter(s -> ((Number) s.get("activeHolds")).longValue() > 1).count());
        // U-2가 걸려 있는지. none은 시드가 지우므로 여기서 그 사실이 드러난다(erd 3.1).
        body.put("u2Present", Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_indexes WHERE tablename = 'seat_hold'"
                        + " AND indexname = 'ux_seat_hold_active')", Boolean.class)));
        return body;
    }

    /**
     * 같은 좌석으로 다시 시연할 수 있게 되돌린다.
     *
     * <p><b>발사할 때 자동으로 되돌리지 않는다.</b> 그러면 "발사할 때마다
     * 초기화된다"는 인상을 주는데 실제 시스템은 그렇지 않다 — 이미 팔린 좌석은
     * 그대로 팔려 있다. 대신 화면이 <b>발사 직전과 직후 상태를 나란히</b> 보여줘,
     * 되돌리지 않고 다시 쏘면 "전부 409, 홀드는 그대로"라는 것이 그 자리에서
     * 읽히게 했다.
     */
    @PostMapping("/demo/race/reset")
    @ResponseBody
    @Transactional
    public Map<String, Object> reset() {
        clearSession();
        return state();
    }

    /**
     * 좌석 수를 바꾼다. <b>시연 중에 터미널로 나가지 않기 위한 것이다.</b>
     *
     * <p>좌석이 하나면 "여럿이 한 좌석을 노린다"까지만 보인다. 좌석이 여럿이면
     * 요청이 흩어지므로, {@code none}에서 <b>좌석마다</b> 초과 홀드가 생기는 것과
     * 전략을 걸면 <b>좌석마다 정확히 하나</b>가 되는 것을 함께 보여줄 수 있다.
     *
     * <p>시드 스크립트를 다시 돌리는 대신 이 회차의 좌석만 만든다 — 사용자 풀과
     * 회차 메타는 그대로 두므로 홀드가 바로 된다.
     */
    @PostMapping("/demo/race/seats")
    @ResponseBody
    @Transactional
    public Map<String, Object> seats(@RequestParam int count) {
        int n = Math.max(1, Math.min(MAX_SEATS, count));
        clearSession();
        jdbc.update("DELETE FROM seat_inventory WHERE session_id = ?", SESSION_ID);

        for (long seatId = 1; seatId <= n; seatId++) {
            // seat는 배치도에 속한 정적 데이터라 없을 때만 만든다.
            jdbc.update("""
                    INSERT INTO seat (id, zone_id, seat_no, row_index, col_index)
                    SELECT ?, 1, 'A-' || ?, 1, ?
                     WHERE NOT EXISTS (SELECT 1 FROM seat WHERE id = ?)
                    """, seatId, seatId, seatId, seatId);
            jdbc.update("""
                    INSERT INTO seat_inventory (session_id, seat_id, status, version)
                    VALUES (?, ?, 'AVAILABLE', 0)
                    """, SESSION_ID, seatId);
        }
        return state();
    }

    /** 이 회차의 홀드·예약을 지우고 재고를 되돌린다. 좌석 자체는 남긴다. */
    private void clearSession() {
        jdbc.update("DELETE FROM reservation_seat WHERE seat_inventory_id IN"
                + " (SELECT id FROM seat_inventory WHERE session_id = ?)", SESSION_ID);
        jdbc.update("DELETE FROM reservation WHERE session_id = ?", SESSION_ID);
        jdbc.update("DELETE FROM seat_hold WHERE session_id = ?", SESSION_ID);
        jdbc.update("UPDATE seat_inventory SET status = 'AVAILABLE', hold_id = NULL,"
                + " held_until = NULL WHERE session_id = ?", SESSION_ID);
        jdbc.update("UPDATE user_session_quota SET held_count = 0 WHERE session_id = ?", SESSION_ID);
    }
}
