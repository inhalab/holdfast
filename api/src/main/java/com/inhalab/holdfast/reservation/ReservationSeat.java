package com.inhalab.holdfast.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 예약에 속한 좌석 N건. docs/erd.md 2절 REQ-01, REQ-09.
 *
 * U-7 {@code ux_reservation_seat_reservation_inventory}
 * (reservation_id, seat_inventory_id)가 한 예약 안의 좌석 중복을 막는다.
 */
@Entity
@Table(name = "reservation_seat")
public class ReservationSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Column(name = "seat_inventory_id", nullable = false)
    private Long seatInventoryId;

    protected ReservationSeat() {
    }

    public Long getId() {
        return id;
    }

    public Long getReservationId() {
        return reservationId;
    }

    public void setReservationId(Long reservationId) {
        this.reservationId = reservationId;
    }

    public Long getSeatInventoryId() {
        return seatInventoryId;
    }

    public void setSeatInventoryId(Long seatInventoryId) {
        this.seatInventoryId = seatInventoryId;
    }
}
