package com.inhalab.holdfast.api;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 모든 오류 응답을 한 곳에서 만든다. api-spec.md 3절.
 *
 * <h2>왜 공통 핸들러여야 하는가</h2>
 *
 * <p>k6는 응답 본문의 {@code code}로 태깅해 집계한다(api-spec.md 3.2절).
 * 컨트롤러마다 오류 응답 모양이 다르면 같은 상황이 다른 코드로 나가고, 그러면
 * concurrency-spec.md 7.1의 <b>"정상 거절과 오류를 분리한다"</b>가 무너진다 —
 * 비교표의 409율·락 포기율·오류율이 서로 섞인 숫자가 된다.
 *
 * <h2>경합은 5xx로 새지 않는다</h2>
 *
 * <p>api-spec.md 3.3절이 지정한 변환을 여기서 강제한다. 프레임워크가 던지는
 * 데이터 접근 예외를 그대로 두면 500이 되는데, 부하 테스트에서 500은 측정값이
 * 아니라 고쳐야 할 결함으로 집계된다. 경합 때문에 생긴 실패가 거기 섞이면
 * 오류율이 오염된다.
 *
 * <ul>
 *   <li>락 대기 초과·데드락 → {@link ErrorCode#LOCK_TIMEOUT}</li>
 *   <li>낙관적 락 충돌 → {@link ErrorCode#RETRY_EXHAUSTED}</li>
 *   <li>유니크 제약 위반 → 제약 이름으로 구분해 409</li>
 * </ul>
 */
@RestControllerAdvice
// Spring Boot의 내장 problemdetails 핸들러(spring.mvc.problemdetails.enabled)가
// MissingRequestHeaderException 같은 표준 MVC 예외를 먼저 잡으면 code 필드 없는
// ProblemDetail이 나간다. 그러면 k6 classify.js가 그 응답을 미분류로 세고,
// 그 실행의 숫자는 쓸 수 없게 된다(load-test/scenarios/lib/classify.js).
// 우선순위를 최상위로 올려 계약이 정한 code가 항상 붙게 한다.
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * 제약 위반 발생 횟수. 7.1이 "앱 커스텀 메트릭"으로 출처를 못박은 값이다.
     *
     * <p><b>k6로는 셀 수 없다.</b> U-2 위반은 아래에서 409
     * {@code SEAT_HELD_BY_OTHER}로 변환되므로 클라이언트에서는 정상 거절과
     * 구분되지 않는다. 그동안은 Postgres 로그의 duplicate key 메시지를 세어
     * 대신했는데(results/m2-pessimistic.md 1.1), 로그 수준에 의존하는 방식이라
     * 메트릭으로 옮긴다.
     *
     * <p>제약 이름을 태그로 붙인다. {@code unique} 전략에서 U-2 위반은 정상
     * 동작이고(4.4) 다른 전략에서는 앱 락이 샜다는 신호라, 어느 제약이
     * 걸렸는지가 해석을 가른다.
     */
    private final Counter.Builder violationCounter = Counter.builder("holdfast.constraint.violations")
            .description("유니크 제약 위반 횟수. 앱 레벨 락이 샜는지의 지표 (7.1, 7.6)");

    private final MeterRegistry meterRegistry;

    public ApiExceptionHandler(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 유니크 제약 이름 → 코드. 이름을 보고 구분하는 이유는 같은
     * {@code DataIntegrityViolationException}이라도 어느 제약이 걸렸는지에 따라
     * 의미가 전혀 다르기 때문이다(erd.md 3절의 U-1~U-13).
     */
    private static final Map<String, ErrorCode> CONSTRAINT_CODES = Map.of(
            // U-2: 활성 홀드 중복. 다른 사용자가 이미 홀드 중이라는 뜻이다.
            "ux_seat_hold_active", ErrorCode.SEAT_HELD_BY_OTHER,
            // U-13: 같은 멱등키로 동시에 두 요청이 들어와 한쪽이 진 경우.
            "ux_idempotency_record_key", ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS,
            // U-7: 한 예약 안에 같은 좌석이 두 번.
            "ux_reservation_seat_reservation_inventory", ErrorCode.SEAT_ALREADY_SOLD,
            // U-6: 같은 홀드 그룹으로 예약이 두 건.
            "ux_reservation_hold", ErrorCode.IDEMPOTENCY_KEY_IN_PROGRESS);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
        return respond(ex.getCode(), ex.getMessage(), ex.getConflicts());
    }

    // ── api-spec.md 3.3: 경합으로 인한 실패를 409로 변환한다 ──────────────

    /**
     * Postgres {@code lock_timeout}(7.3에서 1초로 고정) 초과와 데드락.
     * 둘 다 락 포기율로 집계된다 — "좌석이 남아 있었을 수도 있는데 기다리다
     * 포기한 것"이라 정상 거절도 오류도 아니다(7.6.1).
     */
    @ExceptionHandler({
            CannotAcquireLockException.class,
            PessimisticLockingFailureException.class,
            QueryTimeoutException.class
    })
    public ResponseEntity<ProblemDetail> handleLockTimeout(Exception ex) {
        log.debug("락 대기 초과를 409 LOCK_TIMEOUT으로 변환한다", ex);
        return respond(ErrorCode.LOCK_TIMEOUT, ErrorCode.LOCK_TIMEOUT.defaultDetail(), List.of());
    }

    /**
     * 낙관적 락 충돌. {@code SEAT_HELD_BY_OTHER}로 바꾸지 않는다 — 재시도를
     * 소진한 시점에 좌석이 실제로 남아 있었을 수도 있어, 정상 거절로 세면
     * 409율이 오염된다(api-spec.md 3.3절).
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleOptimisticFailure(OptimisticLockingFailureException ex) {
        log.debug("낙관적 락 충돌을 409 RETRY_EXHAUSTED로 변환한다", ex);
        return respond(ErrorCode.RETRY_EXHAUSTED, ErrorCode.RETRY_EXHAUSTED.defaultDetail(), List.of());
    }

    /**
     * 유니크 제약 위반. {@code unique} 전략에서는 정상 동작이고 다른 전략에서는
     * 앱 락이 샜다는 신호지만(concurrency-spec.md 4.4절), 어느 쪽이든 500으로
     * 나가면 안 된다.
     *
     * <p>어느 제약인지 모르면 500으로 둔다. 모르는 제약이 걸린 것은 경합이
     * 아니라 결함이고, 그때는 오류율에 잡혀 드러나는 편이 맞다.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(DataIntegrityViolationException ex) {
        String message = String.valueOf(ex.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
        for (Map.Entry<String, ErrorCode> entry : CONSTRAINT_CODES.entrySet()) {
            if (message.contains(entry.getKey())) {
                violationCounter.tag("constraint", entry.getKey()).register(meterRegistry).increment();
                log.debug("제약 {} 위반을 409 {}로 변환한다", entry.getKey(), entry.getValue());
                return respond(entry.getValue(), entry.getValue().defaultDetail(), List.of());
            }
        }
        log.error("알 수 없는 제약 위반이다. 경합이 아니라 결함이므로 500으로 낸다", ex);
        return respond(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultDetail(), List.of());
    }

    // ── 클라이언트 오류 ────────────────────────────────────────────────

    /** {@code Idempotency-Key}·{@code X-User-Id} 누락이 여기로 온다(api-spec.md 6절). */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> handleMissingHeader(MissingRequestHeaderException ex) {
        return respond(ErrorCode.VALIDATION_FAILED,
                "필수 헤더가 없습니다: " + ex.getHeaderName(), List.of());
    }

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<ProblemDetail> handleValidation(Exception ex) {
        return respond(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultDetail(), List.of());
    }

    // ── 서버 오류 ──────────────────────────────────────────────────────

    /**
     * 여기까지 온 것은 계약에 없는 예외다. 부하 테스트에서 이 응답이 한 건이라도
     * 나오면 측정값이 아니라 고쳐야 할 결함이다(api-spec.md 3.3절).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("처리되지 않은 예외", ex);
        return respond(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultDetail(), List.of());
    }

    private ResponseEntity<ProblemDetail> respond(ErrorCode code, String detail, List<SeatConflict> conflicts) {
        return ResponseEntity.status(code.status()).body(ProblemDetails.of(code, detail, conflicts));
    }
}
