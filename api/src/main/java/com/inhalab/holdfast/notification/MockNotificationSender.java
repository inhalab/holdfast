package com.inhalab.holdfast.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 알림 발송 Mock. 로그만 남기고 실제로는 아무 데도 보내지 않는다.
 *
 * <p>design-spec.md 4.1의 "알림 Mock: Outbox + 재시도 + 중복 발송 방지"가
 * 이것이다. 4.3이 실제 SMS·카카오 발송을 제외 항목으로 두었으므로 이 클래스가
 * 발송 경계의 끝이다.
 *
 * <h2>실패 주입</h2>
 *
 * <p>{@link MockPaymentGateway}와 같은 형태로 {@code outcome} 하나를 읽는다.
 * <b>재시도와 상한 소진은 실패를 만들 수 없으면 검증할 수 없다</b> — 이슈 #78의
 * 검증 항목 셋 중 둘이 실패 경로다.
 *
 * <table>
 *   <caption>{@code holdfast.notification.outcome}</caption>
 *   <tr><td>{@code send}</td><td>항상 성공. <b>기본값</b></td></tr>
 *   <tr><td>{@code fail}</td><td>항상 실패 — 상한 소진 경로를 본다</td></tr>
 *   <tr><td>{@code random}</td><td>{@code fail-ratio} 비율로 실패</td></tr>
 * </table>
 *
 * <p>기본값이 {@code send}인 이유는 Mock PG와 같다(concurrency-spec.md 7.3) —
 * 부하 측정에 실패를 섞으면 재시도 부하가 확정 부하와 뒤엉켜 무엇을 재는지
 * 흐려진다.
 *
 * <p>{@code outcome}으로 만들 수 없는 시나리오("세 번 실패한 뒤 성공")는
 * 테스트가 {@code @Primary}로 자기 {@link NotificationSender} 빈을 끼워 만든다.
 * 그래서 이 클래스가 아니라 인터페이스가 주입 대상이다.
 */
@Component
public class MockNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(MockNotificationSender.class);

    @Value("${holdfast.notification.outcome:send}")
    private String outcome;

    /** {@code outcome=random}일 때 실패가 나올 비율. 0.0~1.0. */
    @Value("${holdfast.notification.fail-ratio:0.0}")
    private double failRatio;

    @Override
    public void send(Outbox row) {
        String normalized = outcome == null ? "" : outcome.trim().toLowerCase(Locale.ROOT);
        boolean fail = switch (normalized) {
            case "fail" -> true;
            case "random" -> ThreadLocalRandom.current().nextDouble() < failRatio;
            case "send", "" -> false;
            // 오타로 측정이 조용히 다른 조건에서 도는 것을 막는다(MockPaymentGateway와 같은 이유).
            default -> throw new IllegalStateException(
                    "holdfast.notification.outcome 값이 올바르지 않다: " + outcome + " (send|fail|random)");
        };

        if (fail) {
            throw new IllegalStateException(
                    "Mock 발송 실패 — outbox id=" + row.getId() + " (holdfast.notification.outcome)");
        }

        // 실제 발송은 없다. 이 로그가 "보냈다"의 전부다.
        log.info("알림 발송(Mock) — outbox id={} 예약={} 종류={} 재시도={}",
                row.getId(), row.getReservationId(), row.getNotificationType(), row.getRetryCount());
    }
}
