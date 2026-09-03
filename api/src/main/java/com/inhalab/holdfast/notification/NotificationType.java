package com.inhalab.holdfast.notification;

/**
 * {@code outbox.notification_type}. U-12 {@code (reservation_id,
 * notification_type)}의 두 번째 열이다.
 *
 * <h2>값 목록을 여기서 고정한다</h2>
 *
 * <p>스키마의 {@code varchar(50)}은 아무 문자열이나 받는다. 값이 코드 여기저기
 * 문자열 리터럴로 흩어지면 오타 하나가 <b>U-12를 우회한다</b> —
 * {@code "RESERVATION_CONFIRMED"}와 {@code "RESERVATION_CONFIRMD"}는 다른
 * 값이므로 같은 예약에 두 행이 들어가고, 중복 발송 방지가 조용히 무력해진다.
 * 열거형으로 두면 컴파일러가 막는다.
 *
 * <h2>최소 완결은 하나뿐이다</h2>
 *
 * <p>이슈 #78·#66 합의에 따라 {@link #RESERVATION_CONFIRMED} 하나만 정의한다.
 * <b>취소 알림은 여유 항목이다.</b> #78의 핵심은 워커 동시성(같은 행을 두
 * 워커가 집지 않는가)인데 알림 종류를 늘려도 그 검증에 더해지는 것이 없다.
 *
 * <p><b>여기가 종류를 추가할 자리다.</b> 추가할 때 함께 봐야 할 것 —
 * U-12가 {@code (reservation_id, notification_type)}이므로 같은 예약에 종류가
 * 다른 행 여러 개는 이미 허용된다. 스키마 변경 없이 상수만 늘리면 된다.
 * 다만 {@code RESERVATION_CANCELLED}를 넣을 때는 <b>어느 트랜잭션에
 * 얹을지</b>를 확정 때와 같은 방식으로 정해야 한다
 * ({@link com.inhalab.holdfast.reservation.ConfirmationNotifier} 참조).
 */
public enum NotificationType {

    /** 예약이 확정됐다. 확정 트랜잭션 안에서 INSERT된다. */
    RESERVATION_CONFIRMED
}
