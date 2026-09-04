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
import java.util.Map;

/**
 * <b>동시 요청 시연 화면.</b> 좌석 하나에 요청 N개를 한 번에 보내 전략별 차이를
 * 화면으로 보인다.
 *
 * <h2>왜 필요한가 — 손으로는 동시성을 못 보여준다</h2>
 *
 * <p>창을 둘 띄워 사람이 클릭하는 것으로는 아무것도 재현되지 않는다. 홀드
 * 트랜잭션이 밀리초라 손으로 누른 두 요청은 <b>겹치지 않고</b>, 순차 요청은
 * {@code none}에서도 깨지지 않는다. 화면으로는 "이미 잡힌 좌석은 못 잡는다"까지만
 * 보이고, 그것은 이 프로젝트가 보여주려는 것이 아니다.
 *
 * <p>이 화면은 브라우저가 {@code Promise.all}로 동시에 발사한다. 실측에서
 * {@code none}은 요청 수만큼 홀드가 생기고(6개 보내면 6건), 전략을 걸면 1건이다.
 * <b>7.6 표의 초과 홀드 열이 화면에서 재현되는 셈이다.</b>
 *
 * <h2>사용자를 전부 다르게 보낸다</h2>
 *
 * <p>{@code SeatHoldService}는 좌석보다 <b>먼저</b>
 * {@code user_session_quota (session_id, user_id)} 행을 {@code FOR UPDATE}로
 * 잠근다(concurrency-spec 1.1 — CS-6). 같은 사용자로 N개를 보내면 그 행에서
 * 전부 직렬화되어 <b>좌석 경합이 아예 일어나지 않는다.</b> 화면이 요청마다 다른
 * {@code X-User-Id}를 싣는 이유가 이것이고, 부하 시나리오가 사용자 풀을 따로
 * 두는 이유와 같다(7.3).
 *
 * <h2>시연 전용이다</h2>
 *
 * <p>{@code holdfast.demo.enabled=false}로 끌 수 있다. 아래 초기화는 한 회차의
 * 좌석·홀드·예약을 지우므로 <b>로컬 시연 밖에서 켜 두지 않는다.</b> 이 프로젝트는
 * 로컬 Docker Compose가 측정·시연 환경이고(infra-decision.md 2절) AWS 배포는
 * 잘라낸 항목이라 기본값을 켬으로 둔다.
 */
@Controller
@ConditionalOnProperty(name = "holdfast.demo.enabled", havingValue = "true", matchIfMissing = true)
public class DemoRaceController {

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
        model.addAttribute("state", state(1L, 1L));
        return "demo/race";
    }

    /**
     * 좌석 상태와 홀드 수. <b>판정은 응답이 아니라 DB에서 읽는다</b> —
     * 초과 예약을 k6가 셀 수 없는 것과 같은 이유다(concurrency-spec 7.1).
     * 브라우저가 받은 201 개수만으로는 "같은 좌석이었는지"를 알 수 없다.
     */
    @GetMapping("/demo/race/state")
    @ResponseBody
    public Map<String, Object> state(@RequestParam(defaultValue = "1") long sessionId,
                                     @RequestParam(defaultValue = "1") long seatId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("strategy", strategy);
        body.put("seatStatus", jdbc.queryForObject(
                "SELECT status FROM seat_inventory WHERE session_id = ? AND seat_id = ?",
                String.class, sessionId, seatId));
        body.put("activeHolds", jdbc.queryForObject(
                "SELECT count(*) FROM seat_hold WHERE session_id = ? AND seat_id = ? AND status = 'HELD'",
                Long.class, sessionId, seatId));
        body.put("confirmedSeats", jdbc.queryForObject(
                "SELECT count(*) FROM seat_hold WHERE session_id = ? AND seat_id = ? AND status = 'CONFIRMED'",
                Long.class, sessionId, seatId));
        // U-2가 걸려 있는지. none은 시드가 지우므로 여기서 그 사실이 드러난다(erd 3.1).
        body.put("u2Present", Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_indexes WHERE tablename = 'seat_hold'"
                        + " AND indexname = 'ux_seat_hold_active')", Boolean.class)));
        return body;
    }

    /**
     * 같은 좌석으로 다시 시연할 수 있게 되돌린다.
     *
     * <p><b>한 회차의 좌석만 건드린다.</b> 시드를 다시 돌리는 것과 달리 사용자
     * 풀과 회차 메타는 그대로 두므로, 전략을 바꾸지 않는 한 터미널로 나갈 필요가
     * 없다. 전략을 바꾸려면 앱을 다시 띄워야 하므로 그때는
     * {@code ./holdfast demo <전략>}을 쓴다.
     */
    @PostMapping("/demo/race/reset")
    @ResponseBody
    @Transactional
    public Map<String, Object> reset(@RequestParam(defaultValue = "1") long sessionId,
                                     @RequestParam(defaultValue = "1") long seatId) {
        jdbc.update("DELETE FROM reservation_seat WHERE seat_inventory_id IN"
                + " (SELECT id FROM seat_inventory WHERE session_id = ? AND seat_id = ?)",
                sessionId, seatId);
        jdbc.update("DELETE FROM reservation WHERE hold_id IN"
                + " (SELECT hold_id FROM seat_hold WHERE session_id = ? AND seat_id = ?)",
                sessionId, seatId);
        jdbc.update("DELETE FROM seat_hold WHERE session_id = ? AND seat_id = ?", sessionId, seatId);
        jdbc.update("UPDATE seat_inventory SET status = 'AVAILABLE', hold_id = NULL,"
                + " held_until = NULL WHERE session_id = ? AND seat_id = ?", sessionId, seatId);
        jdbc.update("UPDATE user_session_quota SET held_count = 0 WHERE session_id = ?", sessionId);
        return state(sessionId, seatId);
    }
}
