package com.inhalab.holdfast.seat;

import java.time.Instant;
import java.util.List;

/**
 * openapi.yaml {@code SeatStatusSnapshot}. {@code GET .../seats/status} 응답이다.
 *
 * <p>{@code heldUntil}이 없다 — 값이 매초 바뀌어 {@code ETag}가 사실상 항상
 * 달라지므로 304의 이점이 사라지고, 남의 홀드가 언제 끝나는지는 화면에 필요
 * 없다(api-spec.md 5.1절).
 */
public record SeatStatusSnapshotResponse(
        Long sessionId,
        Instant serverTime,
        List<SeatStatusEntryResponse> seats
) {
}
