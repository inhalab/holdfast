package com.inhalab.holdfast.reservation;

import java.time.Instant;
import java.util.List;

/**
 * {@link SeatHoldStrategy#hold} 결과.
 *
 * <p>전략은 성공·실패를 <b>보고만</b> 한다. 트랜잭션을 되돌리거나 예외를 던지는
 * 판단은 {@link SeatHoldService}가 한다 — 전략마다 롤백 방식이 달라지면
 * 전략 밖 코드 경로가 갈라져 "프로퍼티만 바꿔 측정한다"는 전제가 깨진다.
 *
 * @param heldUntil 성공 시 홀드 만료 시각. DB {@code now()} 기준이다
 *                  (concurrency-spec.md 3절 — 앱 서버 2대의 시계가 어긋나면
 *                  만료 판정이 인스턴스마다 달라진다). 실패면 {@code null}.
 * @param code      실패 시 대표 코드(openapi.yaml {@code ErrorCode}). 성공이면 {@code null}.
 * @param conflicts 좌석별 사유. 성공이면 빈 리스트.
 */
public record HoldResult(
        boolean success,
        Instant heldUntil,
        String code,
        List<SeatConflict> conflicts
) {

    public static HoldResult success(Instant heldUntil) {
        return new HoldResult(true, heldUntil, null, List.of());
    }

    public static HoldResult conflict(String code, List<SeatConflict> conflicts) {
        return new HoldResult(false, null, code, List.copyOf(conflicts));
    }
}
