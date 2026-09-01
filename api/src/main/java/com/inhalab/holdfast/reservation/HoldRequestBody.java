package com.inhalab.holdfast.reservation;

import java.util.List;

/**
 * openapi.yaml {@code HoldRequest}. {@code POST /api/holds}의 요청 본문이다.
 *
 * <p>{@code userId}는 본문이 아니라 {@code X-User-Id} 헤더로 온다 —
 * 인증이 붙으면 그 헤더가 토큰에서 유도된 값으로 대체되고 본문은 그대로다.
 */
public record HoldRequestBody(Long sessionId, List<Long> seatIds) {
}
