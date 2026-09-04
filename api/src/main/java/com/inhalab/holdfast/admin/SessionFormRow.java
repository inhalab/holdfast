package com.inhalab.holdfast.admin;

/**
 * 회차 수정 폼 한 줄. 시각은 {@code datetime-local} 입력에 그대로 넣을 수 있게
 * <b>한국 시각 문자열로 미리 변환해</b> 담는다({@code yyyy-MM-ddTHH:mm}).
 *
 * <p>템플릿에서 {@code Instant}를 포맷하지 않는 이유는, {@code Instant}가 날짜·시각
 * 필드를 직접 갖지 않아 표현식에서 다루기 번거롭고 시간대 기준이 화면에 흩어지기
 * 때문이다. 변환 기준은 컨트롤러 한 곳에만 둔다.
 */
public record SessionFormRow(
        Long id,
        Long seatLayoutId,
        String startsAt,
        String endsAt,
        String entryOpensAt,
        String entryClosesAt,
        String reserveOpensAt,
        Integer maxPerUser,
        String status
) {
}
