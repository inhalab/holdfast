package com.inhalab.holdfast.notification;

/**
 * {@code outbox.status}. erd.md 2절, V2__outbox_claim.sql.
 *
 * <pre>
 * PENDING ──클레임──> SENDING ──성공──> SENT
 *    ^                   │
 *    └──실패(재시도 남음)─┤
 *                        └──상한 소진──> FAILED
 * </pre>
 *
 * <p><b>{@code FAILED}는 종착이다.</b> 워커가 다시 집지 않는다 — 상한을 소진한
 * 행을 계속 집으면 워커가 영원히 실패하는 행에 매달려, 뒤에 쌓인 정상 행의
 * 발송이 밀린다. 되살리려면 사람이 상태를 되돌려야 한다.
 */
public enum OutboxStatus {

    /** 큐에 들어갔고 아직 집히지 않았다. 확정 트랜잭션이 이 상태로 INSERT한다. */
    PENDING,

    /**
     * 워커가 집었다. {@code claimed_at}이 함께 기록된다.
     *
     * <p>이 상태로 오래 남아 있으면 워커가 집은 뒤 죽었다는 뜻이고, 클레임
     * 만료 시각이 지나면 다른 워커가 되찾는다.
     */
    SENDING,

    /** 발송에 성공했다. 종착. */
    SENT,

    /** 재시도 상한을 소진했다. 종착 — 워커가 다시 집지 않는다. */
    FAILED
}
