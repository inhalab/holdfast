package com.inhalab.holdfast;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling}은 알림 Outbox 워커를 위한 것이다
 * ({@code notification.OutboxScheduler}, 이슈 #78). 이것 말고 주기 실행은 없다 —
 * 홀드 만료 정리는 스케줄러가 아니라 홀드 경로의 lazy 검증이 담당한다
 * (concurrency-spec.md 3절).
 */
@SpringBootApplication
@EnableScheduling
public class HoldfastApplication {
    public static void main(String[] args) {
        SpringApplication.run(HoldfastApplication.class, args);
    }
}
