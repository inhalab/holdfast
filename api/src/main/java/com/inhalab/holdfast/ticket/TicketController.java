package com.inhalab.holdfast.ticket;

import com.inhalab.holdfast.reservation.ReservationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 티켓 조회·검표 API. docs/state-transitions.md 4절.
 *
 * <p>오류 응답을 여기서 만들지 않는다 —
 * {@link com.inhalab.holdfast.api.ApiExceptionHandler}가 전부 처리한다.
 */
@RestController
public class TicketController {

    private final TicketService ticketService;
    private final ReservationService reservationService;

    public TicketController(TicketService ticketService, ReservationService reservationService) {
        this.ticketService = ticketService;
        this.reservationService = reservationService;
    }

    /**
     * 예약의 티켓 목록. 본인 예약만 조회할 수 있다 — 소유권 확인은
     * {@link ReservationService#get}에 위임한다(남의 예약이면 404).
     */
    @GetMapping("/api/reservations/{reservationId}/tickets")
    public List<TicketResponse> tickets(@PathVariable long reservationId,
                                        @RequestHeader("X-User-Id") long userId) {
        reservationService.get(reservationId, userId);
        return ticketService.ticketsOf(reservationId).stream()
                .map(row -> new TicketResponse(
                        row.ticketId(), row.qrToken(), row.status(),
                        row.seatNo(), row.zoneName(), row.issuedAt(), row.usedAt(), Instant.now()))
                .toList();
    }

    /**
     * 검표. 사용자 식별 헤더를 요구하지 않는다 — 스캔하는 쪽은 티켓 소유자가
     * 아니라 게이트 단말이고, 판정은 QR 토큰 자체로 이뤄진다.
     *
     * <p>{@code Idempotency-Key}를 요구하지 않는다. 홀드·확정·결제와 달리
     * 검표는 재시도를 멱등하게 눌러야 하는 "요청"이 아니라, 스캔할 때마다
     * 일어나는 물리적 사건의 기록이다 — 같은 티켓을 두 번 스캔하면 두 번째는
     * 네트워크 재시도가 아니라 정말로 다시 스캔된 것이고, {@code REJECTED_DUPLICATE}가
     * 그 사실을 정확히 담는다.
     */
    @PostMapping("/api/tickets/scan")
    public TicketScanResponse scan(@RequestBody TicketScanRequestBody request) {
        return ticketService.scan(request.qrToken());
    }
}
