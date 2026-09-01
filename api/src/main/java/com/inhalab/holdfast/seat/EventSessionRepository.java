package com.inhalab.holdfast.seat;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 회차 조회. 좌석맵·상태 스냅샷 응답의 존재 확인 및 메타 정보 출처다.
 *
 * <p>이 저장소가 노출하는 메서드는 전부 단순 SELECT다. 락을 잡지 않는다 —
 * 좌석·회차 조회는 경합 대상이 아니다.
 */
public interface EventSessionRepository extends JpaRepository<EventSession, Long> {
}
