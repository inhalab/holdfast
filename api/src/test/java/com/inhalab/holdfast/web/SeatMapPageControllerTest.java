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
