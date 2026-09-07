package com.inhalab.holdfast.web;

import com.inhalab.holdfast.catalog.CatalogProgramRepository;
import com.inhalab.holdfast.catalog.CatalogSessionRepository;
import com.inhalab.holdfast.seat.Program;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>경로와 링크가 함께 움직이는지 본다.</b> 이슈 #124 — 판정은 #138.
 *
 * <p>{@code /admin/**}과 {@code /demo/**}는 로컬에서만 켜지는 화면이라 프로퍼티로
 * 끈다. 그런데 <b>같은 프로퍼티를 두 곳이 나눠 쓴다</b> — 컨트롤러는
 * {@code @ConditionalOnProperty}로 빈을 만들지 않고, 바닥 내비게이션은
 * {@link PageModelAdvice}가 넘긴 값으로 링크를 지운다.
 *
 * <p><b>둘 중 하나만 바뀌면 화면이 거짓말을 한다.</b>
 *
 * <ul>
 *   <li>링크만 남으면 <b>눌러서 404</b>를 만난다 — 이슈가 지목한 상태다</li>
 *   <li>경로만 남으면 <b>들어갈 길이 없는데 열려 있다</b> — 끈 줄 알았는데 안 꺼진 것이라
 *       더 나쁘다</li>
 * </ul>
 *
 * <p>그래서 이 테스트는 켠 상태와 끈 상태에서 <b>경로와 링크를 함께</b> 본다.
 * 한쪽만 고치면 여기서 걸린다.
 *
 * <p>DB가 필요 없다 — 보는 것은 라우팅과 렌더링이지 데이터가 아니다.
 */
@DisplayName("로컬 전용 화면: 경로와 링크가 함께 켜지고 함께 꺼진다")
class LocalOnlyScreenTest {

    /** 프로그램 목록은 아무 화면이나 하나면 된다 — 바닥 내비게이션은 모든 화면에 붙는다. */
    private static final String ANY_PAGE = "/programs";

    @Nested
    @WebMvcTest(controllers = {CatalogPageController.class, AdminPageController.class})
    @DisplayName("기본값 — 로컬은 둘 다 켬")
    class Enabled extends Fixture {

        @Test
        void 관리자_경로가_열리고_링크도_있다() throws Exception {
            mvc.perform(get("/admin/reservations")).andExpect(status().isOk());

            mvc.perform(get(ANY_PAGE))
                    .andExpect(content().string(containsString("/admin/programs")))
                    .andExpect(content().string(containsString("/demo/race")));
        }
    }

    @Nested
    @WebMvcTest(controllers = {CatalogPageController.class, AdminPageController.class})
    @TestPropertySource(properties = "holdfast.admin.enabled=false")
    @DisplayName("admin.enabled=false — 경로도 링크도 없다")
    class AdminDisabled extends Fixture {

        @Test
        void 관리자_경로가_404다() throws Exception {
            // 빈이 만들어지지 않아 매핑 자체가 없다. **잠근 것이 아니라 없앤 것이다** —
            // 403이 아니라 404인 것이 그 차이다.
            mvc.perform(get("/admin/reservations")).andExpect(status().isNotFound());
        }

        @Test
        void 바닥_내비에_관리자_링크가_없다() throws Exception {
            mvc.perform(get(ANY_PAGE))
                    .andExpect(status().isOk())
                    // 데모는 그대로 켜져 있다 — 둘은 따로 끈다.
                    .andExpect(content().string(containsString("/demo/race")))
                    .andExpect(content().string(not(containsString("/admin/programs"))));
        }
    }

    @Nested
    @WebMvcTest(controllers = CatalogPageController.class)
    @TestPropertySource(properties = "holdfast.demo.enabled=false")
    @DisplayName("demo.enabled=false — 데모 링크가 사라진다")
    class DemoDisabled extends Fixture {

        @Test
        void 바닥_내비에_데모_링크가_없다() throws Exception {
            mvc.perform(get(ANY_PAGE))
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString("/demo/race"))))
                    // 관리자는 그대로다.
                    .andExpect(content().string(containsString("/admin/programs")));
        }
    }

    /**
     * 세 슬라이스가 같은 협력자를 쓴다. {@code /programs}가 렌더되기만 하면 되므로
     * 프로그램은 하나면 충분하고, 나머지 저장소는 비어 있어도 된다.
     */
    abstract static class Fixture {

        @Autowired
        MockMvc mvc;

        @MockitoBean
        CatalogProgramRepository programRepository;

        @MockitoBean
        CatalogSessionRepository sessionRepository;

        @MockitoBean
        com.inhalab.holdfast.admin.AdminReservationRepository adminReservationRepository;

        @MockitoBean
        com.inhalab.holdfast.admin.AdminSeatSummaryRepository adminSeatSummaryRepository;

        @MockitoBean
        com.inhalab.holdfast.admin.AdminSessionStatsRepository adminSessionStatsRepository;

        @MockitoBean
        com.inhalab.holdfast.reservation.ReservationService reservationService;

        @org.junit.jupiter.api.BeforeEach
        void stubCatalog() {
            when(programRepository.findAllByOrderByIdAsc()).thenReturn(List.<Program>of());
        }
    }
}
