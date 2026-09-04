package com.inhalab.holdfast.catalog;

import java.time.Instant;

/**
 * 회차를 사용자에게 어떻게 보여줄지. 이슈 #108 — SFR-006의 검수 기준이
 * <b>"접수종료 노출 방식이 운영정책과 일치"</b>이므로, 판단 근거를 화면 밖으로
 * 꺼내 이름을 붙이고 규칙을 {@link #of}에 한 벌만 둔다.
 *
 * <h2>노출 정책</h2>
 *
 * <p><b>1. 감추지 않고 상태를 적는다.</b> 매진·종료된 회차를 목록에서 지우면
 * "원래 없었는지 이미 끝났는지"를 사용자가 구분할 수 없다. 문의가 늘고, 예약한
 * 사람이 자기 회차를 목록에서 못 찾는다. 실제 예약 사이트도 접수종료를 남겨 두고
 * 표시만 바꾼다.
 *
 * <p><b>2. 막힐 것은 누르기 전에 알린다.</b> 오픈 전 회차의 홀드는 서버가
 * {@code RESERVATION_NOT_OPEN}으로 거절하지만(REQ-08), 그 사실이 화면에 없으면
 * 사용자는 좌석을 고르고 버튼을 눌러야 알게 된다. 목록과 좌석맵이 <b>둘 다</b>
 * 미리 적는다.
 *
 * <p><b>3. 화면은 서버 판정을 대신하지 않는다.</b> 여기서 정하는 것은 보여 주는
 * 방식뿐이다. 링크로 곧장 들어와도 홀드는 서버가 다시 판정하며, 그 판정이 정본이다
 * — 이 열거형은 그 판정을 <b>앞당겨 알려 줄 뿐</b>이다.
 *
 * <h2>{@code event_session.status}와 잔여석을 어떻게 쓰는가</h2>
 *
 * <p>이슈가 정하라고 한 것이다. <b>둘 다 쓰되 순서를 둔다</b> —
 * {@code status}가 먼저고 잔여석이 나중이다. {@code status}는 운영자가 직접 정한
 * 값이라 사실보다 앞서고, 잔여석은 그 회차가 열려 있을 때만 뜻이 있다.
 * 닫힌 회차는 자리가 남아도 닫힌 것이고, 오픈 전 회차는 매진일 수 없다.
 */
public enum SaleState {

    /** 지금 좌석을 고를 수 있다. */
    ON_SALE,

    /** 예약 오픈 전. 서버도 홀드를 {@code RESERVATION_NOT_OPEN}으로 막는다(REQ-08). */
    NOT_YET_OPEN,

    /** 잔여 좌석 0. */
    SOLD_OUT,

    /** 회차가 닫혔다. */
    CLOSED;

    /** {@code event_session.status} — erd.md 2절. */
    static final String SESSION_OPEN = "OPEN";
    static final String SESSION_SCHEDULED = "SCHEDULED";

    /** 이 상태에서 좌석을 고를 수 있는가. 화면 셋이 같은 답을 쓰게 하는 지점이다. */
    public boolean sellable() {
        return this == ON_SALE;
    }

    /**
     * 노출 상태를 정한다. <b>회차 목록과 좌석맵이 이 메서드 하나를 함께 쓴다</b> —
     * 목록에서는 "접수 종료"인데 들어가 보면 좌석이 눌리는 어긋남을 없애려면
     * 규칙이 한 벌이어야 한다.
     *
     * <p><b>이유를 하나로 뭉치지 않는다.</b> "오픈 전"과 "종료"를 같은 조건으로
     * 묶으면 닫힌 회차에도 오픈 일시가 찍힌다 — 실제로 그렇게 만들었다가 고쳤다.
     *
     * @param sessionStatus  {@code event_session.status}
     * @param reserveOpensAt 예약 오픈 시각. {@code null}이면 시각 제한이 없다
     * @param availableSeats 잔여 좌석 수
     * @param now            판단 기준 시각
     */
    public static SaleState of(String sessionStatus,
                               Instant reserveOpensAt,
                               long availableSeats,
                               Instant now) {
        if (!SESSION_OPEN.equals(sessionStatus)) {
            // SCHEDULED는 아직 열리지 않은 것이고 CLOSED는 끝난 것이다.
            return SESSION_SCHEDULED.equals(sessionStatus) ? NOT_YET_OPEN : CLOSED;
        }
        if (reserveOpensAt != null && now.isBefore(reserveOpensAt)) {
            return NOT_YET_OPEN;
        }
        return availableSeats > 0 ? ON_SALE : SOLD_OUT;
    }
}
