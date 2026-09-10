package com.inhalab.holdfast.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>언제 프로퍼티를 덮어쓰는가.</b> 이슈 #151.
 *
 * <p>덮어쓰는 조건이 하나라도 틀리면 두 결말 중 하나가 된다 — 로컬의
 * {@code app1}/{@code app2}가 사라지거나, Fargate에서 두 태스크가 같은 이름을 갖는다.
 * 후자가 이 이슈가 막으려는 것이다.
 */
@DisplayName("인스턴스 이름 초기화: 언제 덮어쓰나")
class InstanceIdentityInitializerTest {

    private static final String KEY = "holdfast.instance-id";

    private final InstanceIdentityInitializer initializer = new InstanceIdentityInitializer();

    @Test
    @DisplayName("INSTANCE_ID가 있으면 손대지 않는다 — 로컬이 이긴다")
    void 환경변수가_있으면_그대로_둔다() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("INSTANCE_ID", "app1")
                // 메타데이터가 있어도 무시해야 한다. 닿지 않는 주소를 넣어 확인한다.
                .withProperty("ECS_CONTAINER_METADATA_URI_V4", "http://127.0.0.1:1/none");

        initializer.postProcessEnvironment(env, null);

        assertThat(env.getPropertySources().contains("holdfast-instance-id")).isFalse();
    }

    @Test
    @DisplayName("둘 다 없으면 손대지 않는다 — application.yml의 기본값이 남는다")
    void 메타데이터가_없으면_그대로_둔다() {
        MockEnvironment env = new MockEnvironment();

        initializer.postProcessEnvironment(env, null);

        assertThat(env.getPropertySources().contains("holdfast-instance-id")).isFalse();
    }

    @Test
    @DisplayName("INSTANCE_ID가 비어 있고 메타데이터가 있으면 덮어쓴다")
    void 메타데이터가_있으면_덮어쓴다() {
        // 닿지 않는 주소라 unknown 이 되지만, **덮어쓰는 결정 자체**가 이 테스트의 대상이다.
        MockEnvironment env = new MockEnvironment()
                .withProperty("ECS_CONTAINER_METADATA_URI_V4", "http://127.0.0.1:1/none");

        initializer.postProcessEnvironment(env, null);

        assertThat(env.getPropertySources().contains("holdfast-instance-id")).isTrue();
        assertThat(env.getProperty(KEY)).isEqualTo("unknown");
    }

    @Test
    @DisplayName("덮어쓸 때는 맨 앞에 넣는다 — application.yml의 기본값을 이겨야 한다")
    void 기존_값보다_앞선다() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("ECS_CONTAINER_METADATA_URI_V4", "http://127.0.0.1:1/none")
                .withProperty(KEY, "local");

        initializer.postProcessEnvironment(env, null);

        // 뒤에 넣었다면 여기서 local 이 나온다 — 그러면 Fargate 에서 배지가 안 갈린다.
        assertThat(env.getProperty(KEY)).isEqualTo("unknown");
    }
}
