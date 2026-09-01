package com.inhalab.holdfast.seat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 배치도 내 구역. docs/erd.md 2절 REQ-09.
 *
 * U-3 {@code ux_zone_layout_name} (seat_layout_id, name)이 배치도 내 구역명
 * 중복을 막는다. 제약 자체는 마이그레이션(V1__init_schema.sql)이 건다 — 엔티티는
 * FK 컬럼만 스칼라(Long)로 갖고 {@code @ManyToOne} 객체 그래프는 두지 않는다.
 * 락 전략별로 트랜잭션이 쥐는 시간이 성능 특성을 가르는 프로젝트라, 지연 로딩이
 * 예상치 못한 시점에 추가 쿼리를 발생시켜 락 보유 시간에 끼어드는 것을 피한다.
 */
@Entity
@Table(name = "zone")
public class Zone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "seat_layout_id", nullable = false)
    private Long seatLayoutId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    protected Zone() {
    }

    public Long getId() {
        return id;
    }

    public Long getSeatLayoutId() {
        return seatLayoutId;
    }

    public void setSeatLayoutId(Long seatLayoutId) {
        this.seatLayoutId = seatLayoutId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }
}
