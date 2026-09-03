package com.inhalab.holdfast.ticket;

/**
 * 검표 판정. erd.md {@code ticket_scan.result}의 열거형을 그대로 쓴다.
 *
 * <p><b>거절도 이력으로 남긴다.</b> U-11은 성공 입장({@code ADMITTED})만 티켓당
 * 1건으로 제한하는 부분 유니크 인덱스이므로, 실패 스캔은 몇 번이든 기록된다
 * (erd.md 3절). "언제 몇 번 거절됐는가"가 검표 도메인에서는 그 자체로 정보다.
 */
public enum ScanResult {

    /** 입장 허용. 티켓당 1건만 존재할 수 있다(U-11). */
    ADMITTED,

    /** 이미 입장에 쓰인 티켓이다. */
    REJECTED_DUPLICATE,

    /** 회차의 입장 가능 시간이 아니다(REQ-06). */
    REJECTED_TIME,

    /** 토큰이 없거나, 예약이 취소됐거나, 티켓이 무효다. */
    REJECTED_INVALID
}
