package com.inhalab.holdfast.web;

import com.inhalab.holdfast.api.ApiException;
import com.inhalab.holdfast.catalog.SaleState;
import com.inhalab.holdfast.seat.SeatMapResponse;
import com.inhalab.holdfast.seat.SeatMapZoneResponse;
import com.inhalab.holdfast.seat.SeatQueryService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.Instant;
import java.util.List;

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
    // 기본값이 1인 이유: SeatHoldService는 user_session_quota 행이 미리 있어야
    // 홀드를 받는다(concurrency-spec 1.1 — 시드가 사전 생성). load-test/sql/seed.sql이
    // generate_series(1, :users)로 그 행을 만들어 두므로, 표준 시드로 로컬을 띄우면
    // 이 값이 항상 그 범위 안에 들어 별도 수동 시드 없이 화면이 바로 동작한다.
    private static final long DEFAULT_USER_ID = 1L;

    /** {@code seat_inventory.status} — 잔여석을 셀 때 쓴다. */
    private static final String STATUS_AVAILABLE = "AVAILABLE";

    private final SeatQueryService seatQueryService;

    public SeatMapPageController(SeatQueryService seatQueryService) {
        this.seatQueryService = seatQueryService;
    }

    /**
     * 좌석맵 화면.
     *
     * <p><b>{@code ?userId=}로 사용자를 바꿀 수 있다. 인증이 아니다.</b>
     * 이 프로젝트의 서사는 "같은 좌석을 여러 사람이 동시에 노린다"인데, 화면이
     * 한 사용자로 고정돼 있으면 <b>그 장면을 화면으로 만들 수 없다</b> — 창을
     * 둘 띄워 같은 좌석을 눌러 보이는 것이 발표에서 가장 직접적인 시연이다.
     *
     * <p>보안이 약해지는 것이 아니다. 사용자 식별은 이미 {@code X-User-Id}
     * 헤더이고(api-spec.md 7절 — 인증은 제외 항목), API 수준에서는 아무나 그
     * 헤더를 바꿔 보낼 수 있다. 화면만 고정해 두는 것은 아무것도 막지 못하면서
     * 시연만 불가능하게 한다.
     *
     * <p>시드가 만든 사용자 범위(기본 1~500) 밖의 값을 주면 홀드가
     * {@code user_session_quota} 행이 없어 실패한다. 화면은 뜨지만 좌석을 잡을 수
     * 없으므로, 시연 전에 시드 범위를 확인한다.
     */
    @GetMapping("/sessions/{sessionId}")
    public String seatMap(@PathVariable long sessionId,
                          @RequestParam(name = "userId", defaultValue = "" + DEFAULT_USER_ID)
                          long userId,
                          Model model) {
        SeatMapResponse seatMap = seatQueryService.getSeatMap(sessionId);
        model.addAttribute("seatMap", seatMap);
        model.addAttribute("userId", userId);
        model.addAttribute("saleState", saleStateOf(seatMap));
        return "seatmap/index";
    }

    /**
     * 이 회차를 지금 팔고 있는가(이슈 #108 — SFR-006 "접수종료 노출 방식이
     * 운영정책과 일치").
     *
     * <p><b>회차 목록에만 상태를 적는 것으로는 부족했다.</b> 좌석맵은 링크로 곧장
     * 열리고, 그때는 오픈 전이든 종료됐든 화면이 평소와 똑같이 떴다 — 좌석을 고르고
     * 선점을 눌러야 서버의 {@code RESERVATION_NOT_OPEN}을 보게 된다.
     *
     * <p><b>판단은 {@link SaleState#of}가 한다.</b> 목록과 규칙이 갈리면 목록에서는
     * "접수 종료"인데 들어가면 좌석이 눌리는 화면이 생긴다.
     *
     * <p>잔여석은 <b>이미 받아 온 좌석맵에서 센다.</b> 같은 것을 묻는 쿼리를 하나 더
     * 두면 두 값이 어긋날 수 있고, 어긋난 순간이 곧 "매진인데 고를 수 있는" 화면이다.
     */
    private SaleState saleStateOf(SeatMapResponse seatMap) {
        long available = seatMap.zones().stream()
                .map(SeatMapZoneResponse::seats)
                .flatMap(List::stream)
                .filter(seat -> STATUS_AVAILABLE.equals(seat.status()))
                .count();

        return SaleState.of(seatMap.sessionStatus(), seatMap.reserveOpensAt(),
                available, Instant.now());
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
