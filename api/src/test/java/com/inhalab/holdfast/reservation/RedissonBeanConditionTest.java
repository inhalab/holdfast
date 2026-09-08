package com.inhalab.holdfast.reservation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Redis를 쓰는 전략에서만 Redisson 빈이 생긴다.</b> 이슈 #155.
 *
 * <h2>무엇을 지키나</h2>
 *
 * <p>Redisson 스타터의 자동설정은 <b>기동 시 접속을 시도</b>한다. 그래서 그것이
 * 살아 있으면 {@code pessimistic}을 잴 때도 Redis가 떠 있어야 하고, AWS에서는
 * <b>60회 측정 중 48회를 놀고 있는 ElastiCache에 과금</b>한다.
 *
 * <p>{@code application.yml}이 {@code RedissonAutoConfigurationV2}·{@code V4}를
 * 제외하고 {@link RedissonLockConfig}가 {@code holdfast.strategy=redis}일 때만
 * 클라이언트를 만든다. <b>이 테스트는 그 두 장치가 함께 살아 있는지를 본다.</b>
 *
 * <h2>왜 주석이 아니라 테스트인가</h2>
 *
 * <p>자동설정이 되살아나는 길이 둘 있다.
 *
 * <ul>
 *   <li>Redisson을 올릴 때 <b>imports 파일에 새 이름이 추가</b>되면 — 4.7.0이
 *       이미 {@code V2}와 {@code V4} 둘을 등록한다. 하나만 빼면 다른 하나가
 *       그대로 접속한다</li>
 *   <li>누가 {@code exclude} 목록을 지우면</li>
 * </ul>
 *
 * <p><b>어느 쪽도 기동을 깨지 않는다.</b> Redis가 떠 있는 개발·CI 환경에서는
 * 조용히 접속할 뿐이라, 되살아난 것을 알아채는 시점이 <b>ElastiCache 요금이
 * 나온 뒤</b>가 된다.
 *
 * <h2>Redis 컨테이너를 띄우지 않는다</h2>
 *
 * <p>다른 흐름 테스트들은 <i>"Redisson 자동설정이 기동 시 접속을 시도하므로
 * 컨텍스트를 띄우려면 필요하다"</i>는 주석과 함께 {@code redis:7-alpine}을 띄운다.
 * <b>아래 {@code NotRedisStrategy}가 그것을 띄우지 않고도 뜨는 것 자체가 이
 * 이슈의 결과다</b> — 그 주석들은 이제 {@code redis} 전략 테스트에만 참이다.
 */
@Testcontainers
@DisplayName("Redisson 빈은 redis 전략에서만 생긴다 (#155)")
class RedissonBeanConditionTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @Nested
    @SpringBootTest(properties = {
            "holdfast.strategy=pessimistic",
            "holdfast.outbox.scheduler.enabled=false"
    })
    @DisplayName("redis가 아닌 전략 — Redis 없이 뜨고 빈도 없다")
    class NotRedisStrategy {

        @Autowired
        ApplicationContext context;

        @Test
        void RedissonClient_빈이_없다() {
            // 이 컨텍스트가 뜬 것 자체가 절반의 검증이다 — Redis 컨테이너가 없다.
            assertThat(context.getBeanNamesForType(RedissonClient.class)).isEmpty();
        }

        @Test
        void 그래도_홀드_전략은_있다() {
            // 빠진 것은 Redisson뿐이고 측정 대상은 그대로다.
            assertThat(context.getBean(SeatHoldStrategy.class))
                    .isInstanceOf(PessimisticSeatHoldStrategy.class);
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "holdfast.strategy=redis",
            "holdfast.outbox.scheduler.enabled=false"
    })
    @DisplayName("redis 전략 — 클라이언트가 생기고 실제로 붙는다")
    class RedisStrategy {

        @Container
        static GenericContainer<?> redis =
                new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

        @DynamicPropertySource
        static void redisProperties(DynamicPropertyRegistry registry) {
            registry.add("spring.data.redis.host", redis::getHost);
            registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        }

        @Autowired
        RedissonClient redisson;

        @Test
        void 클라이언트가_살아_있고_락을_잡는다() {
            // **빈이 있는 것만으로는 부족하다.** 자동설정을 대신하면서 주소를
            // 잘못 조립하면 빈은 생기고 쓸 때 터진다 — 실제로 잡아 본다.
            assertThat(redisson.isShutdown()).isFalse();
            assertThat(redisson.getLock(RedisSeatHoldStrategy.lockKey(1L, 1L)).tryLock()).isTrue();
        }
    }
}
