package com.inhalab.holdfast.notification;

import com.inhalab.holdfast.reservation.ConfirmationNotifier;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * {@link ConfirmationNotifier}의 Outbox 구현. REQ-05, 이슈 #78.
 *
 * <p>확정 트랜잭션 안에서 {@code outbox} 행 하나를 {@code PENDING}으로 넣는다.
 * <b>발송은 하지 않는다</b> — 그것은 {@link OutboxWorker}의 일이고, 둘을 나눈
 * 것이 Outbox 패턴 그 자체다. 확정 트랜잭션이 외부 발송을 기다리면 CS-2의
 * 임계 구역이 외부 시스템의 응답 시간만큼 길어진다.
 *
 * <h2>제약 위반을 잡지 않는다</h2>
 *
 * <p>U-12 위반({@code ux_outbox_reservation_notification})은
 * {@code DataIntegrityViolationException}으로 그대로 올라간다. 잡아서 무시하면
 * <b>같은 예약이 두 번 확정됐다는 신호가 사라진다.</b>
 *
 * <p>정상 경로에서는 일어날 수 없다 — {@code ReservationService#confirm}이
 * {@code HOLD_ALREADY_CONFIRMED}로 먼저 막는다. 그래서 여기서 터진다면 그
 * 방어가 샌 것이고, {@code unique} 전략에서 제약 위반이 "앱 락이 샜다"는
 * 신호인 것과 같은 성격이다(concurrency-spec.md 4.4).
 *
 * <h2>페이로드는 손에 있는 값만 담는다</h2>
 *
 * <p>좌석 목록이나 회차 이름을 넣으려면 질의가 늘고, 그 비용은 이 프로젝트에서
 * 가장 경합이 심한 구간에 붙는다({@link ConfirmationNotifier}). 발송 시점에
 * 필요하면 워커가 {@code reservation_id}로 조회하면 된다 — 그쪽은 임계 구역이
 * 아니다.
 */
@Component
public class OutboxConfirmationNotifier implements ConfirmationNotifier {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public OutboxConfirmationNotifier(OutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void notifyConfirmed(long reservationId, long sessionId, long userId, Instant confirmedAt) {
        String payload = objectMapper.writeValueAsString(
                new ConfirmedPayload(reservationId, sessionId, userId, confirmedAt));
        outboxRepository.insertPending(
                reservationId, NotificationType.RESERVATION_CONFIRMED.name(), payload);
    }

    /** {@code outbox.payload}에 들어가는 모양. 발송 시점에 워커가 읽는다. */
    record ConfirmedPayload(long reservationId, long sessionId, long userId, Instant confirmedAt) {
    }
}
