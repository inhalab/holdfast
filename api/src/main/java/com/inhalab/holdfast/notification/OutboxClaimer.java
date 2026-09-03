package com.inhalab.holdfast.notification;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * {@code outbox}에 대한 트랜잭션 단위 연산. {@link OutboxWorker}가 쓴다.
 *
 * <h2>왜 워커와 분리했나</h2>
 *
 * <p><b>워커의 한 사이클은 트랜잭션 셋이다.</b>
 *
 * <pre>
 * 1. 클레임      [트랜잭션]   PENDING → SENDING
 * 2. 발송        [트랜잭션 밖]  외부 호출
 * 3. 결과 기록   [트랜잭션]   SENDING → SENT / PENDING(재시도) / FAILED
 * </pre>
 *
 * <p>2번이 트랜잭션 밖이어야 하는 것이 이 구조의 전부다. 발송이 트랜잭션 안에
 * 있으면 외부 시스템이 느린 동안 DB 커넥션과 행 락을 쥐고 있게 되는데, 그것은
 * 비관적 락이 대기 중 커넥션을 점유하는 것과 같은 문제다
 * (concurrency-spec.md 4.2). 지금은 발송이 Mock이라 즉시 끝나지만 <b>구조가
 * 그 사실에 기대면 안 된다</b> — 실제 발송으로 바꾸는 순간 드러난다.
 *
 * <p>같은 빈 안에서 {@code this.claim()}을 부르면 프록시를 지나지 않아
 * {@code @Transactional}이 먹지 않는다. 그래서 별도 빈이다 — 자기 호출로
 * 트랜잭션이 조용히 사라지는 것이 이 분리를 문서가 아니라 구조로 강제하는
 * 이유다.
 */
@Service
public class OutboxClaimer {

    private final OutboxRepository outboxRepository;

    public OutboxClaimer(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    /**
     * 집을 수 있는 행을 최대 {@code batchSize}개 잡아 {@code SENDING}으로 바꾼다.
     *
     * <p>두 문장이 <b>한 트랜잭션</b> 안에 있어야 한다. {@code FOR UPDATE}가
     * 잡은 행 락은 커밋까지만 유지되므로, 조회와 상태 변경이 나뉘면 그 사이에
     * 다른 워커가 같은 행을 가져간다.
     *
     * @return 집힌 행. 비어 있으면 할 일이 없다는 뜻이다
     */
    @Transactional
    public List<Outbox> claim(int batchSize, int claimTimeoutSeconds) {
        List<Long> ids = outboxRepository.findClaimableIds(claimTimeoutSeconds, batchSize);
        if (ids.isEmpty()) {
            return List.of();
        }
        outboxRepository.markSending(ids);
        // markSending의 rowsAffected를 판정에 쓰지 않는다 — 바로 위에서 FOR UPDATE로
        // 잠근 행이라 질 수 없다. 판정이 필요한 자리는 발송 뒤의 결과 기록이다.
        return outboxRepository.findAllById(ids);
    }

    /**
     * 발송 성공을 기록한다.
     *
     * @return 기록됐으면 {@code true}. {@code false}는 <b>내가 발송하는 동안
     *         클레임이 만료돼 다른 워커가 이 행을 가져갔다</b>는 뜻이다
     */
    @Transactional
    public boolean markSent(long id) {
        return outboxRepository.markSent(id) == 1;
    }

    /**
     * 발송 실패를 기록한다. 재시도가 남았으면 {@code PENDING}으로 되돌리고
     * 다음 시각을 예약하며, 상한을 소진했으면 {@code FAILED}로 내린다.
     *
     * <p><b>상한 판정에 쓰는 {@code retryCount}는 집을 때 읽은 값이다.</b> 그
     * 행은 내가 {@code SENDING}으로 들고 있으므로 그 사이에 아무도 바꾸지
     * 못한다. 클레임이 만료돼 남이 가져갔다면 아래 UPDATE가
     * {@code rowsAffected = 0}으로 지므로 잘못된 값으로 쓰는 일도 없다.
     *
     * @return 기록됐으면 {@code true}
     */
    @Transactional
    public boolean recordFailure(long id, int retryCount, int maxRetries, long backoffMillis) {
        if (retryCount >= maxRetries) {
            return outboxRepository.markFailed(id) == 1;
        }
        return outboxRepository.scheduleRetry(id, backoffMillis) == 1;
    }
}
