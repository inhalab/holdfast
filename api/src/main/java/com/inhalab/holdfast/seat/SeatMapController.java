package com.inhalab.holdfast.seat;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.Set;

/**
 * 좌석·회차 조회 API. openapi.yaml {@code getSeatMap}·{@code getSeatStatusSnapshot}.
 *
 * <p>서버 세션을 쓰지 않는다(concurrency-spec.md 0.4) — 이 컨트롤러의 어떤
 * 메서드도 {@code HttpSession}을 참조하지 않는다. 앱 2대 구성에서 세션 공유는
 * 측정 오염 요인이다.
 *
 * <p>오류 처리는 지금은 이 컨트롤러 안에 국한한다. 예약·홀드 API가 붙으면
 * {@code ErrorCode} 전체를 다루는 공통 처리로 옮길 여지가 있지만, 지금은 이
 * 두 엔드포인트가 실제로 내는 404·500만 다룬다(openapi.yaml에 이 두 엔드포인트의
 * 응답으로 명시된 것은 200/304/404/500뿐이다).
 */
@RestController
@RequestMapping("/api/sessions/{sessionId}")
public class SeatMapController {

    private final SeatQueryService seatQueryService;
    private final ITemplateEngine templateEngine;

    public SeatMapController(SeatQueryService seatQueryService, ITemplateEngine templateEngine) {
        this.seatQueryService = seatQueryService;
        this.templateEngine = templateEngine;
    }

    /** GET /api/sessions/{sessionId}/seats — 전체 조회. 폴링에 쓰지 않는다. */
    @GetMapping(value = "/seats", produces = MediaType.APPLICATION_JSON_VALUE)
    public SeatMapResponse getSeatMap(@PathVariable Long sessionId) {
        return seatQueryService.getSeatMap(sessionId);
    }

    /** GET /api/sessions/{sessionId}/seats/status — 폴링 전용, JSON. */
    @GetMapping(value = "/seats/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object getSeatStatusJson(@PathVariable Long sessionId, WebRequest webRequest, HttpServletResponse response) {
        SeatStatusSnapshotResult result = seatQueryService.getSeatStatusSnapshot(sessionId);

        // WebRequest#checkNotModified가 If-None-Match를 비교해 일치하면 응답에
        // 304와 ETag를 직접 세팅한다. 그러면 여기서는 몸통 없이 null만 돌려주면 된다.
        if (webRequest.checkNotModified(result.etag())) {
            return null;
        }

        response.setHeader("Cache-Control", CacheControl.noCache().getHeaderValue());
        return result.snapshot();
    }

    /** GET /api/sessions/{sessionId}/seats/status — 폴링 전용, htmx fragment. */
    @GetMapping(value = "/seats/status", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public Object getSeatStatusHtml(@PathVariable Long sessionId, WebRequest webRequest, HttpServletResponse response) {
        SeatStatusSnapshotResult result = seatQueryService.getSeatStatusSnapshot(sessionId);

        if (webRequest.checkNotModified(result.etag())) {
            return null;
        }

        response.setHeader("Cache-Control", CacheControl.noCache().getHeaderValue());

        Context context = new Context();
        context.setVariable("seats", result.snapshot().seats());
        // "템플릿명 :: fragment명" 문자열 결합은 th:insert/th:replace 안에서만 파싱되는
        // 표준 표현식 문법이다. process()에 그대로 넘기면 그 문자열 자체를 템플릿
        // 이름으로 찾다가 실패한다. 자바 API로 fragment를 뽑을 때는 셀렉터를 별도
        // Set<String> 인자로 넘긴다 — 전체 페이지가 아니라 htmx가 교체할 조각
        // 하나만 반환한다.
        return templateEngine.process("fragments/seat-status", Set.of("seatStatus"), context);
    }

    @ExceptionHandler(SessionNotFoundException.class)
    public ProblemDetail handleSessionNotFound(SessionNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Not Found");
        problem.setProperty("code", "SESSION_NOT_FOUND");
        problem.setProperty("serverTime", Instant.now().toString());
        return problem;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "예기치 못한 오류가 발생했습니다.");
        problem.setTitle("Internal Server Error");
        problem.setProperty("code", "INTERNAL_ERROR");
        problem.setProperty("serverTime", Instant.now().toString());
        return problem;
    }
}
