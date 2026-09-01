package com.inhalab.holdfast.api;

import org.springframework.http.HttpStatus;

/**
 * API 오류·거절 사유 코드. docs/api-spec.md 3.1절 분류표를 코드로 옮긴 것이다.
 *
 * <p><b>이 열거형이 분류표의 단일 출처다.</b> 같은 표가 세 곳에 흩어져 있다 —
 * openapi.yaml의 {@code ErrorCode} 열거, 여기, 그리고 k6의
 * {@code load-test/scenarios/lib/classify.js}. 세 곳이 어긋나면 측정이 조용히
 * 오염되므로, 값을 바꿀 때는 셋을 함께 고친다.
 *
 * <p>{@link Category}는 응답에 직렬화되지 않는다. k6가 {@code code} 문자열로
 * 태깅해 자기 쪽 분류표로 집계하기 때문이다(api-spec.md 3.2절). 여기에 함께 둔
 * 이유는 <b>분류를 코드 정의 지점에 붙여두기 위해서</b>다 — HTTP 상태만 보고는
 * "정상 거절"과 "락 포기"가 구분되지 않는데(둘 다 409), 그 구분이 7.6 기록
 * 양식의 열을 가른다.
 */
public enum ErrorCode {

    // ── 정상 거절 5종 — 시스템이 제 역할을 한 결과다. 7.6의 409율에 집계된다 ──
    SEAT_ALREADY_SOLD(HttpStatus.CONFLICT, Category.NORMAL_REJECTION, "이미 판매된 좌석입니다."),
    SEAT_HELD_BY_OTHER(HttpStatus.CONFLICT, Category.NORMAL_REJECTION, "다른 사용자가 선점 중인 좌석입니다."),
    HOLD_EXPIRED(HttpStatus.CONFLICT, Category.NORMAL_REJECTION, "홀드가 만료되었습니다."),
    QUOTA_EXCEEDED(HttpStatus.CONFLICT, Category.NORMAL_REJECTION, "1인 최대 매수를 초과했습니다."),
    RESERVATION_NOT_OPEN(HttpStatus.CONFLICT, Category.NORMAL_REJECTION, "예약 오픈 전입니다."),

    // ── 락 포기 2종 — 409로 응답하지만 409율이 아니라 락 포기율 열로 간다(7.6.1) ──
    LOCK_TIMEOUT(HttpStatus.CONFLICT, Category.LOCK_GIVEUP, "락 대기 시간을 초과했습니다."),
    RETRY_EXHAUSTED(HttpStatus.CONFLICT, Category.LOCK_GIVEUP, "재시도 상한을 소진했습니다."),

    // ── 상태 거절 — 경합이 아니다. 어느 열에도 넣지 않는다 ──
    HOLD_RELEASED(HttpStatus.CONFLICT, Category.STATE_REJECTION, "이미 해제된 홀드입니다."),
    HOLD_ALREADY_CONFIRMED(HttpStatus.CONFLICT, Category.STATE_REJECTION, "이미 확정된 홀드입니다."),
    RESERVATION_ALREADY_CANCELLED(HttpStatus.CONFLICT, Category.STATE_REJECTION, "이미 취소된 예약입니다."),
    RESERVATION_NOT_CANCELLABLE(HttpStatus.CONFLICT, Category.STATE_REJECTION, "취소할 수 없는 예약입니다."),

    // ── 클라이언트 오류 — 부하 시나리오에서 나오면 시나리오가 잘못된 것이다 ──
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, Category.CLIENT_ERROR, "같은 Idempotency-Key로 다른 요청이 왔습니다."),
    IDEMPOTENCY_KEY_IN_PROGRESS(HttpStatus.CONFLICT, Category.CLIENT_ERROR, "같은 Idempotency-Key의 요청이 처리 중입니다."),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, Category.CLIENT_ERROR, "요청 형식이 올바르지 않습니다."),
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, Category.CLIENT_ERROR, "회차를 찾을 수 없습니다."),
    SEAT_NOT_IN_SESSION(HttpStatus.NOT_FOUND, Category.CLIENT_ERROR, "해당 회차의 좌석이 아닙니다."),
    HOLD_NOT_FOUND(HttpStatus.NOT_FOUND, Category.CLIENT_ERROR, "홀드를 찾을 수 없습니다."),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, Category.CLIENT_ERROR, "예약을 찾을 수 없습니다."),

    // ── 서버 오류 — 부하 테스트에서 한 건이라도 나오면 측정값이 아니라 결함이다 ──
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, Category.SERVER_ERROR, "예기치 못한 오류가 발생했습니다.");

    /** api-spec.md 3.1절의 "분류" 열. 응답에 직렬화되지 않는다. */
    public enum Category {
        /** 7.6 기록 양식의 409율. 실패가 아니다. */
        NORMAL_REJECTION,
        /** 7.6.1 락 포기율. 정상 거절도 오류도 아니다. */
        LOCK_GIVEUP,
        /** 경합이 아닌 상태 문제. 어느 열에도 넣지 않는다. */
        STATE_REJECTION,
        /** 클라이언트 잘못. 어느 열에도 넣지 않는다. */
        CLIENT_ERROR,
        /** 7.6 오류율. 5xx만 여기 온다. */
        SERVER_ERROR
    }

    private final HttpStatus status;
    private final Category category;
    private final String defaultDetail;

    ErrorCode(HttpStatus status, Category category, String defaultDetail) {
        this.status = status;
        this.category = category;
        this.defaultDetail = defaultDetail;
    }

    public HttpStatus status() {
        return status;
    }

    public Category category() {
        return category;
    }

    /** 사용자에게 그대로 보여줄 수 있는 한국어 문장(api-spec.md 3.4절). */
    public String defaultDetail() {
        return defaultDetail;
    }
}
