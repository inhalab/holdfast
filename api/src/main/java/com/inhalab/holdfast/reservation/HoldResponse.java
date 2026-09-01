package com.inhalab.holdfast.reservation;

import java.time.Instant;
import java.util.List;

/**
 * openapi.yaml {@code Hold}. {@code POST /api/holds}의 201 응답이다.
 *
 * <p>{@code holdId}와 좌석 목록은 <b>클라이언트가 보관한다.</b> 서버 세션에
 * 저장하지 않는다 — 앱 2대 구성에서 세션 공유는 측정 오염 요인이다
 * (concurrency-spec.md 0.4절).
 *
 * <p>{@code expiresInSeconds}를 함께 주는 이유는 화면이 시계 차이를 계산하지
 * 않고 바로 카운트다운을 걸 수 있게 하기 위해서다.
 */
public record HoldResponse(
        String holdId,
        Long sessionId,
        Long userId,
        List<Long> seatIds,
        Instant heldUntil,
        Integer expiresInSeconds,
        Instant serverTime
) {
}
