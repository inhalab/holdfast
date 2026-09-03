package com.inhalab.holdfast.ticket;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 검표 판정 한 건을 커밋한다. {@link TicketService}와 별도 빈으로 둔 이유는
 * 트랜잭션 경계 때문이다.
 *
 * <h2>왜 별도 빈인가</h2>
 *
 * <p>{@code reservation/UniqueSeatHoldStrategy}의 교훈을 그대로 따른다 —
 * <b>제약 위반이 나면 Postgres 트랜잭션이 이미 중단 상태이므로, 같은 트랜잭션
 * 안에서 잡아 다음 문장으로 넘어가려 해도 그 문장이 실패한다.</b>
 *
 * <p>다만 좌석 홀드와 달리 검표의 중복 사용은 <b>오류가 아니라 정상 판정</b>
 * ({@code REJECTED_DUPLICATE})이므로, 예외를 공통 핸들러로 흘려보내 409로
 * 바꾸는 방식을 쓸 수 없다 — 그러면 결과가 200 응답 본문이 아니라 오류
 * 상태 코드로 나간다. 그래서 두 판정을 <b>서로 다른 최상위 트랜잭션</b>으로
 * 나눈다.
 *
 * <p>{@link TicketService#scan}은 {@code @Transactional}이 아닌 일반 메서드이고,
 * 이 클래스의 {@code @Transactional} 메서드를 프록시를 통해 호출한다. 그래서
 * {@link #admit}이 U-11 위반으로 실패해도 <b>그 실패는 자신의 트랜잭션에만
 * 갇히고</b>, 이어지는 {@link #reject} 호출은 완전히 새 트랜잭션에서 시작해
 * 영향을 받지 않는다. 같은 클래스 안에서 {@code this.admit()}처럼 호출하면
 * 스프링 AOP 프록시를 거치지 않아 이 경계가 생기지 않는다 — 빈을 나눈 이유다.
 */
@Component
public class TicketScanRecorder {

    private final TicketScanRepository ticketScanRepository;
    private final TicketRepository ticketRepository;

    public TicketScanRecorder(TicketScanRepository ticketScanRepository, TicketRepository ticketRepository) {
        this.ticketScanRepository = ticketScanRepository;
        this.ticketRepository = ticketRepository;
    }

    /**
     * 입장 허용을 시도한다.
     *
     * <p>{@code ticket_scan}에 {@code ADMITTED}로 INSERT하고 즉시 flush한다 —
     * flush를 미루면 커밋 시점에야 U-11 위반이 드러나 이 메서드의 반환 이후에
     * 예외가 나므로, 호출자가 성공한 줄 알고 다음 단계로 넘어갈 수 있다.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException 이미 이
     *         티켓으로 ADMITTED가 있으면(U-11) 던져진다. 호출자가 이것을
     *         {@code REJECTED_DUPLICATE}로 해석한다.
     */
    @Transactional
    public void admit(long ticketId) {
        Instant now = Instant.now();

        TicketScan scan = new TicketScan();
        scan.setTicketId(ticketId);
        scan.setResult(ScanResult.ADMITTED.name());
        scan.setScannedAt(now);
        ticketScanRepository.saveAndFlush(scan);

        ticketRepository.markUsed(ticketId, now);
    }

    /** 거절 이력을 남긴다. U-11이 관여하지 않아 실패할 일이 없다. */
    @Transactional
    public void reject(long ticketId, ScanResult result, String reason) {
        TicketScan scan = new TicketScan();
        scan.setTicketId(ticketId);
        scan.setResult(result.name());
        scan.setRejectReason(reason);
        scan.setScannedAt(Instant.now());
        ticketScanRepository.save(scan);
    }
}
