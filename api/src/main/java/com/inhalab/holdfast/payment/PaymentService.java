package com.inhalab.holdfast.payment;

import com.inhalab.holdfast.api.ApiException;
import com.inhalab.holdfast.api.ErrorCode;
import com.inhalab.holdfast.reservation.Reservation;
import com.inhalab.holdfast.reservation.ReservationRepository;
import com.inhalab.holdfast.reservation.ReservationService;
import com.inhalab.holdfast.ticket.TicketService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Mock PG 결제 시도를 기록하고, 승인이면 예약을 확정시킨다.
 *
 * <h2>확정 경로를 다시 만들지 않는다</h2>
 *
 * <p>승인 시 {@link ReservationService#confirm}을 그대로 호출한다. 확정은 이
 * 프로젝트에서 가장 경합이 심한 구간(CS-2)이고, 다섯 락 전략이 붙는 지점이며,
 * {@code held_until > now()} 조건을 단일 SQL에 넣어 만료-확정 경합을 원자적으로
 * 해소하는 코드다(concurrency-spec.md 3절). <b>결제 경로에 그 로직을 복제하면
 * 락 전략이 결제를 거친 확정에만 적용되지 않는 구멍이 생긴다.</b>
 *
 * <p>같은 이유로 {@code reservation/} 패키지를 수정하지 않았다. 확정 흐름
 * ({@code POST /api/reservations})은 그대로 두고 결제는 그 앞에 얹는다 —
 * k6 측정 시나리오와 {@code verify.sql}의 V-1(확정 좌석 수) 판정이 M3와 같은
 * 의미를 유지해야 재측정이 가능하다.
 *
 * <h2>PENDING_PAYMENT를 쓰지 않는다</h2>
 *
 * <p>state-transitions.md 1절은 Mock PG가 확정되면 예약 전이를
 * {@code HELD → PENDING_PAYMENT → CONFIRMED}로 갱신한다고 적었다. 최소 완결의
 * 승인은 <b>한 트랜잭션 안에서 동기로</b> 끝나므로 그 중간 상태가 외부에서
 * 관측되지 않는다 — 쓰면 트랜잭션 내부에서만 존재하다 사라지는 값이 된다.
 * {@code PENDING_PAYMENT}가 실제로 의미를 갖는 것은 {@code TIMEOUT}과 비동기
 * 콜백을 구현할 때(여유 항목)이고, 그때 이 클래스와 함께 도입한다.
 */
@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationService reservationService;
    private final MockPaymentGateway gateway;
    private final TicketService ticketService;

    public PaymentService(PaymentRepository paymentRepository,
                          ReservationRepository reservationRepository,
                          ReservationService reservationService,
                          MockPaymentGateway gateway,
                          TicketService ticketService) {
        this.paymentRepository = paymentRepository;
        this.reservationRepository = reservationRepository;
        this.reservationService = reservationService;
        this.gateway = gateway;
        this.ticketService = ticketService;
    }

    /**
     * 홀드에 대해 결제를 시도한다.
     *
     * <p>승인이면 예약이 확정되고, 거절이면 예약은 {@code HELD}로 남아 TTL 만료를
     * 기다리거나 사용자가 다시 시도할 수 있다. 재시도는 <b>새 {@code payment} 행</b>을
     * 만든다 — 기존 행을 되돌리지 않는다(state-transitions.md 5절).
     *
     * <p>확정이 실패하면({@code HOLD_EXPIRED} 등) 예외가 트랜잭션을 되돌리므로
     * 결제 행도 남지 않는다. 실패한 시도를 {@code FAILED} 이력으로 남기는 것은
     * 5절의 {@code REQUESTED → FAILED} 전이이며 여유 항목이다.
     */
    @Transactional
    public PaymentResponse pay(String holdId, long userId) {
        Reservation reservation = reservationRepository.findByHoldId(holdId)
                // 남의 홀드나 없는 홀드나 같은 답을 준다 — 존재를 흘리지 않는다.
                .orElseThrow(() -> new ApiException(ErrorCode.HOLD_NOT_FOUND));
        // getUserId()는 박싱 타입이다. NOT NULL 컬럼이라 실제로 null일 수 없지만,
        // 언박싱 NPE가 500으로 새면 경합 실패와 구분되지 않으므로 명시적으로 막는다.
        if (reservation.getUserId() == null || reservation.getUserId() != userId) {
            throw new ApiException(ErrorCode.HOLD_NOT_FOUND);
        }

        Payment payment = new Payment();
        payment.setReservationId(reservation.getId());
        // PG 거래 ID. U-8 유니크가 콜백 멱등키로 쓰는 값이다(concurrency-spec.md 6절).
        // 실제 PG라면 PG가 발급하지만 Mock에서는 우리가 만든다.
        payment.setPgTxId(UUID.randomUUID().toString());
        payment.setStatus(PaymentStatus.REQUESTED.name());
        // 요금 정책은 요구사항 추적표에 없어 0으로 고정한다(openapi.yaml Reservation.totalAmount).
        payment.setAmount(0L);
        payment.setCreatedAt(Instant.now());

        PaymentStatus outcome = gateway.decide();
        String reservationStatus = reservation.getStatus();

        if (outcome == PaymentStatus.APPROVED) {
            // 확정은 기존 경로에 맡긴다. 여기서 실패하면 예외가 올라가 결제 행까지 롤백된다.
            Reservation confirmed = reservationService.confirm(holdId, userId);
            reservationStatus = confirmed.getStatus();
            payment.setStatus(PaymentStatus.APPROVED.name());
            payment.setApprovedAt(Instant.now());
            // 발권(#80)은 확정과 같은 트랜잭션에서 일어난다 — 이유는
            // TicketService#issueTickets 문서에 있다.
            ticketService.issueTickets(confirmed.getId());
        } else {
            payment.setStatus(PaymentStatus.DECLINED.name());
        }

        Payment saved = paymentRepository.save(payment);

        return new PaymentResponse(
                saved.getId(),
                saved.getPgTxId(),
                PaymentStatus.valueOf(saved.getStatus()),
                reservation.getId(),
                reservationStatus,
                saved.getAmount(),
                saved.getApprovedAt(),
                Instant.now());
    }
}
