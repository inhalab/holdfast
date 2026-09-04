package com.inhalab.holdfast.web;

import com.inhalab.holdfast.admin.AdminSeatLayoutRepository;
import com.inhalab.holdfast.admin.AdminSeatLayoutService;
import com.inhalab.holdfast.admin.LayoutRow;
import com.inhalab.holdfast.admin.SeatRow;
import com.inhalab.holdfast.seat.SeatLayout;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 관리자 좌석배치도 등록. 이슈 #102 — SFR-005.
 *
 * <p>회차 등록(#101)이 배치도를 참조하는데 그 배치도를 만들 화면이 없었다.
 * 이 화면이 {@code seat_layout}·{@code zone}·{@code seat}를 만든다.
 *
 * <p><b>인증이 없어 이 경로는 누구나 열 수 있다.</b> {@code scope-m4.md} 8절에
 * 기록된 그대로이며 이 이슈에서 바꾸지 않는다 —
 * {@link AdminCatalogPageController}와 같은 조건이다.
 *
 * <h2>실패는 리다이렉트하지 않는다</h2>
 *
 * <p>이유는 {@link AdminCatalogPageController#detailWithError}에 적힌 것과
 * 같다 — 앱이 2대라 플래시(HTTP 세션)가 POST를 받은 인스턴스에만 남는다.
 * 검증 실패는 그 응답에서 바로 그린다.
 */
@Controller
public class AdminSeatLayoutPageController {

    private final AdminSeatLayoutRepository layoutRepository;
    private final AdminSeatLayoutService layoutService;

    public AdminSeatLayoutPageController(AdminSeatLayoutRepository layoutRepository,
                                         AdminSeatLayoutService layoutService) {
        this.layoutRepository = layoutRepository;
        this.layoutService = layoutService;
    }

    // ── 배치도 목록 ─────────────────────────────────────────────────────

    @GetMapping("/admin/layouts")
    public String layouts(Model model) {
        model.addAttribute("layouts", layoutRepository.rows());
        return "admin/layouts";
    }

    @PostMapping("/admin/layouts")
    public String createLayout(@RequestParam String name, Model model) {
        long layoutId;
        try {
            layoutId = layoutService.createLayout(name);
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            return layouts(model);
        }
        // 만든 직후에 할 일은 구역 등록이다. 목록으로 돌려보내면 방금 만든 것을
        // 다시 찾아 눌러야 한다.
        return "redirect:/admin/layouts/" + layoutId;
    }

    // ── 배치도 상세 ─────────────────────────────────────────────────────

    @GetMapping("/admin/layouts/{layoutId}")
    public String layout(@PathVariable long layoutId, Model model) {
        SeatLayout layout = layoutRepository.findById(layoutId)
                .orElseThrow(() -> new IllegalArgumentException("배치도를 찾을 수 없습니다: " + layoutId));

        LayoutRow row = layoutRepository.rows().stream()
                .filter(r -> r.id() == layoutId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("배치도 집계를 찾지 못했다: " + layoutId));

        // 좌석은 배치도 단위로 한 번 읽고 구역별로 가른다. 구역마다 묻지 않는다.
        Map<Long, List<SeatRow>> seatsByZone = layoutRepository.seatsOf(layoutId).stream()
                .collect(Collectors.groupingBy(SeatRow::zoneId));

        model.addAttribute("layout", layout);
        model.addAttribute("summary", row);
        model.addAttribute("zones", layoutRepository.zonesOf(layoutId));
        model.addAttribute("seatsByZone", seatsByZone);
        model.addAttribute("maxSeatsPerGrid", AdminSeatLayoutService.MAX_SEATS_PER_GRID);
        return "admin/layout-detail";
    }

    /** 검증 실패를 폼과 함께 다시 그린다. 클래스 주석의 "실패는 리다이렉트하지 않는다". */
    private String layoutWithError(long layoutId, String message, Model model) {
        model.addAttribute("error", message);
        return layout(layoutId, model);
    }

    @PostMapping("/admin/layouts/{layoutId}")
    public String updateLayout(@PathVariable long layoutId,
                               @RequestParam String name,
                               Model model) {
        try {
            layoutService.updateLayout(layoutId, name);
        } catch (IllegalArgumentException e) {
            return layoutWithError(layoutId, e.getMessage(), model);
        }
        return "redirect:/admin/layouts/" + layoutId;
    }

    @PostMapping("/admin/layouts/{layoutId}/delete")
    public String deleteLayout(@PathVariable long layoutId, Model model) {
        try {
            layoutService.deleteLayout(layoutId);
        } catch (IllegalArgumentException e) {
            return layoutWithError(layoutId, e.getMessage(), model);
        }
        return "redirect:/admin/layouts";
    }

    // ── 구역 ────────────────────────────────────────────────────────────

    /**
     * 구역 등록. <b>받는 것은 이름·행·열 셋뿐이다.</b> 정렬 순서는 만든 차례로
     * 붙고({@link AdminSeatLayoutService#createZone}), 좌석번호 접두어는 화면의
     * "고급"을 펴야 나온다 — 비우면 구역 이름에서 만든다.
     */
    @PostMapping("/admin/layouts/{layoutId}/zones")
    public String createZone(@PathVariable long layoutId,
                             @RequestParam String name,
                             @RequestParam int rows,
                             @RequestParam int cols,
                             @RequestParam(required = false) String seatNoPrefix,
                             Model model) {
        try {
            layoutService.createZone(layoutId, name, rows, cols, seatNoPrefix);
        } catch (IllegalArgumentException e) {
            return layoutWithError(layoutId, e.getMessage(), model);
        }
        return "redirect:/admin/layouts/" + layoutId;
    }

    @PostMapping("/admin/zones/{zoneId}")
    public String updateZone(@PathVariable long zoneId,
                             @RequestParam long layoutId,
                             @RequestParam String name,
                             Model model) {
        try {
            layoutService.updateZone(zoneId, name);
        } catch (IllegalArgumentException e) {
            return layoutWithError(layoutId, e.getMessage(), model);
        }
        return "redirect:/admin/layouts/" + layoutId;
    }

    /**
     * 구역을 위/아래로 한 칸 옮긴다. 정렬 순서를 숫자로 받던 자리를 대신한다 —
     * "작을수록 위"라는 규칙을 읽지 않아도 되는 것이 이 화면이 얻는 전부다.
     */
    @PostMapping("/admin/zones/{zoneId}/move")
    public String moveZone(@PathVariable long zoneId,
                           @RequestParam long layoutId,
                           @RequestParam String direction,
                           Model model) {
        try {
            layoutService.moveZone(zoneId, "up".equals(direction));
        } catch (IllegalArgumentException e) {
            return layoutWithError(layoutId, e.getMessage(), model);
        }
        return "redirect:/admin/layouts/" + layoutId;
    }

    @PostMapping("/admin/zones/{zoneId}/delete")
    public String deleteZone(@PathVariable long zoneId,
                             @RequestParam long layoutId,
                             Model model) {
        try {
            layoutService.deleteZone(zoneId);
        } catch (IllegalArgumentException e) {
            return layoutWithError(layoutId, e.getMessage(), model);
        }
        return "redirect:/admin/layouts/" + layoutId;
    }

    // ── 좌석 ────────────────────────────────────────────────────────────

    /**
     * 구역 맨 아래에 행을 이어 붙인다. <b>받는 것은 행 수 하나뿐이다</b> — 열 수와
     * 좌석번호는 그 구역에 이미 있는 좌석에서 읽는다
     * ({@link AdminSeatLayoutService#addSeatRows}).
     */
    @PostMapping("/admin/zones/{zoneId}/rows")
    public String addSeatRows(@PathVariable long zoneId,
                              @RequestParam long layoutId,
                              @RequestParam int rows,
                              Model model) {
        try {
            layoutService.addSeatRows(zoneId, rows);
        } catch (IllegalArgumentException e) {
            return layoutWithError(layoutId, e.getMessage(), model);
        }
        return "redirect:/admin/layouts/" + layoutId;
    }

    /**
     * 좌석 한 칸 삭제. <b>{@code seatId}를 경로가 아니라 폼 값으로 받는다</b> —
     * 격자 미리보기가 구역마다 폼 하나에 좌석 수만큼 버튼을 담고, 눌린 버튼의
     * 값만 전송되는 HTML 규칙을 그대로 쓴다. 좌석마다 폼을 하나씩 두면 50석
     * 구역에 폼이 50개가 된다.
     */
    @PostMapping("/admin/seats/delete")
    public String deleteSeat(@RequestParam long seatId,
                             @RequestParam long layoutId,
                             Model model) {
        try {
            layoutService.deleteSeat(seatId);
        } catch (IllegalArgumentException e) {
            return layoutWithError(layoutId, e.getMessage(), model);
        }
        return "redirect:/admin/layouts/" + layoutId;
    }

    /**
     * 없는 배치도·구역(GET). 등록·수정 실패는 여기로 오지 않는다 — 각 POST가
     * 직접 잡아 폼 위에 띄운다. 이유는 {@link AdminCatalogPageController}와 같다.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(404).contentType(MediaType.TEXT_PLAIN).body(ex.getMessage());
    }
}
