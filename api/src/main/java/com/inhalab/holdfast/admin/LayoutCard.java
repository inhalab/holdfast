package com.inhalab.holdfast.admin;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 배치도 목록의 한 줄 — {@link LayoutRow}에 구역 구성을 붙인 것(#192).
 *
 * <p>화면이 두 값을 나란히 읽어야 하는데 저장소 쿼리는 둘로 나뉘어 있다
 * ({@code rows()}와 {@code zoneSummariesOf(...)}). 하나로 합친 쿼리를 쓰면
 * 구역 수만큼 배치도 줄이 늘어 집계를 화면에서 다시 접어야 한다 — 그래서
 * 두 번 읽고 여기서 맞춘다.
 */
public record LayoutCard(LayoutRow layout, List<ZoneSummary> zones) {

    /**
     * 목록 전체를 한 번에 맞춘다.
     *
     * <p>구역이 하나도 없는 배치도도 줄이 되어야 한다 — 방금 만들어 아직 구역을
     * 안 넣은 상태가 바로 그것이고, 관리자가 다음에 할 일이 있는 줄이다.
     */
    public static List<LayoutCard> of(List<LayoutRow> rows, List<ZoneSummary> zones) {
        Map<Long, List<ZoneSummary>> byLayout = zones.stream()
                .collect(Collectors.groupingBy(ZoneSummary::seatLayoutId));
        return rows.stream()
                .map(r -> new LayoutCard(r, byLayout.getOrDefault(r.id(), List.of())))
                .toList();
    }
}
