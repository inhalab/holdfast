package com.inhalab.holdfast.catalog;

import com.inhalab.holdfast.seat.Program;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 예약 가능한 프로그램 목록. 사용자가 "무엇을 예약할지" 고르는 첫 단계다.
 *
 * <p>{@code seat/}의 {@link Program} 엔티티를 읽기만 하는 별도 저장소다 —
 * 그 패키지의 파일은 고치지 않는다(#79·#80·#82에서 확립한 경계).
 */
public interface CatalogProgramRepository extends JpaRepository<Program, Long> {

    List<Program> findAllByOrderByIdAsc();
}
