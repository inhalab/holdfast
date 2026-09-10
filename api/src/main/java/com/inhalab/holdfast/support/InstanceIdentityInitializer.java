package com.inhalab.holdfast.support;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * {@code holdfast.instance-id}를 기동 시 한 번 확정한다. 이슈 #151.
 *
 * <h2>왜 프로퍼티로 채우나 — 빈으로 두면 화면 테스트가 깨진다</h2>
 *
 * <p>처음에는 {@link InstanceIdentity}를 {@code @Component}로 두고
 * {@code StatusController}·{@code PageModelAdvice}가 주입받게 했다. <b>그러면
 * {@code @WebMvcTest} 슬라이스가 전부 깨진다</b> — 그 슬라이스는 웹 계층 빈만 싣고
 * {@code support}의 {@code @Component}는 안 싣기 때문이다. 슬라이스마다 모의 객체를
 * 넣어 막을 수는 있지만, <b>화면이 배지를 어떻게 그리는지 보는 테스트에 배지를
 * 가짜로 넣는 것</b>이라 정작 재려던 것이 사라진다.
 *
 * <p>그래서 <b>읽는 쪽은 {@code @Value}로 두고 값을 정하는 자리만 여기 하나로
 * 모은다.</b> 소비처는 프로퍼티 하나를 읽을 뿐이라 슬라이스 테스트가 그대로 돌고,
 * "대체값을 정하는 규칙이 두 곳에 생기는" 문제도 없다 — #124가 경계한 그 상황이다.
 *
 * <h2>언제 무엇을 채우나</h2>
 *
 * <p>{@code INSTANCE_ID}가 있으면 <b>아무것도 하지 않는다.</b> {@code application.yml}이
 * 이미 그 값을 쓰고 있고, 로컬의 {@code app1}·{@code app2}라는 읽기 좋은 이름이
 * 시연에서 값을 한다.
 *
 * <p>비어 있고 {@code ECS_CONTAINER_METADATA_URI_V4}가 있으면 <b>Fargate다.</b>
 * 태스크 메타데이터에서 이 태스크의 ARN을 읽어 그 뒤 8자를 넣는다. 근거는
 * {@link InstanceIdentity}에 있다.
 *
 * <h2>기동 시 한 번뿐이다</h2>
 *
 * <p>{@code EnvironmentPostProcessor}는 컨텍스트가 만들어지기 전에 한 번 돈다.
 * <b>매 요청 부르지 않는다</b> — 그것은 측정 경로에 HTTP 호출을 하나 더하는
 * 일이다({@code concurrency-spec} 7.3).
 *
 * <p>등록은 {@code META-INF/spring.factories}에 있다.
 */
public class InstanceIdentityInitializer implements EnvironmentPostProcessor {

    private static final String KEY = "holdfast.instance-id";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        String configured = env.getProperty("INSTANCE_ID", "");
        if (!configured.isBlank()) {
            return;  // 로컬이 이긴다. application.yml 이 이미 이 값을 쓴다.
        }
        String metadataUri = env.getProperty("ECS_CONTAINER_METADATA_URI_V4", "");
        if (metadataUri.isBlank()) {
            return;  // AWS가 아니다. application.yml 의 기본값(local)이 남는다.
        }
        String resolved = new InstanceIdentity("", metadataUri).name();
        env.getPropertySources().addFirst(
                new MapPropertySource("holdfast-instance-id", Map.of(KEY, resolved)));
    }
}
