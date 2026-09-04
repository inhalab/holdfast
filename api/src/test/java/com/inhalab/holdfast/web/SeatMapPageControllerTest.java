package com.inhalab.holdfast.web;

import com.inhalab.holdfast.seat.SeatMapResponse;
import com.inhalab.holdfast.seat.SeatMapSeatResponse;
import com.inhalab.holdfast.seat.SeatMapZoneResponse;
import com.inhalab.holdfast.seat.SeatQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * 좌석맵 페이지가 실제로 렌더되는지 검증한다. content 검증이 Thymeleaf 템플릿을
 * 끝까지 렌더시키므로, grid fragment include와 th:* 표현식 오류를 CI가 잡아준다.
 *
 * {@link SeatQueryService}는 seat/조회 API(#37) 담당 영역이라 슬라이스 테스트에서
 * DB 없이 Mockito로 대체한다 — 이 테스트가 검증하는 것은 조회 결과가 아니라
 * 페이지 렌더링(템플릿 표현식·격자 구조)이다.
 */
@WebMvcTest(SeatMapPageController.class)
class SeatMapPageControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SeatQueryService seatQueryService;

    @Test
    void 좌석맵_페이지가_격자와_함께_렌더된다() throws Exception {
        when(seatQueryService.getSeatMap(anyLong())).thenReturn(sampleSeatMap());

        mvc.perform(get("/sessions/{sessionId}", 1001))
                .andExpect(status().isOk())
                .andExpect(view().name("seatmap/index"))
                // 회차 메타가 data-* 로 내려간다 (클라이언트 상태 보관용)
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-session-id=\"1001\"")))
                // grid fragment가 좌석 버튼을 렌더한다
                .andExpect(content().string(org.hamcrest.Matchers.containsString("class=\"seat")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-seat-id")))
                // 세 상태가 모두 나타난다
                .andExpect(content().string(org.hamcrest.Matchers.containsString("is-available")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("is-held")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("is-sold")));
    }


    // ── 접수종료 노출 정책 (이슈 #108, design-spec 5.6) ──────────────────
    //
    // **회차 목록에 상태를 적는 것만으로는 부족했다.** 좌석맵은 링크로 곧장
    // 열리고, 그때는 오픈 전이든 종료됐든 화면이 평소와 똑같이 떴다 — 좌석을
    // 고르고 선점을 눌러야 서버 거절을 보게 된다.
    //
    // 판단 규칙 자체는 SaleStatePolicyTest가 본다. 여기서 보는 것은
    // **그 결과가 화면에 나타나는가**다.

    @Test
    void 파는_회차는_안내_없이_평소대로_렌더된다() throws Exception {
        when(seatQueryService.getSeatMap(anyLong())).thenReturn(sampleSeatMap());

        mvc.perform(get("/sessions/{sessionId}", 1001))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-sale-state=\"ON_SALE\"")))
                .andExpect(content().string(not(containsString("sale-notice"))))
                .andExpect(content().string(containsString(">선점하기<")));
    }

    @Test
    void 예약_오픈_전이면_안내와_함께_선점_버튼이_잠긴다() throws Exception {
        when(seatQueryService.getSeatMap(anyLong()))
                .thenReturn(seatMap("OPEN", Instant.now().plusSeconds(3600), "AVAILABLE"));

        mvc.perform(get("/sessions/{sessionId}", 1001))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-sale-state=\"NOT_YET_OPEN\"")))
                .andExpect(content().string(containsString("아직 예약 오픈 전입니다")))
                // 버튼 문구가 바뀐다. 실제로 못 누르게 막는 것은 seatmap.js의
                // onSale 플래그이고, 그 플래그의 출처가 위 data-sale-state다.
                .andExpect(content().string(containsString(">선택할 수 없음<")));
    }

    @Test
    void 잔여석이_0이면_매진으로_보인다() throws Exception {
        when(seatQueryService.getSeatMap(anyLong()))
                .thenReturn(seatMap("OPEN", null, "HELD", "SOLD"));

        mvc.perform(get("/sessions/{sessionId}", 1001))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-sale-state=\"SOLD_OUT\"")))
                .andExpect(content().string(containsString("매진되었습니다")));
    }

    @Test
    void 닫힌_회차는_자리가_남아도_접수_종료로_보인다() throws Exception {
        when(seatQueryService.getSeatMap(anyLong()))
                .thenReturn(seatMap("CLOSED", null, "AVAILABLE", "AVAILABLE"));

        mvc.perform(get("/sessions/{sessionId}", 1001))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-sale-state=\"CLOSED\"")))
                .andExpect(content().string(containsString("접수가 종료된 회차입니다")));
    }

    /** 좌석 상태만 바꿔 가며 회차를 만든다. 잔여석은 AVAILABLE 개수로 정해진다. */
    private SeatMapResponse seatMap(String sessionStatus, Instant reserveOpensAt,
                                    String... seatStatuses) {
        List<SeatMapSeatResponse> seats = new java.util.ArrayList<>();
        for (int i = 0; i < seatStatuses.length; i++) {
            seats.add(new SeatMapSeatResponse(
                    30001L + i, "A-" + (i + 1), 1, i + 1, seatStatuses[i]));
        }
        Instant now = Instant.now();
        return new SeatMapResponse(
                1001L, sessionStatus, reserveOpensAt, now, now.plusSeconds(7200),
                4, 300, now, List.of(new SeatMapZoneResponse(1L, "A구역", 1, seats)));
    }

    private SeatMapResponse sampleSeatMap() {
        List<SeatMapSeatResponse> seats = List.of(
                new SeatMapSeatResponse(30001L, "A-1", 1, 1, "AVAILABLE"),
                new SeatMapSeatResponse(30002L, "A-2", 1, 2, "HELD"),
                new SeatMapSeatResponse(30003L, "A-3", 1, 3, "SOLD")
        );
        SeatMapZoneResponse zone = new SeatMapZoneResponse(1L, "A구역", 1, seats);
        Instant now = Instant.now();
        return new SeatMapResponse(
                1001L, "OPEN", null, now, now.plusSeconds(7200),
                4, 300, now, List.of(zone));
    }
}
