package com.inhalab.holdfast.web;

import com.inhalab.holdfast.admin.AdminReservationRepository;
import com.inhalab.holdfast.admin.AdminReservationRow;
import com.inhalab.holdfast.admin.AdminSeatSummaryRepository;
import com.inhalab.holdfast.admin.AdminSessionStatsRepository;
import com.inhalab.holdfast.admin.SessionCountRow;
import com.inhalab.holdfast.admin.SessionStats;
import com.inhalab.holdfast.reservation.Reservation;
import com.inhalab.holdfast.reservation.ReservationService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 관리자 예약 현황 조회. 이슈 #82 — M4 최소 완결. **조회만** 한다. 회차·좌석배치
 * 등록은 이슈가 명시적으로 여유로 내렸다 — 시드 스크립트(load-test/sql,
 * infra/demo-seed.sql)가 대신한다.
 *
 * <p>인증이 없어(design-spec 요구사항 추적표 밖) 이 경로도 다른 페이지와 같은
 * 방식으로 노출된다 — 접근 제어는 이 프로젝트의 범위 밖이다.
 */
@Controller
public class AdminPageController {

    private final AdminReservationRepository adminReservationRepository;
    private final AdminSeatSummaryRepository adminSeatSummaryRepository;
    private final AdminSessionStatsRepository adminSessionStatsRepository;
    private final ReservationService reservationService;

    public AdminPageController(AdminReservationRepository adminReservationRepository,
                               AdminSeatSummaryRepository adminSeatSummaryRepository,
                               AdminSessionStatsRepository adminSessionStatsRepository,
                               ReservationService reservationService) {
        this.adminReservationRepository = adminReservationRepository;
        this.adminSeatSummaryRepository = adminSeatSummaryRepository;
        this.adminSessionStatsRepository = adminSessionStatsRepository;
        this.reservationService = reservationService;
    }

    @GetMapping("/admin/reservations")
    public String reservations(Model model) {
        List<Reservation> reservations = adminReservationRepository.findTop200ByOrderByIdDesc();

        // seatsOf()는 ReservationService(#79와 같은 재사용 경계)가 이미 공개한
        // 메서드다 — reservation/을 고치지 않는다. 관리자 화면이라 N+1을
        // 받아들인다(design-spec 5.3, 이 이슈의 "조회만" 축소 범위와 같은 판단).
        List<AdminReservationRow> rows = reservations.stream()
                .map(r -> new AdminReservationRow(
                        r.getId(), r.getSessionId(), r.getUserId(), r.getStatus(),
                        r.getTotalAmount(), r.getCreatedAt(), r.getConfirmedAt(), r.getCancelledAt(),
                        reservationService.seatsOf(r.getId())))
                .toList();

        model.addAttribute("reservations", rows);
        model.addAttribute("sessionStats", sessionStats());
        return "admin/reservations";
    }

    /**
     * 회차별 예약 현황 통계. 이슈 #104 — SFR-002의 통계 축.
     *
     * <p><b>쿼리 넷을 회차 전부에 대해 한 번씩만 돌린다.</b> 회차마다 세면
     * 회차가 늘수록 이 화면이 느려지고, <b>#105가 곧 이 화면의 응답시간을
     * 잰다</b>(PER-002). #127이 총 좌석 수를 한 쿼리로 가져와 id로 맞춘 것과 같은
     * 형태다.
     *
     * <p><b>기준 시각을 여기서 한 번만 잡는다.</b> 만료 판정이 쿼리마다 다른
     * 시각을 쓰면 같은 화면 안에서 숫자가 어긋난다.
     */
    private Map<Long, SessionStats> sessionStats() {
        Instant now = Instant.now();

        Map<Long, Map<String, Long>> seats = group(adminSeatSummaryRepository.summarize().stream()
                .map(r -> new SessionCountRow(r.sessionId(), r.status(), r.count()))
                .toList());
        Map<Long, Map<String, Long>> reservations = group(adminSessionStatsRepository.reservationCounts());
        Map<Long, Map<String, Long>> tickets = group(adminSessionStatsRepository.ticketCounts());
        Map<Long, Map<String, Long>> expired = group(adminSessionStatsRepository.expiredHoldCounts(now));

        // 회차 하나라도 등장하면 줄을 만든다. 좌석은 있는데 예약이 없는 회차가
        // 목록에서 빠지면 "0석 팔린 회차"를 볼 수 없다(design-spec 5.6 —
        // 감추지 않고 상태를 적는다).
        Map<Long, SessionStats> stats = new TreeMap<>();
        for (Long sessionId : union(seats, reservations, tickets, expired)) {
            Map<String, Long> s = seats.getOrDefault(sessionId, Map.of());
            Map<String, Long> r = reservations.getOrDefault(sessionId, Map.of());
            Map<String, Long> t = tickets.getOrDefault(sessionId, Map.of());

            long available = count(s, "AVAILABLE");
            long held = count(s, "HELD");
            long sold = count(s, "SOLD");
            long issued = count(t, "ISSUED");
            long used = count(t, "USED");

            stats.put(sessionId, new SessionStats(
                    available + held + sold,
                    available, held, sold,
                    count(expired.getOrDefault(sessionId, Map.of()), "EXPIRED"),
                    count(r, "HELD"), count(r, "CONFIRMED"),
                    count(r, "CANCELLED"), count(r, "EXPIRED"),
                    issued + used, used));
        }
        return stats;
    }

    private static Map<Long, Map<String, Long>> group(List<SessionCountRow> rows) {
        Map<Long, Map<String, Long>> grouped = new TreeMap<>();
        for (SessionCountRow row : rows) {
            grouped.computeIfAbsent(row.sessionId(), k -> new LinkedHashMap<>())
                    .put(row.key(), row.count());
        }
        return grouped;
    }

    @SafeVarargs
    private static Set<Long> union(Map<Long, ?>... maps) {
        Set<Long> ids = new TreeSet<>();
        for (Map<Long, ?> m : maps) {
            ids.addAll(m.keySet());
        }
        return ids;
    }

    private static long count(Map<String, Long> counts, String key) {
        Long n = counts.get(key);
        return n == null ? 0L : n;
    }
}
