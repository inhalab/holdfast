package com.inhalab.holdfast.reservation;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import java.util.Optional;

/**
 * 멱등 기록의 저장 연산. <b>모두 별도 트랜잭션({@code REQUIRES_NEW})에서 돈다.</b>
 *
 * <p>선점 표시가 본 작업과 같은 트랜잭션에 있으면 커밋 전까지 다른 요청에게
 * 보이지 않는다. 그러면 같은 키의 동시 요청이
 * {@code IDEMPOTENCY_KEY_IN_PROGRESS}로 즉시 거절되지 못하고 유니크 인덱스에서
 * 블로킹된다 — 커넥션을 쥔 채 기다리게 되어 부하 측정에서 락 대기와 구분되지
 * 않는다. 그래서 표시만 먼저 커밋한다.
 */
@Component
public class IdempotencyStore {

    private final IdempotencyRecordRepository repository;
    private final EntityManager entityManager;

    public IdempotencyStore(IdempotencyRecordRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    /**
     * 키를 선점한다.
     *
     * <p>{@code ON CONFLICT DO NOTHING}으로 경쟁을 처리한다 — 예외를 던지고 잡는
     * 대신 {@code rowsAffected}로 판정하면, 이미 있는 키가 예외 경로를 타지 않아
     * 로그와 메트릭이 깨끗하게 유지된다.
     *
     * @return 선점에 성공했으면 {@code true}. 이미 있으면 {@code false}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(String key, String requestHash) {
        int inserted = entityManager.createNativeQuery("""
                        INSERT INTO idempotency_record (idempotency_key, request_hash, created_at)
                        VALUES (:key, :hash, now())
                        ON CONFLICT (idempotency_key) DO NOTHING
                        """)
                .setParameter("key", key)
                .setParameter("hash", requestHash)
                .executeUpdate();
        return inserted == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyRecord> find(String key) {
        return repository.findByIdempotencyKey(key);
    }

    /** 확정된 결과를 남긴다. 이후 같은 키의 요청은 이 응답을 그대로 재생한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    public void storeResponse(String key, int status, String body) {
        entityManager.createNativeQuery("""
                        UPDATE idempotency_record
                           SET response_status = :status, response_body = :body
                         WHERE idempotency_key = :key
                        """)
                .setParameter("status", status)
                .setParameter("body", body)
                .setParameter("key", key)
                .executeUpdate();
    }

    /**
     * 선점을 취소한다. <b>결과가 확정되지 않은 경우에만</b> 쓴다 — 서버 결함으로
     * 작업이 중단되면 그 키는 아무 결과도 남기지 못했으므로, 표시를 지워 클라이언트가
     * 같은 키로 다시 시도할 수 있게 한다. 지우지 않으면 그 키는 영원히
     * {@code IDEMPOTENCY_KEY_IN_PROGRESS}에 갇힌다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    public void release(String key) {
        entityManager.createNativeQuery("DELETE FROM idempotency_record WHERE idempotency_key = :key")
                .setParameter("key", key)
                .executeUpdate();
    }
}
