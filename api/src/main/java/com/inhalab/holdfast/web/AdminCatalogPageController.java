package com.inhalab.holdfast.web;

import com.inhalab.holdfast.admin.AdminCatalogService;
import com.inhalab.holdfast.admin.AdminSeatLayoutRepository;
import com.inhalab.holdfast.admin.SessionFormRow;
import com.inhalab.holdfast.catalog.CatalogProgramRepository;
import com.inhalab.holdfast.catalog.CatalogSessionRepository;
import com.inhalab.holdfast.seat.Program;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 관리자 프로그램·회차 등록. 이슈 #101 — SFR-005.
 *
 * <p>지금까지 회차는 시드 SQL로만 만들 수 있었다. 발주처 관점에서 그것은
 * "운영할 수 있는 시스템"이 아니다.
 *
 * <p><b>인증이 없어 이 경로는 누구나 열 수 있다.</b> 그 사실은 {@code scope-m4.md}
 * 8절에 이미 기록돼 있고 이 이슈에서 바꾸지 않는다(#101 주의사항).
 *
 * <p>좌석배치도(구역·좌석) 등록은 별도 이슈(#102)다. 여기서는 이미 있는 배치도를
 * 고르기만 한다.
 */
@Controller
public class AdminCatalogPageController {

    /**
     * {@code datetime-local} 입력에는 시간대가 없다. 앱은 UTC로 도는데 운영자는
     * 한국 시각으로 입력하므로, 화면에서 오간 값을 한국 시각으로 해석한다.
     * 이 기준을 바꾸면 이미 등록된 회차의 표시 시각이 통째로 어긋난다.
     */
    private static final ZoneId FORM_ZONE = ZoneId.of("Asia/Seoul");

    /** {@code datetime-local}이 받아들이는 형식. 초·시간대를 붙이면 입력이 비어 보인다. */
    private static final DateTimeFormatter FORM_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final CatalogProgramRepository programRepository;
    private final CatalogSessionRepository sessionRepository;
    private final AdminSeatLayoutRepository seatLayoutRepository;
    private final AdminCatalogService catalogService;

    public AdminCatalogPageController(CatalogProgramRepository programRepository,
                                      CatalogSessionRepository sessionRepository,
                                      AdminSeatLayoutRepository seatLayoutRepository,
                                      AdminCatalogService catalogService) {
        this.programRepository = programRepository;
        this.sessionRepository = sessionRepository;
        this.seatLayoutRepository = seatLayoutRepository;
        this.catalogService = catalogService;
    }

    /** 프로그램 목록 + 등록 폼. */
    @GetMapping("/admin/programs")
    public String programs(Model model) {
        model.addAttribute("programs", programRepository.findAllByOrderByIdAsc());
        return "admin/programs";
    }

    @PostMapping("/admin/programs")
    public String createProgram(@RequestParam String name,
                                @RequestParam(required = false) String description) {
        catalogService.createProgram(name, description);
        return "redirect:/admin/programs";
    }

    /** 프로그램 상세 — 수정 폼 + 회차 목록 + 회차 등록 폼. */
    @GetMapping("/admin/programs/{programId}")
    public String program(@PathVariable long programId, Model model) {
        Program program = programRepository.findById(programId)
                .orElseThrow(() -> new IllegalArgumentException("프로그램을 찾을 수 없습니다: " + programId));

        List<SessionFormRow> sessions = sessionRepository.findByProgramIdOrderByStartsAtAsc(programId)
                .stream()
                .map(s -> new SessionFormRow(
                        s.getId(), s.getSeatLayoutId(),
                        toFormValue(s.getStartsAt()), toFormValue(s.getEndsAt()),
                        toFormValue(s.getEntryOpensAt()), toFormValue(s.getEntryClosesAt()),
                        toFormValue(s.getReserveOpensAt()),
                        s.getMaxPerUser(), s.getStatus()))
                .toList();

        model.addAttribute("program", program);
        model.addAttribute("sessions", sessions);
        model.addAttribute("layouts", seatLayoutRepository.options());
        model.addAttribute("zone", FORM_ZONE.getId());
        return "admin/program-detail";
    }

    @PostMapping("/admin/programs/{programId}")
    public String updateProgram(@PathVariable long programId,
                                @RequestParam String name,
                                @RequestParam(required = false) String description) {
        catalogService.updateProgram(programId, name, description);
        return "redirect:/admin/programs/" + programId;
    }

    @PostMapping("/admin/programs/{programId}/sessions")
    public String createSession(@PathVariable long programId,
                                @RequestParam long seatLayoutId,
                                @RequestParam String startsAt,
                                @RequestParam String endsAt,
                                @RequestParam String entryOpensAt,
                                @RequestParam String entryClosesAt,
                                @RequestParam String reserveOpensAt,
                                @RequestParam int maxPerUser,
                                @RequestParam String status,
                                @RequestParam(defaultValue = "100") int userPoolSize) {
        catalogService.createSession(programId, seatLayoutId,
                toInstant(startsAt), toInstant(endsAt),
                toInstant(entryOpensAt), toInstant(entryClosesAt), toInstant(reserveOpensAt),
                maxPerUser, status, userPoolSize);
        return "redirect:/admin/programs/" + programId;
    }

    @PostMapping("/admin/sessions/{sessionId}")
    public String updateSession(@PathVariable long sessionId,
                                @RequestParam long programId,
                                @RequestParam String startsAt,
                                @RequestParam String endsAt,
                                @RequestParam String entryOpensAt,
                                @RequestParam String entryClosesAt,
                                @RequestParam String reserveOpensAt,
                                @RequestParam int maxPerUser,
                                @RequestParam String status) {
        catalogService.updateSession(sessionId,
                toInstant(startsAt), toInstant(endsAt),
                toInstant(entryOpensAt), toInstant(entryClosesAt), toInstant(reserveOpensAt),
                maxPerUser, status);
        return "redirect:/admin/programs/" + programId;
    }

    /** {@code 2026-09-04T14:30} → 한국 시각으로 해석한 {@link Instant}. */
    private Instant toInstant(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(FORM_ZONE).toInstant();
    }

    /** {@link Instant} → {@code datetime-local} 입력에 넣을 한국 시각 문자열. */
    private String toFormValue(Instant instant) {
        return instant == null ? "" : FORM_FORMAT.format(instant.atZone(FORM_ZONE));
    }

    /** 잘못된 입력·없는 프로그램. 다른 페이지 컨트롤러와 같이 평문으로 답한다. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body(ex.getMessage());
    }
}
