package com.inhalab.holdfast.admin;

/**
 * 배치도 상세 화면의 구역 한 줄. 이슈 #102.
 *
 * <p>{@code maxRow}·{@code maxCol}은 격자의 크기다. 좌석을 이어 넣을 때 몇 번째
 * 행부터 붙는지를 관리자가 미리 보게 하려고 함께 읽는다 — 좌석맵 화면이
 * {@code rowIndex}·{@code colIndex}로 격자를 그리므로 이 값이 곧 화면 모양이다.
 */
public record ZoneRow(Long id, String name, Integer sortOrder,
                      Long seatCount, Integer maxRow, Integer maxCol) {
}
