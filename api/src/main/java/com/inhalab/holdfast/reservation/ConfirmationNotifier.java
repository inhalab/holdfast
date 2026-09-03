package com.inhalab.holdfast.reservation;

import java.time.Instant;

/**
 * 확정 사실을 알림 경로에 넘긴다. REQ-05(국립 SFR-003), 이슈 #78.
 *
 * <h2>왜 인터페이스인가 — 의존 방향</h2>
 *
 * <p>구현은 {@code notification/}에 있고 이 인터페이스는 {@code reservation/}에
 * 있다. <b>확정 코드가 알림 구현을 알지 않게 하려는 것이다.</b> 컴파일 의존은
 * {@code notification/} → {@code reservation/} 한 방향으로만 흐르고, 담당 경계
 * (roles.md 디렉토리 표)와도 어긋나지 않는다.
 *
 * <h2>왜 이벤트가 아닌가</h2>
 *
 * <p>{@code ApplicationEventPublisher} + {@code @TransactionalEventListener}로
 * 같은 분리를 얻을 수 있고 그쪽이 흔한 답이다. 쓰지 않은 이유는 하나다 —
 * <b>{@code BEFORE_COMMIT}이면 확정과 원자적이고 {@code AFTER_COMMIT}이면
 * 아니다.</b> 설계 전체가 그 한 단어에 달리고, 틀려도 정상 동작처럼 보이다가
 * 프로세스가 죽는 순간에만 드러난다.
 *
 * <p>이 프로젝트는 같은 계열의 위험을 이미 한 번 다뤘다 —
 * concurrency-spec.md 5.2 "분산락 해제는 커밋 이후"이며, 거기서도
 * "흔한 실수이며 단위 테스트로는 잡히지 않고 부하 테스트에서만 드러난다"고
 * 적었다. 이미 아는 함정을 새 자리에 하나 더 들일 이유가 없다.
 *
 * <p>직접 호출이면 트랜잭션 경계가 코드 순서 그대로다. 알림 종류가 여럿이 되면
 * 그때 이벤트로 옮기는 것이 맞다.
 *
 * <h2>호출 위치는 확정 그 자체다</h2>
 *
 * <p>{@link ReservationService#confirm}이 부른다. 호출부(컨트롤러나
 * {@code PaymentService})가 아니라 확정 안이어야 하는 이유가 둘이다.
 *
 * <p><b>확정 경로가 둘이다.</b> {@code POST /api/reservations}(결제 없는 확정,
 * M3 측정 시나리오가 쓰는 경로)와 {@code POST /api/payments}(승인 → 확정)가
 * 모두 {@code confirm()}을 지난다. 호출부에 두면 두 곳에 써야 하고, 한쪽을
 * 빠뜨리면 조용히 알림이 안 나간다.
 *
 * <p><b>컨트롤러 계층은 트랜잭션 밖이다.</b> {@link IdempotencyService}는
 * {@code @Transactional}이 아니고 {@link IdempotencyStore}는 전부
 * {@code REQUIRES_NEW}다. 컨트롤러에서 INSERT하면 확정이 이미 커밋된 뒤가 되어,
 * 그 사이에 앱이 죽으면 <b>확정은 됐는데 알림 행이 없다.</b> Outbox 패턴의
 * 전제가 정확히 그 원자성이다.
 */
public interface ConfirmationNotifier {

    /**
     * 확정된 예약의 알림을 큐에 넣는다. <b>호출자의 트랜잭션 안에서 실행된다</b> —
     * 확정이 롤백되면 이 행도 함께 사라져야 한다.
     *
     * <p>인자가 원시값인 것은 의도적이다. <b>구현이 무엇을 더 조회하지 않도록</b>
     * 확정 시점에 이미 손에 있는 값만 넘긴다. 좌석 목록 같은 것을 넣으려면
     * 질의가 하나 더 늘고, 그 비용은 이 프로젝트에서 가장 경합이 심한 구간
     * (CS-2)에 붙는다.
     */
    void notifyConfirmed(long reservationId, long sessionId, long userId, Instant confirmedAt);
}
