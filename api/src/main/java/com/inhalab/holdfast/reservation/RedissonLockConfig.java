package com.inhalab.holdfast.reservation;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link RedisSeatHoldStrategy}가 쓰는 Redisson 클라이언트. 이슈 #155.
 *
 * <h2>왜 자동설정을 쓰지 않나</h2>
 *
 * <p><b>Redisson 스타터의 자동설정은 기동 시 접속을 시도한다.</b> 그래서 Redis가
 * 없으면 앱이 뜨지 못하는데, <b>다섯 전략 중 그것을 쓰는 것은 하나뿐이다.</b>
 * 실측으로 확인했다 — {@code none}·{@code optimistic}·{@code unique}·
 * {@code pessimistic} 넷 다 {@code Failed to resolve 'redis'}로 기동에 실패한다.
 *
 * <p>대가는 셋이다.
 *
 * <ul>
 *   <li><b>AWS 비용.</b> 전략 5종 × 3경합도 × 3회 = 60회 측정 중 <b>48회는
 *       ElastiCache가 놀면서 과금된다.</b> {@code infra-decision.md} 5절이
 *       "데모 직전 apply, 종료 즉시 destroy"로 비용을 관리하는데, 그 안에서도
 *       쓰지 않는 리소스를 띄워 두는 셈이다</li>
 *   <li><b>가용성.</b> Redis 장애가 곧 전체 장애다. 논리적으로는 네 전략이
 *       Redis와 무관하다</li>
 *   <li><b>설명.</b> "왜 안 쓰는 전략의 인프라에 의존하나"에 답할 말이 없다</li>
 * </ul>
 *
 * <h2>전략 빈과 같은 조건을 건다</h2>
 *
 * <p>{@code application.yml}이 {@code RedissonAutoConfigurationV2}·{@code V4}를
 * 제외하고, 이 클래스가 <b>{@code holdfast.strategy=redis}일 때만</b> 클라이언트를
 * 만든다. {@link RedisSeatHoldStrategy}가 걸고 있는 조건과 <b>같은 관용구·같은
 * 값</b>이라, 전략 빈이 있으면 클라이언트도 있고 없으면 둘 다 없다.
 *
 * <p><b>측정 경로에 닿지 않는다.</b> 빈을 만들지 않는 것이지 코드 경로를 바꾸는
 * 것이 아니다 — {@code redis} 전략을 잴 때는 지금과 똑같이 뜬다. #124가
 * {@code /admin/**}을 끌 때 쓴 논리와 같다.
 *
 * <h2>자동설정이 채우던 값은 두 개뿐이었다</h2>
 *
 * <p>{@code spring.data.redis.host}·{@code port}를 읽어 단일 서버로 붙이는 것이
 * 전부다. 클러스터·센티널·비밀번호는 이 프로젝트가 쓰지 않는다 —
 * {@code docker-compose.yml}의 {@code redis:7-alpine} 한 대이고, AWS에서도
 * ElastiCache 단일 노드다({@code infra-decision.md} 3절). <b>쓰지 않는 설정을
 * 흉내 내지 않는다</b> — 필요해지면 그때 자동설정으로 되돌리는 편이 낫다.
 */
@Configuration
@ConditionalOnProperty(name = "holdfast.strategy", havingValue = "redis")
public class RedissonLockConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    /**
     * {@code redis} 전략 전용 클라이언트.
     *
     * <p><b>{@code shutdown} 메서드를 지정한다.</b> 지정하지 않으면 컨텍스트가
     *닫혀도 Redisson의 넷티 스레드가 남아 테스트가 컨텍스트를 여러 번 띄울 때
     * 쌓인다. 자동설정은 이것을 해 주고 있었다.
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + host + ":" + port);
        return Redisson.create(config);
    }
}
