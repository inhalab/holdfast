package com.inhalab.holdfast.ticket;

import com.inhalab.holdfast.reservation.ReservationSeat;
import com.inhalab.holdfast.reservation.ReservationSeatRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 티켓 발급과 검표. docs/state-transitions.md 4절 — 이 도메인의 상태 기계는
 * "부분 확정"이었고 전이를 일으키는 행위의 세부는 이 작업(#80)에서 확정한다고
 * 그 문서가 명시했다. 아래 두 판단이 그 확정이다.
 */
@Service
public class TicketService {

    private final TicketRepository ticketRepository;
    private final ReservationSeatRepository reservationSeatRepository;
    private final TicketScanRecorder scanRecorder;

    public TicketService(TicketRepository ticketRepository,
                         ReservationSeatRepository reservationSeatRepository,
                         TicketScanRecorder scanRecorder) {
        this.ticketRepository = ticketRepository;
        this.reservationSeatRepository = reservationSeatRepository;
        this.scanRecorder = scanRecorder;
    }

    /**
     * 확정된 예약좌석마다 티켓을 발급한다.
     *
     * <p><b>확정과 같은 트랜잭션에서, 결제 승인 직후 호출된다</b>
     * ({@code PaymentService#pay}) — state-transitions 4절이 잠정으로 남긴
     * "확정 트랜잭션과 같은 트랜잭션인지"에 대한 답이다. 별도 비동기로
     * 미루면 "결제는 승인됐는데 티켓이 없는" 창이 생기고, 이 창을 재현하는
     * 것 자체는 M3가 이미 결제·확정 경합으로 다룬 문제의 변형이라 M4에서
     * 새로 만들 이유가 없다.
     *
     * <p>{@code reservation/}을 수정하지 않는다 — 이미 공개된
     * {@link ReservationSeatRepository}를 읽기만 한다(#79와 같은 경계).
     */
    @Transactional
    public void issueTickets(long reservationId) {
        List<ReservationSeat> seats = reservationSeatRepository.findByReservationId(reservationId);
        Instant now = Instant.now();
        for (ReservationSeat seat : seats) {
            Ticket ticket = new Ticket();
            ticket.setReservationSeatId(seat.getId());
            ticket.setQrToken(UUID.randomUUID().toString());
            ticket.setStatus(TicketStatus.ISSUED.name());
            ticket.setIssuedAt(now);
            ticketRepository.save(ticket);
        }
    }

    /** 예약 확인·티켓 화면(#81)이 쓸 목록. */
    @Transactional(readOnly = true)
    public List<TicketRow> ticketsOf(long reservationId) {
        return ticketRepository.findRowsByReservationId(reservationId);
    }

    /**
     * 검표. QR 토큰 하나로 판정하며 결과는 전부 200이다 — 거절도 오류가 아닌
     * 판정이다({@link TicketScanResponse}).
     *
     * <p><b>예약 취소를 lazy로 검증한다.</b> 취소 시점에 티켓을 무효화하지
     * 않는다 — 취소는 {@code reservation/}의 트랜잭션이고, 그 안에서 이 패키지의
     * 테이블까지 갱신하게 만들면 패키지 경계를 넘는 훅이 생기며 CS-4의 임계
     * 구역에 쓰기가 하나 늘어난다. 대신 검표 시점에 예약 상태를 함께 읽어
     * {@code REJECTED_INVALID}로 판정한다. 확정 경로가 만료를 lazy로 검증하는
     * 것(concurrency-spec 3절)과 같은 모양이며, <b>그래서 티켓 상태에
     * {@code VOID}가 없다</b>({@link TicketStatus}).
     *
     * <p><b>이 메서드는 {@code @Transactional}이 아니다.</b> 판정마다
     * {@link TicketScanRecorder}의 서로 다른 최상위 트랜잭션을 호출해야
     * 하기 때문이다 — 그 이유는 {@link TicketScanRecorder} 클래스 문서에 있다.
     */
    public TicketScanResponse scan(String qrToken) {
        Instant now = Instant.now();
        var context = ticketRepository.findScanContextByQrToken(qrToken);

        if (context.isEmpty()) {
            // 티켓이 없으면 남길 ticket_scan 행도 없다(ticket_id NOT NULL).
            return reject(null, ScanResult.REJECTED_INVALID, "유효하지 않은 QR 코드입니다.", now);
        }

        TicketScanContext ctx = context.get();

        if (!"CONFIRMED".equals(ctx.reservationStatus())) {
            return reject(ctx.ticketId(), ScanResult.REJECTED_INVALID, "취소된 예약의 티켓입니다.", now);
        }
        if (!TicketStatus.ISSUED.name().equals(ctx.ticketStatus())) {
            // 이미 USED다. U-11까지 갈 것 없이 여기서 걸러진다 — 재고 상태
            // 확인이 제약을 대신하지 않는다는 것과 같은 이유로, 이 확인은
            // "동시에 노린 경합"이 아니라 "늦게 온 재사용 시도"를 거른다
            // (reservation/UniqueSeatHoldStrategy 참고).
            return reject(ctx.ticketId(), ScanResult.REJECTED_DUPLICATE, "이미 사용된 티켓입니다.", now);
        }
        if (now.isBefore(ctx.entryOpensAt()) || now.isAfter(ctx.entryClosesAt())) {
            return reject(ctx.ticketId(), ScanResult.REJECTED_TIME, "입장 가능 시간이 아닙니다.", now);
        }

        try {
            scanRecorder.admit(ctx.ticketId());
            return new TicketScanResponse(ScanResult.ADMITTED, ctx.ticketId(), null, now, Instant.now());
        } catch (DataIntegrityViolationException e) {
            // 동시에 두 게이트에서 스캔된 진짜 경합. U-11이 하나만 통과시켰다.
            return reject(ctx.ticketId(), ScanResult.REJECTED_DUPLICATE, "이미 사용된 티켓입니다.", now);
        }
    }

    private TicketScanResponse reject(Long ticketId, ScanResult result, String reason, Instant scannedAt) {
        if (ticketId != null) {
            scanRecorder.reject(ticketId, result, reason);
        }
        return new TicketScanResponse(result, ticketId, reason, scannedAt, Instant.now());
    }
}
