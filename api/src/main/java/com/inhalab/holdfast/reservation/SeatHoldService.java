package com.inhalab.holdfast.reservation;

import com.inhalab.holdfast.seat.EventSession;
import com.inhalab.holdfast.seat.EventSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 좌석 홀드의 <b>전략 밖 공통 단계</b>. concurrency-spec.md 1.1·5.1절.
 *
 * <p>여기서 하는 일은 5개 전략 전부에서 동일하다. 전략별 분기를 두지 않는다 —
 * "k6 시나리오는 고정하고 프로퍼티만 바꿔 측정한다"(4절)는 전제가 성립하려면
 * 전략 밖 코드 경로가 전략에 따라 갈라지면 안 된다. 이 클래스에 {@code if
 * (strategy instanceof ...)} 같은 분기가 생기면 그 전제가 깨진 것이다.
 *
 * <h2>전역 락 순서</h2>
 *
 * <p><b>사용자 할당량 행(CS-6) → 좌석 행(오름차순)</b>이다(5.1). 이 순서가
 * 뒤집히면 데드락이 난다. 이 클래스가 앞 단계를, 전략이 뒤 단계를 맡는다.
 *
 * <ol>
 *   <li>좌석 ID 오름차순 정렬 — 5.1</li>
 *   <li>사용자 할당량 행 선행 락 + 1인 최대 매수 검사 — 1.1, REQ-03</li>
 *   <li>전략 위임 — 여기서부터가 락 전략 비교 대상이다</li>
 *   <li>전부 아니면 전무 판정 및 할당량 반영</li>
 * </ol>
 *
 * <p>CS-6을 {@code none}에서도 똑같이 적용하는 이유는 1.1절에 있다 — 이 구역은
 * 락 전략 비교 대상이 <b>아니다.</b> 1인 최대 매수는 좌석 단위 락으로 애초에
 * 막히지 않는 다른 축이라, 전략별로 다르게 처리하면 측정에 변수만 하나 더
 * 늘어난다.
 */
@Service
public class SeatHoldService {

    private final SeatHoldStrategy strategy;
    private final EventSessionRepository eventSessionRepository;
    private final UserSessionQuotaRepository userSessionQuotaRepository;

    public SeatHoldService(SeatHoldStrategy strategy,
                           EventSessionRepository eventSessionRepository,
                           UserSessionQuotaRepository userSessionQuotaRepository) {
        this.strategy = strategy;
        this.eventSessionRepository = eventSessionRepository;
        this.userSessionQuotaRepository = userSessionQuotaRepository;
    }

    /**
     * 좌석을 홀드한다.
     *
     * @throws SeatHoldRejectedException 정상 거절. 트랜잭션이 롤백되어 부분
     *                                   성공이 남지 않는다(api-spec.md 4절).
     */
    @Transactional
    public HoldResult hold(long sessionId, long userId, List<Long> seatIds, String holdId) {
        // 1) 좌석 ID 오름차순 정렬(5.1). 중복은 제거한다 — 같은 좌석을 두 번
        //    넣은 요청이 스스로와 경합해 자기 자신을 초과 홀드로 만드는 것은
        //    측정하려는 경합이 아니다.
        List<Long> ordered = seatIds.stream().distinct().sorted().toList();

        EventSession session = eventSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("회차를 찾을 수 없습니다. sessionId=" + sessionId));

        // 2) 사용자 할당량 행 선행 락(CS-6). 좌석 행보다 먼저 잠근다.
        UserSessionQuota quota = userSessionQuotaRepository.findForUpdate(sessionId, userId)
                .orElseThrow(() -> new IllegalStateException(
                        "할당량 행이 없습니다. 시드가 사전 생성해야 한다(concurrency-spec 1.1). "
                                + "sessionId=" + sessionId + " userId=" + userId));

        int requested = ordered.size();
        if (quota.getHeldCount() + requested > session.getMaxPerUser()) {
            throw new SeatHoldRejectedException("QUOTA_EXCEEDED", List.of());
        }

        // 3) 전략 위임. 여기서부터가 비교 대상이다.
        HoldResult result = strategy.hold(new HoldCommand(sessionId, userId, ordered, holdId));

        // 4) 전부 아니면 전무. 예외를 던져 트랜잭션째로 되돌린다.
        if (!result.success()) {
            throw new SeatHoldRejectedException(result.code(), result.conflicts());
        }

        quota.setHeldCount(quota.getHeldCount() + requested);
        userSessionQuotaRepository.save(quota);

        return result;
    }
}
