package com.inhalab.holdfast.seat;

/**
 * 서비스 → 컨트롤러 내부 전달용. JSON으로 나가지 않는다.
 *
 * <p>{@code etag}를 본문과 함께 묶는 이유는 컨트롤러가 {@code WebRequest
 * .checkNotModified(etag)}로 304 판정을 하려면 본문을 만들기 전에 ETag를
 * 알아야 하기 때문이다 — 둘 다 같은 조회 결과에서 나오므로 서비스가 한 번에
 * 계산해서 함께 돌려준다.
 */
public record SeatStatusSnapshotResult(SeatStatusSnapshotResponse snapshot, String etag) {
}
