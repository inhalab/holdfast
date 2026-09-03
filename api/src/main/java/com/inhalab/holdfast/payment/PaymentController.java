package com.inhalab.holdfast.payment;

import com.inhalab.holdfast.reservation.IdempotencyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mock PG 결제 API. docs/state-transitions.md 5절이 인터페이스의 정본이다.
 *
 * <p>오류 응답을 여기서 만들지 않는다 —
 * {@link com.inhalab.holdfast.api.ApiExceptionHandler}가 전부 처리한다. 컨트롤러마다
 * 오류 모양이 다르면 k6의 {@code code} 태깅 집계가 깨진다(api-spec.md 3.2절).
 *
 * <p>서버 세션을 쓰지 않는다(concurrency-spec.md 0.4절). 사용자 식별은
 * {@code X-User-Id} 헤더로만 한다.
 */
@RestController
public class PaymentController {

    private final PaymentService paymentService;
    private final IdempotencyService idempotencyService;

    public PaymentController(PaymentService paymentService, IdempotencyService idempotencyService) {
        this.paymentService = paymentService;
        this.idempotencyService = idempotencyService;
    }

    /**
     * 결제 시도. 승인이면 예약이 확정된다.
     *
     * <p>거절도 201이다 — 결제 거절은 오류가 아니라 결과이며, 409로 내보내면
     * k6의 409율(좌석 경합 지표)에 무관한 건수가 섞인다({@link PaymentResponse}).
     *
     * <p>{@code Idempotency-Key}를 확정·홀드와 똑같이 요구한다. 결제야말로 재시도가
     * 중복 청구로 이어지면 안 되는 경로다(concurrency-spec.md 6절).
     */
    @PostMapping("/api/payments")
    public ResponseEntity<?> pay(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                 @RequestHeader("X-User-Id") long userId,
                                 @RequestBody PaymentCreateRequestBody request) {
        return idempotencyService.execute(idempotencyKey, "POST", "/api/payments", request, () -> {
            PaymentResponse body = paymentService.pay(request.holdId(), userId);
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        });
    }
}
