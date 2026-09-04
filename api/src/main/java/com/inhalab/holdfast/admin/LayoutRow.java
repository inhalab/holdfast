package com.inhalab.holdfast.admin;

/**
 * 좌석배치도 목록 한 줄. 이슈 #102.
 *
 * <p>{@code sessionCount}가 삭제 가능 여부를 가른다 — 회차가 하나라도 이 배치도를
 * 쓰면 좌석·구역·배치도를 지울 수 없다. 근거는
 * {@link AdminSeatLayoutService} 클래스 주석의 "삭제 정책".
 */
public record LayoutRow(Long id, String name, Long zoneCount, Long seatCount, Long sessionCount) {

    public boolean isDeletable() {
        return sessionCount == 0;
    }
}
