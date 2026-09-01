package com.inhalab.holdfast.reservation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 좌석 선점·해제 API. openapi.yaml {@code createHold}·{@code releaseHold}.
 *
 * <p>오류 응답을 여기서 만들지 않는다. {@link com.inhalab.holdfast.api.ApiExceptionHandler}가
 * 전부 처리한다 — 컨트롤러마다 오류 모양이 다르면 k6의 {@code code} 태깅 집계가
 * 깨진다(api-spec.md 3.2절).
 *
 * <p>서버 세션을 쓰지 않는다(concurrency-spec.md 0.4절). 사용자 식별은
 * {@code X-User-Id} 헤더로만 한다.
 */
@RestController
public class HoldController {

    private final SeatHoldService seatHoldService;
    private final IdempotencyService idempotencyService;

    public HoldController(SeatHoldService seatHoldService, IdempotencyService idempotencyService) {
        this.seatHoldService = seatHoldService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping("/api/holds")
    public ResponseEntity<?> createHold(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                        @RequestHeader("X-User-Id") long userId,
                                        @RequestBody HoldRequestBody request) {
        return idempotencyService.execute(idempotencyKey, "POST", "/api/holds", request, () -> {
            // holdId는 서버가 만든다. 클라이언트가 정하면 남의 홀드 그룹에
            // 끼어들 수 있고, U-6(홀드 그룹당 예약 1건)도 클라이언트 손에 넘어간다.
            String holdId = UUID.randomUUID().toString();
            HoldResult result = seatHoldService.hold(
                    request.sessionId(), userId, request.seatIds(), holdId);

            Instant now = Instant.now();
            List<Long> ordered = request.seatIds().stream().distinct().sorted().toList();
            HoldResponse body = new HoldResponse(
                    holdId,
                    request.sessionId(),
                    userId,
                    ordered,
                    result.heldUntil(),
                    (int) Math.max(0, Duration.between(now, result.heldUntil()).toSeconds()),
                    now);
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        });
    }

    /**
     * 선점 해제. 이미 해제·만료된 홀드에도 204를 반환한다 — 재시도가 실패로
     * 보이지 않아야 한다(api-spec.md 6.1절).
     */
    @DeleteMapping("/api/holds/{holdId}")
    public ResponseEntity<?> releaseHold(@PathVariable String holdId,
                                         @RequestHeader("Idempotency-Key") String idempotencyKey,
                                         @RequestHeader("X-User-Id") long userId) {
        return idempotencyService.execute(
                idempotencyKey, "DELETE", "/api/holds/" + holdId, null, () -> {
                    seatHoldService.release(holdId, userId);
                    return ResponseEntity.noContent().build();
                });
    }
}
