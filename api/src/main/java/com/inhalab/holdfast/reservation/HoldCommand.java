package com.inhalab.holdfast.reservation;

import java.util.List;

/**
 * 좌석 홀드 요청. {@link SeatHoldStrategy}가 받는 입력이다.
 *
 * <p>concurrency-spec.md 4절의 시그니처
 * {@code hold(long sessionId, List<Long> seatIds, String holdId)}가 정한 것은
 * "전략이 무엇을 받는가"이지 파라미터 나열 방식이 아니다. 같은 정보를 커맨드
 * 객체에 담아 넘긴다.
 *
 * <p><b>나열 대신 객체로 묶은 이유는 측정 신뢰도다.</b> {@code sessionId}와
 * {@code userId}가 둘 다 {@code long}이라 나열 방식에서는 순서를 바꿔 넘겨도
 * 컴파일이 통과한다. 그 버그는 엉뚱한 사용자·회차로 홀드를 잡아 초과 예약을
 * 만들면서 "락 전략이 샌 것"과 구분되지 않고, 결국 측정 결과 전체를 의심하게
 * 만든다. concurrency-spec.md 7.1이 지표별 출처를 못박은 것과 같은 이유로
 * 원인이 뒤섞이는 경로를 사전에 차단한다.
 *
 * <p><b>TTL은 여기 없다.</b> 홀드 유지 시간은 각 전략이
 * {@code holdfast.hold-ttl-seconds}로 직접 주입받는다. 호출 측이 TTL을 정하면
 * 전략마다 다른 값이 들어갈 여지가 생겨 7.3 고정 변수 통제가 깨진다.
 *
 * @param seatIds 좌석 ID. <b>오름차순으로 정렬된 상태로 들어온다</b> —
 *                정렬은 전략 밖 공통 단계가 책임진다(5.1, {@link SeatHoldService}).
 */
public record HoldCommand(
        long sessionId,
        long userId,
        List<Long> seatIds,
        String holdId
) {
}
