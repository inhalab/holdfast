package com.inhalab.holdfast.notification;

/**
 * 알림을 실제로 내보내는 지점. 이슈 #78 — 구현은 Mock이다.
 *
 * <p>인터페이스로 둔 이유는 <b>이 경계가 트랜잭션 밖이라는 사실을 코드에
 * 드러내기 위해서다.</b> {@link OutboxWorker}는 이 호출을 클레임 트랜잭션과
 * 결과 기록 트랜잭션 <b>사이</b>에서 한다. 여기에 DB 접근을 넣으면 그 구조가
 * 무너지므로, 구현이 저장소를 알지 못하게 막는다.
 *
 * <p>실제 발송(SMS·카카오)은 design-spec.md 4.3의 제외 항목이다 — 발송 대행
 * 계약이 필요해 Mock으로 대체한다.
 */
public interface NotificationSender {

    /**
     * 알림 한 건을 발송한다.
     *
     * <p><b>실패는 예외로 알린다.</b> 실제 발송이라면 HTTP 클라이언트가 그렇게
     * 던지고, 워커는 어차피 모든 예외를 실패로 처리해야 한다. 반환값과 예외
     * 두 갈래를 두면 한쪽을 빠뜨렸을 때 실패가 성공으로 기록된다.
     *
     * @param row 발송 대상. {@code SENDING}으로 집힌 행이다
     * @throws RuntimeException 발송 실패. 워커가 재시도를 예약한다
     */
    void send(Outbox row);
}
