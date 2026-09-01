package com.inhalab.holdfast.seat;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;

/**
 * 좌석·회차 조회 API. openapi.yaml {@code getSeatMap}·{@code getSeatStatusSnapshot}.
 *
 * <p>서버 세션을 쓰지 않는다(concurrency-spec.md 0.4) — 이 컨트롤러의 어떤
 * 메서드도 {@code HttpSession}을 참조하지 않는다. 앱 2대 구성에서 세션 공유는
 * 측정 오염 요인이다.
 *
 * <p><b>오류 응답은 이 컨트롤러가 만들지 않는다.</b>
 * {@link com.inhalab.holdfast.api.ApiExceptionHandler}가 전부 처리한다 —
 * 컨트롤러마다 오류 모양이 다르면 k6가 {@code code}로 태깅해 집계하는 것이
 * 깨지고, concurrency-spec.md 7.1의 "정상 거절과 오류를 분리한다"가 무너진다.
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
}
