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
    private static final String SESSION_OPEN = "OPEN";
    private static final String SESSION_SCHEDULED = "SCHEDULED";

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
    @GetMapping("/programs/{programId}")
    public String sessions(@PathVariable long programId, Model model) {
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
     * 회차를 목록에서 어떻게 보여줄지 정한다(이슈 #108 — SFR-006의 "접수종료 노출
     * 방식이 운영정책과 일치").
     *
     * <p><b>이유를 하나로 뭉치지 않는다.</b> "오픈 전"과 "종료"를 같은 조건으로
     * 묶으면 닫힌 회차에도 오픈 일시가 찍힌다 — 실제로 그렇게 만들었다가 고쳤다.
     *
     * <p>판단 순서에 뜻이 있다. 닫힌 회차는 잔여석이 남아 있어도 닫힌 것이고,
     * 오픈 전 회차는 매진일 수 없다.
     *
     * <p>서버의 {@code RESERVATION_NOT_OPEN} 거절(REQ-08)을 화면이 미리 보여주는
     * 것이지 대신하는 것이 아니다 — 링크로 들어가도 홀드는 서버가 다시 판정한다.
     */
    private SaleState saleStateOf(EventSession session, long available, Instant now) {
        if (!SESSION_OPEN.equals(session.getStatus())) {
            // SCHEDULED는 아직 열리지 않은 것이고 CLOSED는 끝난 것이다.
            return SESSION_SCHEDULED.equals(session.getStatus())
                    ? SaleState.NOT_YET_OPEN
                    : SaleState.CLOSED;
        }
        if (session.getReserveOpensAt() != null && now.isBefore(session.getReserveOpensAt())) {
            return SaleState.NOT_YET_OPEN;
        }
        return available > 0 ? SaleState.ON_SALE : SaleState.SOLD_OUT;
    }

    /** 없는 프로그램. 다른 페이지 컨트롤러와 같이 평문으로 답한다. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleNotFound(IllegalArgumentException ex) {
        return ResponseEntity.status(404).contentType(MediaType.TEXT_PLAIN).body(ex.getMessage());
    }
}
