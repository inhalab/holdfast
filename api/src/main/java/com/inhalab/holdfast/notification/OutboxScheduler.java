package com.inhalab.holdfast.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@link OutboxWorker}를 주기적으로 깨운다.
 *
 * <h2>스케줄링을 워커에서 떼어낸 이유</h2>
 *
 * <p>워커의 한 사이클({@link OutboxWorker#pollOnce})은 평범한 메서드이고,
 * 언제 부를지만 이 클래스가 정한다. 그래서 <b>테스트가 워커를 직접 부를 수
 * 있다.</b> 스케줄러가 배경에서 함께 돌면 테스트가 만든 상태를 그것이 먼저
 * 집어가, 무엇이 무엇을 처리했는지 알 수 없게 된다.
 *
 * <p>{@code holdfast.outbox.scheduler.enabled=false}로 이 빈만 끄면 워커는
 * 그대로 있고 자동 실행만 멈춘다. 락 전략을 프로퍼티로 스위칭하는 것과 같은
 * 형태다(concurrency-spec.md 4절).
 *
 * <h2>고정 지연이지 고정 주기가 아니다</h2>
 *
 * <p>{@code fixedDelay}는 <b>이전 실행이 끝난 뒤부터</b> 센다.
 * {@code fixedRate}로 두면 한 사이클이 주기보다 오래 걸릴 때 실행이 겹쳐 쌓이고,
 * 발송이 느려질수록 워커가 자기 자신과 경쟁하게 된다. 클레임이 중복 발송을
 * 막아주긴 하지만, 막아야 할 일을 스스로 만들 이유가 없다.
 *
 * <p>기본 스케줄러 스레드는 하나다. 인스턴스 <b>안에서는</b> 한 번에 한
 * 사이클만 돌고, 병렬성은 인스턴스 2대에서 나온다({@link OutboxWorker}).
 */
@Component
@ConditionalOnProperty(name = "holdfast.outbox.scheduler.enabled", havingValue = "true",
        matchIfMissing = true)
public class OutboxScheduler {

    private final OutboxWorker worker;

    public OutboxScheduler(OutboxWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${holdfast.outbox.poll-interval-ms:1000}")
    public void poll() {
        worker.pollOnce();
    }
}
