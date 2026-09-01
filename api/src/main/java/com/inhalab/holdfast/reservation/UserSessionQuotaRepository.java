package com.inhalab.holdfast.reservation;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * CS-6 사용자 할당량 저장소. concurrency-spec.md 1.1절.
 */
public interface UserSessionQuotaRepository extends JpaRepository<UserSessionQuota, Long> {

    /**
     * {@code (회차, 사용자)} 할당량 행을 <b>잠그고</b> 읽는다.
     *
     * <p>여기에 락이 있는 것은 {@code none} 베이스라인에서도 마찬가지다.
     * CS-6은 락 전략 비교 대상이 <b>아니며</b>(1.1절) 5종 전부에서 동일하게
     * 처리해 변수를 늘리지 않는다. 좌석 경합(CS-1)에 락을 걸지 않는 것과
     * 별개의 축이다 — 1인 최대 매수는 좌석 단위 락으로는 애초에 막히지 않는다.
     *
     * <p>전역 락 순서는 <b>사용자 할당량 행 → 좌석 행(오름차순)</b>이다(5.1).
     * 이 메서드가 그 순서의 첫 단계다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM UserSessionQuota q WHERE q.sessionId = :sessionId AND q.userId = :userId")
    Optional<UserSessionQuota> findForUpdate(@Param("sessionId") long sessionId,
                                             @Param("userId") long userId);
}
