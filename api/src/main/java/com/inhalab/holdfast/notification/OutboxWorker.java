package com.inhalab.holdfast.notification;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 알림 Outbox 워커. REQ-05(국립 SFR-003), 이슈 #78.
 *
 * <p>미발송 행을 집어 발송을 시도하고 결과를 기록한다. 한 사이클이
 * <b>클레임 → 발송 → 결과 기록</b>이며 가운데만 트랜잭션 밖이다
 * ({@link OutboxClaimer}).
 *
 * <h2>두 인스턴스를 막지 않는다 — 행 단위로 경쟁시킨다</h2>
 *
 * <p>앱 2대에서 워커가 동시에 돈다. 인스턴스 하나만 돌게 하는 방법(리더 선출,
 * advisory lock, 스케줄러 락)도 있지만 <b>쓰지 않는다.</b>
 *
 * <table>
 *   <caption>두 방식</caption>
 *   <tr><th></th><th>인스턴스 단위 배제</th><th>행 단위 경쟁 (채택)</th></tr>
 *   <tr><td>중복 발송</td><td>막힌다</td><td>막힌다 — 조건부 UPDATE</td></tr>
 *   <tr><td>한쪽이 죽으면</td><td><b>리스가 만료될 때까지 아무도 안 돈다</b></td>
 *       <td>다른 쪽이 즉시 이어받는다</td></tr>
 *   <tr><td>처리량</td><td>항상 1대분</td><td>2대분</td></tr>
 *   <tr><td>새로 들이는 것</td><td>리더 선출 기전</td><td>없음 — 이미 쓰는 관용구</td></tr>
 * </table>
 *
 * <p>concurrency-spec.md 6절이 이미 그렇게 정해 두었다 —
 * "{@code SELECT ... FOR UPDATE SKIP LOCKED}로 집는다. 2대가 같은 행을 잡지
 * 않으면서 <b>한쪽이 죽어도 다른 쪽이 이어받는다</b>." 인스턴스 단위로 배제하면
 * 그 문장의 뒷부분을 스스로 깨고, 워커를 <b>단일 장애점</b>으로 만든다.
 *
 * <p>배제가 필요한 것은 "이 작업은 전역에서 한 번만 일어나야 한다"일 때인데,
 * outbox는 그 반대다 — 행이 서로 독립이므로 <b>많이 돌수록 좋다.</b>
 *
 * <h2>재시도 — optimistic과 성격이 다르다</h2>
 *
 * <p>concurrency-spec.md 4.3의 선례를 참고하되 값은 같지 않다. 기다리는 대상이
 * 다르기 때문이다.
 *
 * <table>
 *   <caption>재시도의 성격</caption>
 *   <tr><th></th><th>{@code optimistic} (4.3)</th><th>outbox</th></tr>
 *   <tr><td>기다리는 것</td><td>같은 행을 다투는 <b>다른 요청</b></td>
 *       <td>회복되지 않은 <b>외부 시스템</b></td></tr>
 *   <tr><td>경합 창</td><td>서브밀리초 (7.7.1)</td><td>초~분 단위</td></tr>
 *   <tr><td>기준 간격</td><td>2ms</td><td><b>1000ms</b></td></tr>
 *   <tr><td>상한</td><td>3회</td><td><b>5회</b></td></tr>
 *   <tr><td>대기 방식</td><td>{@code Thread.sleep} — 스레드를 쥔 채</td>
 *       <td><b>{@code next_retry_at}에 적고 놓아준다</b></td></tr>
 * </table>
 *
 * <p><b>대기 방식의 차이가 가장 크다.</b> 4.3은 한 트랜잭션 안에서 재시도하므로
 * 백오프가 곧 스레드 점유이고, 그래서 2ms처럼 짧아야 한다. 여기서는 실패한 행을
 * 놓아주고 다음 시각만 적으므로 <b>기다리는 동안 아무 자원도 쥐지 않는다.</b>
 * 1초를 쉬어도 워커는 그 사이 다른 행을 처리한다. 외부 시스템이 회복할 시간을
 * 실제로 주려면 그쪽이 맞다.
 *
 * <p>상한을 3이 아니라 5로 둔 것도 같은 이유다. 4.3에서 상한을 올리지 않기로 한
 * 근거는 "한 번 진 요청은 상한까지 계속 진다"였는데, 그 근거가 여기에는 없다 —
 * 외부 시스템은 시간이 지나면 회복되므로 <b>나중 시도의 성공 확률이 더 높다.</b>
 * 전량 지수 백오프로 5회면 마지막 시도가 최초 실패로부터 약 30초 뒤다.
 *
 * <p>지터는 4.3과 같은 full jitter다. 워커 2대가 같은 행을 같은 시각에 다시
 * 집으려 몰리는 것을 흩는다.
 */
@Service
public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

    private final OutboxClaimer claimer;
    private final NotificationSender sender;
    private final Counter sentCounter;
    private final Counter retryCounter;
    private final Counter failedCounter;
    private final Counter lostClaimCounter;

    /** 한 사이클에 집는 행 수. */
    @Value("${holdfast.outbox.batch-size:50}")
    private int batchSize;

    /** 재시도 상한. 최초 시도는 포함되지 않는다 — 5면 최대 6번 시도한다. */
    @Value("${holdfast.outbox.max-retries:5}")
    private int maxRetries;

    /** 지수 백오프의 기준 간격(ms). 외부 시스템의 회복을 기다리는 값이다. */
    @Value("${holdfast.outbox.backoff-base-ms:1000}")
    private long backoffBaseMs;

    /** 백오프 상한(ms). 지수로 늘어나는 값이 무한정 커지지 않게 자른다. */
    @Value("${holdfast.outbox.backoff-max-ms:60000}")
    private long backoffMaxMs;

    /**
     * 클레임 만료(초). 이 시간이 지나도 {@code SENDING}인 행은 집은 워커가
     * 죽은 것으로 보고 다른 워커가 되찾는다.
     *
     * <p><b>발송 최대 소요 시간보다 넉넉해야 한다.</b> 짧으면 살아 있는 워커가
     * 발송하는 동안 남이 같은 행을 가져가고, 그때 중복 발송이 난다 — 결과 기록의
     * 조건부 UPDATE가 <b>기록</b>은 막지만 이미 나간 발송을 되돌리지는 못한다.
     * 그것이 이 값을 30초로 두는 이유다.
     */
    @Value("${holdfast.outbox.claim-timeout-seconds:30}")
    private int claimTimeoutSeconds;

    public OutboxWorker(OutboxClaimer claimer, NotificationSender sender, MeterRegistry meterRegistry) {
        this.claimer = claimer;
        this.sender = sender;
        this.sentCounter = Counter.builder("holdfast.outbox.sent")
                .description("발송에 성공해 SENT로 기록된 알림 수")
                .register(meterRegistry);
        this.retryCounter = Counter.builder("holdfast.outbox.retries")
                .description("발송 실패로 재시도를 예약한 누적 횟수")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("holdfast.outbox.failed")
                .description("재시도 상한을 소진해 FAILED로 내린 알림 수")
                .register(meterRegistry);
        this.lostClaimCounter = Counter.builder("holdfast.outbox.lost-claim")
                .description("발송 중 클레임을 잃어 결과를 버린 횟수 — 0이 아니면 클레임 만료가 너무 짧다")
                .register(meterRegistry);
    }

    /**
     * 한 사이클을 돈다. 스케줄러가 주기적으로 부르고, 테스트는 직접 부른다.
     *
     * <p><b>예외를 밖으로 내보내지 않는다.</b> 스케줄러가 예외를 받으면 그 다음
     * 주기가 그대로 도는 구현도 있지만 조용히 멈추는 구현도 있다. 워커가 한 번의
     * 실패로 영영 서지 않도록 여기서 막는다.
     *
     * @return 이 사이클에 발송에 성공한 건수
     */
    public int pollOnce() {
        List<Outbox> claimed;
        try {
            claimed = claimer.claim(batchSize, claimTimeoutSeconds);
        } catch (RuntimeException e) {
            log.warn("outbox 클레임에 실패했다. 다음 주기에 다시 시도한다", e);
            return 0;
        }

        int sent = 0;
        for (Outbox row : claimed) {
            if (dispatch(row)) {
                sent++;
            }
        }
        return sent;
    }

    /** 한 행을 발송하고 결과를 기록한다. @return 발송에 성공하고 기록까지 됐으면 true */
    private boolean dispatch(Outbox row) {
        try {
            // 트랜잭션 밖이다. 여기서 느려져도 DB 커넥션을 쥐지 않는다.
            sender.send(row);
        } catch (RuntimeException e) {
            recordFailure(row, e);
            return false;
        }

        if (!claimer.markSent(row.getId())) {
            // 발송하는 동안 클레임이 만료돼 다른 워커가 가져갔다. 결과를 버린다 —
            // 여기서 덮어쓰면 저쪽의 진행을 지운다. 이 값이 0이 아니면
            // claim-timeout-seconds가 발송 소요 시간에 비해 짧다는 신호다.
            lostClaimCounter.increment();
            log.warn("발송 뒤 클레임을 잃었다 — outbox id={}. claim-timeout-seconds를 확인하라",
                    row.getId());
            return false;
        }
        sentCounter.increment();
        return true;
    }

    private void recordFailure(Outbox row, RuntimeException cause) {
        int retryCount = row.getRetryCount() == null ? 0 : row.getRetryCount();
        boolean exhausted = retryCount >= maxRetries;
        long backoffMillis = exhausted ? 0 : backoffMillis(retryCount);

        boolean recorded = claimer.recordFailure(row.getId(), retryCount, maxRetries, backoffMillis);
        if (!recorded) {
            lostClaimCounter.increment();
            return;
        }

        if (exhausted) {
            failedCounter.increment();
            log.warn("알림 발송을 포기한다 — outbox id={} 재시도 {}회 소진", row.getId(), maxRetries, cause);
        } else {
            retryCounter.increment();
            log.info("알림 발송 실패 — outbox id={} {}회째, {}ms 뒤 재시도. 사유={}",
                    row.getId(), retryCount + 1, backoffMillis, cause.toString());
        }
    }

    /**
     * full jitter 지수 백오프. 4.3과 같은 형태이고 기준 간격만 다르다.
     *
     * <p>{@code Thread.sleep}이 아니라 <b>다음 시각을 계산해 돌려준다.</b>
     * 워커는 이 값을 {@code next_retry_at}에 적고 행을 놓아주므로, 기다리는
     * 동안 스레드도 커넥션도 쥐지 않는다.
     */
    private long backoffMillis(int retryCount) {
        long ceiling = Math.min(backoffMaxMs, backoffBaseMs << Math.min(retryCount, 20));
        // 0이면 즉시 재시도가 되어 실패한 외부 시스템을 그대로 다시 때린다. 최소 1ms.
        return Math.max(1L, ThreadLocalRandom.current().nextLong(ceiling + 1));
    }
}
