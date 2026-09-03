package com.inhalab.holdfast.web;

import com.inhalab.holdfast.admin.AdminReservationRepository;
import com.inhalab.holdfast.admin.AdminReservationRow;
import com.inhalab.holdfast.admin.AdminSeatSummaryRepository;
import com.inhalab.holdfast.admin.SeatSummaryRow;
import com.inhalab.holdfast.reservation.Reservation;
import com.inhalab.holdfast.reservation.ReservationService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
    private final ReservationService reservationService;

    public AdminPageController(AdminReservationRepository adminReservationRepository,
                               AdminSeatSummaryRepository adminSeatSummaryRepository,
                               ReservationService reservationService) {
        this.adminReservationRepository = adminReservationRepository;
        this.adminSeatSummaryRepository = adminSeatSummaryRepository;
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

        // 회차 → (상태 → 개수). TreeMap으로 회차 순서를, LinkedHashMap으로
        // AVAILABLE/HELD/SOLD 정렬(리포지토리 쿼리의 ORDER BY)을 그대로 살린다.
        Map<Long, Map<String, Long>> seatSummary = new TreeMap<>();
        for (SeatSummaryRow row : adminSeatSummaryRepository.summarize()) {
            seatSummary.computeIfAbsent(row.sessionId(), k -> new LinkedHashMap<>())
                    .put(row.status(), row.count());
        }

        model.addAttribute("reservations", rows);
        model.addAttribute("seatSummary", seatSummary);
        return "admin/reservations";
    }
}
