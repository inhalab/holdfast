package com.inhalab.holdfast.ticket;

/**
 * 티켓 상태. design-spec.md 3.3절 · erd.md {@code ticket.status}.
 *
 * <h2>{@code VOID}를 두지 않는다</h2>
 *
 * <p>설계 단계에서는 "예약 취소로 무효가 된 티켓"을 {@code VOID}로 잡았다.
 * 구현에서 뺐다 — <b>아무 코드도 그 값을 쓰지 않는데 열거형에만 남아 있으면
 * 시스템이 지키지 않는 약속이 된다.</b> 상태 기계에는 일어나지 않는 전이가
 * 그려지고, 읽는 사람은 없는 상태를 하나 더 머리에 담아야 한다.
 *
 * <p>취소된 예약의 티켓은 <b>검표 시점에 예약 상태를 함께 읽어</b> 막는다 —
 * {@link TicketService#scan}이 {@link ScanResult#REJECTED_INVALID}로 거절한다.
 * 그래서 상태 값이 하나 더 필요하지 않다.
 *
 * <p><b>미리 무효화하지 않는 이유는 비용이다.</b> {@code ISSUED → VOID}를 취소
 * 시점에 실행하려면 {@code reservation/}의 취소 트랜잭션 안에서 이 패키지의
 * 테이블까지 갱신해야 한다. 패키지 경계를 넘는 훅이 생기고, CS-4(취소 시 좌석
 * 반환)의 임계 구역에 쓰기가 하나 늘어난다 — 알림 Outbox의 INSERT 하나를 두고
 * 같은 논의를 했다(concurrency-spec.md 7.8). <b>lazy 검증은 그 비용을 검표
 * 쪽으로 옮긴다.</b> 검표는 경합 구간이 아니다.
 *
 * <p>"왜 거절됐는가"는 상태 플래그가 아니라 {@code ticket_scan.result}에 시각과
 * 함께 남는다. 분석용으로도 그쪽이 낫다 — 몇 번 거절됐는지까지 센다.
 *
 * <p>스키마의 {@code ticket.status}는 {@code varchar(20)}이고 V1 마이그레이션의
 * 주석에 {@code ISSUED/USED/VOID}로 적혀 있다. <b>V1은 이미 적용됐으므로 고치지
 * 않는다</b> — Flyway가 파일 내용으로 체크섬을 내기 때문에, 주석 한 줄을 바꾸면
 * 기존 DB에서 검증이 깨진다. 정본은 이 열거형이다.
 */
public enum TicketStatus {

    /** 발급됨. 검표를 통과할 수 있는 유일한 상태다. */
    ISSUED,

    /** 검표 통과. 재사용은 U-11이 막는다. */
    USED
}
