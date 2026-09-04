package com.inhalab.holdfast.web;

import com.inhalab.holdfast.api.ApiException;
import com.inhalab.holdfast.reservation.Reservation;
import com.inhalab.holdfast.reservation.ReservationService;
import com.inhalab.holdfast.ticket.TicketService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 예약 확인·티켓 화면. 이슈 #81 — M4 최소 완결 흐름
 * "좌석 선택 → 선점 → 확정 → 결제 → QR → 검표"의 확정·QR 구간이다.
 *
 * <p>결제 승인({@code POST /api/payments})이 성공하면 그 응답의
 * {@code reservationId}로 이 페이지로 이동한다(seatmap.js). 조회는
 * {@link ReservationService}·{@link TicketService}를 직접 호출한다 — 이미
 * 공개된 서비스를 읽기만 하며 그 패키지들을 수정하지 않는다(#79·#80과 같은 경계).
 */
@Controller
public class ReservationPageController {

    // 인증 미구현 — SeatMapPageController와 같은 기본값(openapi UserIdHeader 대신).
    private static final long DEFAULT_USER_ID = 1L;

    private final ReservationService reservationService;
    private final TicketService ticketService;

    public ReservationPageController(ReservationService reservationService, TicketService ticketService) {
        this.reservationService = reservationService;
        this.ticketService = ticketService;
    }

    /**
     * 예약 확인·티켓 화면.
     *
     * <p><b>{@code ?userId=}를 받는 이유는 좌석맵과 같다</b>
     * ({@code SeatMapPageController#seatMap}). 여기서는 특히 필요하다 —
     * {@code ReservationService#get}이 남의 예약을 404로 막으므로, 사용자 2로
     * 예매한 뒤 이 화면을 열면 <b>기본값 1로는 404가 된다.</b> 시연 도중 흐름이
     * 거기서 끊긴다.
     */
    @GetMapping("/reservations/{reservationId}")
    public String show(@PathVariable long reservationId,
                       @RequestParam(name = "userId", defaultValue = "" + DEFAULT_USER_ID)
                       long userId,
                       Model model) {
        Reservation reservation = reservationService.get(reservationId, userId);
        model.addAttribute("reservation", reservation);
        model.addAttribute("seats", reservationService.seatsOf(reservationId));
        model.addAttribute("tickets", ticketService.ticketsOf(reservationId));
        return "reservations/confirm";
    }

    /** SeatMapPageController와 같은 이유로 로컬에서 처리한다 — 페이지에는 평문으로. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<String> handleApiException(ApiException ex) {
        return ResponseEntity.status(ex.getCode().status())
                .contentType(MediaType.TEXT_PLAIN)
                .body(ex.getMessage());
    }
}
