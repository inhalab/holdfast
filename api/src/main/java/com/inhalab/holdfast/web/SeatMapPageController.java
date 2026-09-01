package com.inhalab.holdfast.web;

import com.inhalab.holdfast.api.ApiException;
import com.inhalab.holdfast.seat.SeatMapResponse;
import com.inhalab.holdfast.seat.SeatQueryService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 좌석맵 페이지를 렌더하는 컨트롤러. 서버렌더 화면이므로 templates/(박태준 담당)에 속한다.
 *
 * 좌석 조회는 {@link SeatQueryService}(최건, #37)가 그대로 담당한다. 그 결과인
 * {@link SeatMapResponse}의 필드명이 openapi.yaml SeatMap 스키마와 1:1이라
 * 템플릿(seatmap/index.html, seatmap/grid.html)에 별도 변환 없이 바로 넘긴다.
 *
 * 3초 폴링(status fragment)과 상태 변경(hold/reservation) 엔드포인트는 계약 경로 그대로
 * seat/ · reservation/ 컨트롤러가 제공한다. 이 컨트롤러는 그 경로를 매핑하지 않는다
 * (중복 매핑 방지).
 */
@Controller
public class SeatMapPageController {

    // 인증 미구현 — 사용자 식별은 X-User-Id 헤더가 대신한다 (openapi UserIdHeader).
    // 화면에서는 이 값을 data-user-id로 내려 JS가 상태 변경 요청 헤더에 싣는다.
    //
    // 1로 고정하는 이유: SeatHoldService는 user_session_quota 행이 미리 있어야
    // 홀드를 받는다(concurrency-spec 1.1 — 시드가 사전 생성). load-test/sql/seed.sql이
    // generate_series(1, :users)로 그 행을 만들어 두므로, 표준 시드로 로컬을 띄우면
    // 이 값이 항상 그 범위 안에 들어 별도 수동 시드 없이 화면이 바로 동작한다.
    private static final long DEV_USER_ID = 1L;

    private final SeatQueryService seatQueryService;

    public SeatMapPageController(SeatQueryService seatQueryService) {
        this.seatQueryService = seatQueryService;
    }

    @GetMapping("/sessions/{sessionId}")
    public String seatMap(@PathVariable long sessionId, Model model) {
        model.addAttribute("seatMap", seatQueryService.getSeatMap(sessionId));
        model.addAttribute("userId", DEV_USER_ID);
        return "seatmap/index";
    }

    /**
     * {@link ApiExceptionHandler}는 REST API 전용 {@code @RestControllerAdvice}라
     * {@code application/problem+json}만 돌려준다. 이 컨트롤러는 브라우저가 보는
     * 페이지이므로, 같은 예외라도 여기서 던져지면 사람이 읽을 평문으로 답한다.
     * 컨트롤러 로컬 {@code @ExceptionHandler}가 전역 advice보다 먼저 매칭된다.
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<String> handleApiException(ApiException ex) {
        return ResponseEntity.status(ex.getCode().status())
                .contentType(MediaType.TEXT_PLAIN)
                .body(ex.getMessage());
    }
}
