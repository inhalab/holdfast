package com.inhalab.holdfast.admin;

/**
 * 배치도 목록에서 보여 줄 구역 한 칸 — "A구역 12석"(#192).
 *
 * <p>{@link ZoneRow}와 닮았지만 <b>범위가 다르다.</b> 저쪽은 배치도 하나를 여는
 * 상세 화면이 쓰고 격자 크기(행·열)까지 읽는다. 이쪽은 <b>목록의 여러 배치도를
 * 한 번에</b> 읽고 이름과 좌석 수만 본다.
 */
public record ZoneSummary(Long seatLayoutId, String name, Long seatCount) {
}
