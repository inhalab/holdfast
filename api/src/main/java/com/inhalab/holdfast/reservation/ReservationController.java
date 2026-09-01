package com.inhalab.holdfast.reservation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 예약 확정·조회·취소 API. openapi.yaml {@code confirmReservation}·
 * {@code getReservation}·{@code cancelReservation}.
 *
 * <p>오류 응답은 {@link com.inhalab.holdfast.api.ApiExceptionHandler}가 만든다.
 */
@RestController
public class ReservationController {

    private final ReservationService reservationService;
    private final IdempotencyService idempotencyService;

    public ReservationController(ReservationService reservationService,
                                 IdempotencyService idempotencyService) {
        this.reservationService = reservationService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping("/api/reservations")
    public ResponseEntity<?> confirm(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                     @RequestHeader("X-User-Id") long userId,
                                     @RequestBody ReservationCreateRequestBody request) {
        return idempotencyService.execute(idempotencyKey, "POST", "/api/reservations", request, () -> {
            Reservation reservation = reservationService.confirm(request.holdId(), userId);
            return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(reservation));
        });
    }

    @GetMapping("/api/reservations/{reservationId}")
    public ReservationResponse get(@PathVariable long reservationId,
                                   @RequestHeader("X-User-Id") long userId) {
        return toResponse(reservationService.get(reservationId, userId));
    }

    @PostMapping("/api/reservations/{reservationId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable long reservationId,
                                    @RequestHeader("Idempotency-Key") String idempotencyKey,
                                    @RequestHeader("X-User-Id") long userId) {
        return idempotencyService.execute(
                idempotencyKey, "POST", "/api/reservations/" + reservationId + "/cancel", null, () -> {
                    ReservationService.CancelOutcome outcome =
                            reservationService.cancel(reservationId, userId);
                    Reservation reservation = outcome.reservation();
                    CancelResultResponse body = new CancelResultResponse(
                            reservation.getId(),
                            reservation.getStatus(),
                            reservation.getCancelledAt(),
                            outcome.releasedSeatIds(),
                            Instant.now());
                    return ResponseEntity.ok(body);
                });
    }

    private ReservationResponse toResponse(Reservation reservation) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getSessionId(),
                reservation.getUserId(),
                reservation.getStatus(),
                reservationService.seatsOf(reservation.getId()),
                reservation.getTotalAmount(),
                reservation.getConfirmedAt(),
                reservation.getCancelledAt(),
                Instant.now());
    }
}
