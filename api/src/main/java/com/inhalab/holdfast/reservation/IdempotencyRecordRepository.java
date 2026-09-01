package com.inhalab.holdfast.reservation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 멱등키 저장소. concurrency-spec.md 6절, api-spec.md 6절.
 */
public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByIdempotencyKey(String idempotencyKey);
}
