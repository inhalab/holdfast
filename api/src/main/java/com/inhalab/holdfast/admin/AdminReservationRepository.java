package com.inhalab.holdfast.admin;

import com.inhalab.holdfast.reservation.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 예약 목록 조회. {@link Reservation}은 {@code reservation/} 소유 엔티티이지만
 * 이 저장소는 이 패키지({@code admin/})에 둔다 — 관리자 조회 전용 쿼리이고,
 * 그 파일을 고치지 않는다(#79·#80과 같은 경계).
 *
 * <p>최근 200건으로 제한한다. 관리자 화면이지 페이지네이션이 필요한 운영
 * 대시보드가 아니다(design-spec 5.3 — 회차·좌석배치 등록도 여유로 내린
 * 이 이슈의 축소 범위와 같은 판단).
 */
public interface AdminReservationRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findTop200ByOrderByIdDesc();
}
