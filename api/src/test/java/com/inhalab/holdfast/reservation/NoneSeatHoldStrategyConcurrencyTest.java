package com.inhalab.holdfast.reservation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code none} 베이스라인이 <b>실제로 깨지는지</b> 확인한다.
 * concurrency-spec.md 4.1·8절.
 *
 * <p>
 * <b>이 테스트는 초과 홀드가 발생해야 통과한다.</b> 보통의 테스트와 방향이
 * 반대다. 2개월차 산출물이 "락 없이 N명이 동시에 요청하면 초과 예약 M건"이라는
 * 실패 데이터이고, 이 테스트가 그 M이 0이 아님을 보증한다. 여기서 초과가 나오지
 * 않으면 어딘가에 의도치 않은 방어가 남아 있다는 뜻이다.
 *
 * <p>
 * H2가 아니라 실제 Postgres를 쓴다 — {@code FOR UPDATE} 의미론이 달라
 * 동시성 테스트가 통과해도 아무것도 보장하지 않기 때문이다(8절).
 */
@SpringBootTest(properties = {
                "holdfast.strategy=none",
                // 스레드가 커넥션을 기다리며 줄 서면 동시에 조회하는 구간이 줄어든다.
                // 경합을 재현하려는 테스트이므로 풀을 스레드 수보다 넉넉히 잡는다.
                "spring.datasource.hikari.maximum-pool-size=40"
})
@Testcontainers
@DisplayName("none 베이스라인: 락이 없어 초과 홀드가 발생한다")
class NoneSeatHoldStrategyConcurrencyTest {

        private static final long SESSION_ID = 1L;
        private static final long SEAT_ID = 1L;
        private static final int THREADS = 30;

        @Container
        @ServiceConnection
        static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

        // Redis 자체는 none 전략이 쓰지 않지만, Redisson 자동설정이 기동 시 접속을
        // 시도하므로 컨텍스트를 띄우려면 필요하다.
        @Container
        static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                        .withExposedPorts(6379);

        @DynamicPropertySource
        static void redisProperties(DynamicPropertyRegistry registry) {
                registry.add("spring.data.redis.host", redis::getHost);
                registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        }

        @Autowired
        private SeatHoldService seatHoldService;

        @Autowired
        private SeatHoldStrategy strategy;

        @Autowired
        private JdbcTemplate jdbc;

        @BeforeEach
        void seed() {
                // U-2를 지운다. none 베이스라인의 실제 측정 환경과 같게 만들기 위해서다 —
                // load-test/scripts/seed.sh가 none일 때 u2-drop.sql을 실행하는 것과 동일하다
                // (erd.md 3.1). 이 인덱스가 남아 있으면 두 번째 HELD INSERT가 제약 위반으로
                // 막혀 초과 홀드가 관측되지 않는다.
                // 그러면 "락이 
                //

                // 지워라. none 외의 
                                그 전략의 제약 위반 카운터가 의미를 잃는다.
                                cute("DROP INDEX IF EXISTS ux_seat_hold_active");
                                
                                cute("""
                                TRUNCATE TABLE ticket_sc
                                     

                                       seat, zone, seat_layout, event_session, program
                        RESTART IDENTITY CASCADE
                        """);
                
                                ate("INSE
                jdbc.update("IN
                                ate("INSERT INTO zone (id, seat_layout_id, name, sort_order) VALUES (1, 1, 'A'
                                ate("INSERT INTO seat (id, zone_id, seat_no, row_index, col_index) VALUES (?,
                                SEAT_ID);
                                ate("""
                                INSERT INTO event_session (id, program_id, seat_layout_id, starts_at, ends_at,
                                                 

                        VALUES (?, 1, 1, now() + i
                               
                                """, SESSION_ID);
                                
                                 자리뿐이다. 정상이라면 한 명만 잡아야 한다.

                        INSERT INTO seat_inventory (session_id, seat_id,
                        VALUES (?, ?, 'AVAILABLE', 
                        """, SESSION_ID, SEAT_ID);
                        
                                        행은 사전 생성한다(concurrency
                /
         

             
            }
        }
                
                                
                                ("30스레드가 좌석 1석을 동시에 홀드하면 HELD 홀드 행이 2건 이상 

                assertThat(strategy)
                        .as("holdfast.strategy=none이면 none 구현체가 주
                        .isInstanceOf(NoneSeatHoldStrategy.class);
                
                ExecutorService pool = Executors.newFixedThre
                CountDownLatch startGate = new CountDownLat

                AtomicInteger succeeded = new Atomic
                        icInteger reject
                        icInteger failed = 
                                
                                         1; i <= THREADS; i++) {
                                        rId = i;
                                        mit(() -> {
                                                        
                                        {
                                    // 모든 스레드를 같은 순간에 풀어 조회 구간을 겹치게 한다.
                                        startGate.await();
                                        seatHoldService.hold(SESSIO
                                    succeeded.increment
                                        tch (SeatHoldRejectedExce
                                    // 조회 시
                                        rejected.incrementAnd
                                }
                           
                 

                        }
                    });
                                
                                
                startGate.countD

                        .as("모든 스레드가 60초 안에 끝나야 한다")
                                .isTrue();
                                tdown();
                                

                        SELECT cou
                                 WHERE session_id = ? AND seat_id = ? AND status = 'HELD'
                                """, Integer.class, SESSION_ID, SEAT_ID);

                System.out.printf(
                                "[none 베이스라인] 스레드 %d · 성공 %d · 정상거절 %d · 예외 %
                                THREADS, s

                assertThat(failed.get())
                        .as("예기치 못한 
                                .isZero
                                                
                                                은 하나인데 홀드가 둘 이상 남았다면 초과 홀드다.
                                                ows)
                                .as("""

                                — U-2 인덱스가 
                                        섞여 들어갔는지 확인한다(concurrency-spe
                                .isGreaterThan(1);
        
        assertThat(succeeded.get())
                .as("두 스레드 이상이 '내가 좌석을 잡았다'고 믿어야 한다")
                .isGreaterThan(1);
    }
}
