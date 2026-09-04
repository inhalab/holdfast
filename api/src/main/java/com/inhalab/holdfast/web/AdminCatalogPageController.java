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

    /**
     * <b>실패는 리다이렉트하지 않고 그 자리에서 다시 그린다.</b>
     *
     * <p>처음에는 {@code RedirectAttributes}로 플래시 메시지를 실었는데
     * <b>화면에 아무것도 뜨지 않았다.</b> 플래시는 HTTP 세션에 담기고, 앱이 2대라
     * POST를 받은 인스턴스와 리다이렉트된 GET을 받는 인스턴스가 다르다. 앱
     * 하나에 직접 붙이면 뜨고 nginx를 거치면 안 뜬다 — 실측으로 갈렸다.
     *
     * <p>고치는 방법이 셋 있었는데 둘은 값이 크다. <b>sticky session</b>은 nginx의
     * 분배 방식을 바꾸는 일이라 측정 고정 변수를 건드린다(concurrency-spec 7.3).
     * <b>공유 세션 저장소</b>는 배너 하나에 Spring Session과 Redis 왕복을 더한다.
     *
     * <p>남은 하나가 <b>세션을 아예 쓰지 않는 것</b>이다. 검증 실패는 그 응답에서
     * 바로 그리면 되고, 그러면 운영자가 입력하던 폼도 잃지 않는다. 성공은 지금처럼
     * 리다이렉트한다(PRG) — 보여줄 메시지가 없고 결과는 목록에 그대로 보인다.
     */
    private String detailWithError(long programId, String message, Model model) {
        model.addAttribute("error", message);
        return program(programId, model);
    }

    @PostMapping("/admin/programs")
    public String createProgram(@RequestParam String name,
                                @RequestParam(required = false) String description,
                                Model model) {
        try {
            catalogService.createProgram(name, description);
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            return programs(model);
        }
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
                                @RequestParam(required = false) String description,
                                Model model) {
        try {
            catalogService.updateProgram(programId, name, description);
        } catch (IllegalArgumentException e) {
            return detailWithError(programId, e.getMessage(), model);
        }
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
                                @RequestParam(defaultValue = "100") int userPoolSize,
                                Model model) {
        try {
            catalogService.createSession(programId, seatLayoutId,
                    toInstant(startsAt), toInstant(endsAt),
                    toInstant(entryOpensAt), toInstant(entryClosesAt), toInstant(reserveOpensAt),
                    maxPerUser, status, userPoolSize);
        } catch (IllegalArgumentException e) {
            return detailWithError(programId, e.getMessage(), model);
        }
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
                                @RequestParam String status,
                                Model model) {
        List<String> warnings;
        try {
            warnings = catalogService.updateSession(sessionId,
                    toInstant(startsAt), toInstant(endsAt),
                    toInstant(entryOpensAt), toInstant(entryClosesAt), toInstant(reserveOpensAt),
                    maxPerUser, status);
        } catch (IllegalArgumentException e) {
            return detailWithError(programId, e.getMessage(), model);
        }
        if (!warnings.isEmpty()) {
            // 막지는 않았지만 운영자가 알아야 하는 것이다. 이것도 세션에 실을 수
            // 없으므로(위 detailWithError 설명) 바로 그려서 보여준다.
            model.addAttribute("warnings", warnings);
            model.addAttribute("saved", "회차 " + sessionId + "을(를) 수정했습니다.");
            return program(programId, model);
        }
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

    /**
     * 없는 프로그램(GET). 다른 페이지 컨트롤러와 같이 평문으로 답한다.
     *
     * <p><b>등록·수정 실패는 여기로 오지 않는다.</b> 각 POST가 직접 잡아
     * 플래시 메시지로 폼 위에 띄운다 — 여기로 흘리면 평문 오류 페이지가 떠서
     * 운영자가 입력하던 폼을 잃는다. 잘못된 값을 고치려면 폼이 필요하다.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(404).contentType(MediaType.TEXT_PLAIN).body(ex.getMessage());
    }
}
