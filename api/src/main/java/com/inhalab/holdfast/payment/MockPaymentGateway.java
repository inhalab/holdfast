package com.inhalab.holdfast.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mock PG. 이번 결제 시도가 어떤 결과에 도달할지만 정한다.
 *
 * <p>인터페이스의 정본은 docs/state-transitions.md 5절이며 그 결정권은 최건에게
 * 있다(roles.md). 이 클래스는 그 5.2절 주입 파라미터를 읽어 결과만 고른다 —
 * 예약 상태를 바꾸는 일은 {@link PaymentService}가 한다.
 *
 * <h2>최소 완결 범위</h2>
 *
 * <p>이슈 #79·#66 합의에 따라 <b>{@code APPROVED}와 {@code DECLINED}만</b>
 * 구현한다. 5절 상태 기계의 {@code TIMEOUT}·{@code FAILED}와 5.2절의
 * {@code delay-ms}·{@code delay-jitter-ms}·{@code callback-delay-ms}는 여유 항목이라
 * <b>프로퍼티를 선언하지도 않는다</b> — 선언만 해두면 "주입했는데 아무 일도
 * 안 일어나는" 상태가 되어, 나중에 그 시나리오를 돌릴 때 값이 먹은 줄 알고
 * 잘못 읽게 된다.
 *
 * <p>{@code callback-delay-ms}를 홀드 TTL보다 길게 주어 만료-확정 경합을 의도적으로
 * 재현하는 것이 5.2절이 말한 이 파라미터군의 존재 이유다. 그 시나리오를 구현할 때
 * 여기에 함께 추가한다.
 *
 * <h2>기본값을 approve로 두는 이유</h2>
 *
 * <p>concurrency-spec.md 7.3 고정 변수다. 락 전략 비교 시나리오에 결제 지연·거절이
 * 섞이면 어느 지연이 락 대기이고 어느 것이 결제인지 구분할 수 없게 되어 측정이
 * 오염된다. 기본값을 바꾸지 않는다.
 */
@Component
public class MockPaymentGateway {

    /** approve / decline / random. 5.2절의 timeout·fail은 여유 항목이라 아직 받지 않는다. */
    @Value("${holdfast.mock-pg.outcome:approve}")
    private String outcome;

    /** {@code outcome=random}일 때 승인이 나올 비율. 0.0~1.0. */
    @Value("${holdfast.mock-pg.approve-ratio:0.8}")
    private double approveRatio;

    /**
     * 이번 시도의 결과를 고른다.
     *
     * @return {@link PaymentStatus#APPROVED} 또는 {@link PaymentStatus#DECLINED}
     */
    public PaymentStatus decide() {
        String normalized = outcome == null ? "" : outcome.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "decline" -> PaymentStatus.DECLINED;
            case "random" -> ThreadLocalRandom.current().nextDouble() < approveRatio
                    ? PaymentStatus.APPROVED
                    : PaymentStatus.DECLINED;
            // approve가 기본값이다. 알 수 없는 값이 와도 승인으로 떨어뜨리지 않는다 —
            // 오타로 measurement가 조용히 다른 조건에서 도는 것을 막는다.
            case "approve", "" -> PaymentStatus.APPROVED;
            default -> throw new IllegalStateException(
                    "holdfast.mock-pg.outcome 값이 올바르지 않다: " + outcome
                            + " (approve|decline|random). timeout·fail은 아직 구현되지 않았다 — #66 여유 항목");
        };
    }
}
