package com.inhalab.holdfast.web;

import com.inhalab.holdfast.catalog.CatalogProgramRepository;
import com.inhalab.holdfast.catalog.CatalogSessionRepository;
import com.inhalab.holdfast.catalog.SaleState;
import com.inhalab.holdfast.catalog.SessionAvailabilityRow;
import com.inhalab.holdfast.catalog.SessionCard;
import com.inhalab.holdfast.seat.EventSession;
import com.inhalab.holdfast.seat.Program;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 예약 카탈로그 — <b>시스템의 진입점</b>.
 *
 * <p>이 화면이 생기기 전까지는 좌석맵으로 가려면 {@code /sessions/1}을 주소창에
 * 직접 쳐야 했다. {@code program}·{@code event_session} 테이블은 M2부터 있었지만
 * 그것을 보여주는 화면이 없어, 데이터는 있는데 들어가는 문이 없는 상태였다.
 *
 * <p>흐름은 <b>프로그램 → 회차 → 좌석맵</b>이다. 좌석맵부터는 기존 화면(#43)이
 * 그대로 이어받는다.
 *
 * <p>조회만 한다. {@code seat/}·{@code reservation/} 패키지를 수정하지 않고
 * {@code catalog/}의 별도 저장소로 읽는다(#79·#80·#82와 같은 경계).
 */
@Controller
public class CatalogPageController {

    private static final String STATUS_AVAILABLE = "AVAILABLE";

    private final CatalogProgramRepository programRepository;
    private final CatalogSessionRepository sessionRepository;

    public CatalogPageController(CatalogProgramRepository programRepository,
                                 CatalogSessionRepository sessionRepository) {
        this.programRepository = programRepository;
        this.sessionRepository = sessionRepository;
    }

    /**
     * 프로그램 목록. 첫 화면이다.
     *
     * <p><b>{@code /}에서 리다이렉트하지 않고 같은 화면을 직접 렌더한다.</b>
     * 리다이렉트는 Location 헤더가 절대 URL로 바뀌면서 nginx가 넘긴 Host를
     * 그대로 쓰는데, 그 값에 포트가 없으면 {@code http://localhost/programs}로
     * 튀어 연결이 거부된다. nginx 쪽도 함께 고쳤지만(infra/nginx/nginx.conf의
     * {@code $http_host}), 진입점이 프록시 설정에 의존할 이유가 없다.
     */
    @GetMapping({"/", "/programs"})
    public String programs(Model model) {
        model.addAttribute("programs", programRepository.findAllByOrderByIdAsc());
        return "catalog/programs";
    }

    /** 한 프로그램의 회차 목록. 회차마다 잔여 좌석을 함께 보여준다. */
    /**
     * 회차 목록.
     *
     * <h3>{@code ?userId=}를 받아 좌석맵으로 넘긴다</h3>
     *
     * <p>이 화면 자체는 그 값을 쓰지 않는다 — 회차 목록은 누가 보든 같다.
     * <b>받는 이유는 좌석맵 왕복에서 잃지 않기 위해서다.</b>
     *
     * <p>좌석맵에는 {@code ← 회차 목록}이 있고 이 화면에는 {@code 좌석 선택 →}이
     * 있다. 그 왕복에서 값이 빠지면 <b>사용자 2로 보던 사람이 한 바퀴 돌아 1이
     * 되는데 아무 표시도 없다.</b> 404라면 알아채지만 이쪽은 무증상이다.
     *
     * <p><b>그 상태가 시연을 조용히 망친다.</b> 창을 둘 띄워 사용자를 바꾸는 것이
     * 동시성 시연의 수단인데({@code scope-m4.md} 6절), 둘 다 사용자 1이 되면
     * CS-6이 할당량 행에서 직렬화해 <b>경합이 아예 일어나지 않는다.</b> 같은 결과를
     * 8절이 "시연 중 가장 걸리기 쉬운 함정"이라 부른다.
     *
     * <p><b>프로그램 목록까지 나가면 잃는다.</b> 거기서 되돌아오는 경로는 값을
     * 싣지 않는다 — 흐름을 벗어난 것이라 기본값으로 돌아오는 편이 맞다. 그 경계는
     * {@code scope-m4.md} 8절 표에 적었다.
     */
    @GetMapping("/programs/{programId}")
    public String sessions(@PathVariable long programId,
                           @RequestParam(name = "userId", defaultValue = "1") long userId,
                           Model model) {
        Program program = programRepository.findById(programId)
                .orElseThrow(() -> new IllegalArgumentException("프로그램을 찾을 수 없습니다: " + programId));

        List<EventSession> sessions = sessionRepository.findByProgramIdOrderByStartsAtAsc(programId);

        Map<Long, long[]> counts = countsBySession(sessions);
        Instant now = Instant.now();
        List<SessionCard> cards = sessions.stream()
                .map(s -> {
                    long[] c = counts.getOrDefault(s.getId(), new long[]{0, 0});
                    return new SessionCard(
                            s.getId(), s.getStartsAt(), s.getEndsAt(), s.getReserveOpensAt(),
                            s.getStatus(), c[0], c[1], saleStateOf(s, c[0], now));
                })
                .toList();

        model.addAttribute("program", program);
        model.addAttribute("sessions", cards);
        model.addAttribute("userId", userId);
        return "catalog/sessions";
    }

    /** 회차 → {잔여, 전체}. 좌석이 아직 없는 회차는 호출자가 0으로 채운다. */
    private Map<Long, long[]> countsBySession(List<EventSession> sessions) {
        Map<Long, long[]> counts = new HashMap<>();
        if (sessions.isEmpty()) {
            return counts;
        }
        List<Long> ids = sessions.stream().map(EventSession::getId).toList();
        for (SessionAvailabilityRow row : sessionRepository.availabilityOf(ids)) {
            long[] c = counts.computeIfAbsent(row.sessionId(), k -> new long[]{0, 0});
            if (STATUS_AVAILABLE.equals(row.status())) {
                c[0] = row.count();
            }
            c[1] += row.count();
        }
        return counts;
    }

    /**
     * 노출 상태는 {@link SaleState#of}가 정한다. 좌석맵 화면
     * ({@link SeatMapPageController})도 같은 메서드를 쓴다 — 목록에서는 "접수
     * 종료"인데 들어가 보면 좌석이 눌리는 어긋남을 없애려면 규칙이 한 벌이어야
     * 한다(이슈 #108). 정책과 근거는 그 열거형의 클래스 주석에 있다.
     */
    private SaleState saleStateOf(EventSession session, long available, Instant now) {
        return SaleState.of(session.getStatus(), session.getReserveOpensAt(), available, now);
    }

    /** 없는 프로그램. 다른 페이지 컨트롤러와 같이 평문으로 답한다. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleNotFound(IllegalArgumentException ex) {
        return ResponseEntity.status(404).contentType(MediaType.TEXT_PLAIN).body(ex.getMessage());
    }
}
