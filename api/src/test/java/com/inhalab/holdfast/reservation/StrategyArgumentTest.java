package com.inhalab.holdfast.reservation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>전략 이름을 아는 세 곳이 서로 어긋나지 않는지 본다.</b> 이슈 #143.
 *
 * <h2>왜 이 테스트가 필요한가 — 123건이 못 잡은 사고다</h2>
 *
 * <p>없는 전략 이름({@code extreme} — 그것은 경합도 시나리오다)이
 * {@code HOLDFAST_STRATEGY}로 들어가 <b>컨테이너가 기동하지 못했다.</b> 조건에
 * 맞는 {@code @ConditionalOnProperty}가 없어 전략 빈이 0개가 됐기 때문이다.
 *
 * <p><b>테스트 123건이 전부 통과하는 동안 그랬다.</b> 슬라이스라서가 아니다 —
 * 전략 테스트는 전부 {@code @SpringBootTest}로 전체 컨텍스트를 띄운다. 못 잡은
 * 이유는 둘이다.
 *
 * <ul>
 *   <li><b>모든 테스트가 유효한 값을 직접 준다</b>({@code properties =
 *       "holdfast.strategy=..."}). 잘못된 값이라는 입력이 테스트에 없다.</li>
 *   <li>그 {@code properties}가 {@code application.yml}의
 *       {@code ${HOLDFAST_STRATEGY:none}} 자리표시자를 <b>건너뛴다.</b> 실행기가
 *       주는 값으로 앱이 뜨는지는 한 번도 검증되지 않았다.</li>
 * </ul>
 *
 * <h2>무엇을 검사하나 — 목록이 갈리는 것을 막는다</h2>
 *
 * <p>고치면서 {@code holdfast} 스크립트에 유효 값 목록이 생겼다. <b>같은 사실을
 * 두 곳에서 지키게 된 것</b>이므로({@code erd.md} 4절) 그 둘을 여기서 묶는다.
 * 정본은 각 구현의 {@code @ConditionalOnProperty}이고 스크립트는 그것을 옮긴
 * 것이다 — 전략을 더하거나 이름을 바꾸면 이 테스트가 먼저 깨진다.
 *
 * <h2>이 검사가 놓치는 것</h2>
 *
 * <p><b>이미지가 실제로 뜨는지는 보지 않는다.</b> 그것은 컨테이너를 띄워야 알 수
 * 있고, 이 테스트는 목록이 어긋나는 것만 막는다. {@code design-spec.md} 5.4의
 * {@code RedirectShapeTest}(RANDOM_PORT로 진짜 Tomcat)도 여기 쓸 수 없다 —
 * 그 형태 역시 {@code properties=}로 값을 직접 주므로 이번에 뚫린 경로를 지나지
 * 않는다. 필요한 것은 <b>이미지를 띄우는</b> 검증이며 별도 이슈로 남겼다.
 */
@DisplayName("전략 인자: 조건 애너테이션·실행기·기본값이 같은 다섯을 말한다")
class StrategyArgumentTest {

    /** 정본. 다섯 구현이 각자 자기 이름을 {@code havingValue}로 들고 있다. */
    private static final List<Class<?>> IMPLEMENTATIONS = List.of(
            NoneSeatHoldStrategy.class,
            PessimisticSeatHoldStrategy.class,
            OptimisticSeatHoldStrategy.class,
            UniqueSeatHoldStrategy.class,
            RedisSeatHoldStrategy.class);

    private static final String PROPERTY = "holdfast.strategy";

    private static final Path ROOT = repositoryRoot();

    private static Path repositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("load-test"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("load-test/ 가 있는 저장소 루트를 찾지 못했다.");
    }

    /** 애너테이션에서 읽은 유효 전략 이름. 이것이 정본이다. */
    private static Set<String> namesFromAnnotations() {
        Set<String> names = new TreeSet<>();
        for (Class<?> impl : IMPLEMENTATIONS) {
            ConditionalOnProperty c = impl.getAnnotation(ConditionalOnProperty.class);
            assertThat(c)
                    .as("%s 에 @ConditionalOnProperty 가 없다 — 전략 교체 기제가 깨진다", impl.getSimpleName())
                    .isNotNull();
            assertThat(c.name())
                    .as("%s 의 조건 프로퍼티", impl.getSimpleName())
                    .containsExactly(PROPERTY);
            names.add(c.havingValue());
        }
        return names;
    }

    @Test
    @DisplayName("구현 다섯이 서로 다른 이름을 갖는다 — 겹치면 둘이 동시에 뜬다")
    void eachImplementationHasItsOwnName() {
        assertThat(namesFromAnnotations()).hasSize(IMPLEMENTATIONS.size());
    }

    /**
     * <b>이 사고를 직접 막는 검사다.</b> 스크립트가 아는 이름이 애너테이션보다
     * 많으면 <b>앱이 못 뜨는 값을 실행기가 통과시킨다</b> — #143이 그것이었다.
     * 반대로 적으면 멀쩡한 전략을 실행기가 거부한다.
     */
    @Test
    @DisplayName("holdfast 스크립트의 전략 목록이 애너테이션과 정확히 같다")
    void launcherKnowsExactlyTheSameNames() throws IOException {
        String script = Files.readString(ROOT.resolve("holdfast"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("(?m)^STRATEGIES=\"([^\"]*)\"").matcher(script);
        assertThat(m.find())
                .as("holdfast 에 STRATEGIES= 목록이 없다. 검증을 지우면 #143이 되돌아온다")
                .isTrue();

        Set<String> inScript = new LinkedHashSet<>(List.of(m.group(1).trim().split("\\s+")));
        assertThat(inScript)
                .as("holdfast 의 STRATEGIES 와 @ConditionalOnProperty 의 havingValue")
                .containsExactlyInAnyOrderElementsOf(namesFromAnnotations());
    }

    /**
     * <b>기본값도 유효해야 한다.</b> {@code application.yml}이
     * {@code ${HOLDFAST_STRATEGY:none}}으로 받으므로, 그 기본값이 목록 밖이면
     * <b>환경변수를 안 준 모든 실행이 기동하지 못한다.</b>
     */
    @Test
    @DisplayName("application.yml의 기본 전략이 유효한 이름이다")
    void defaultStrategyIsValid() throws IOException {
        String yml = Files.readString(
                ROOT.resolve("api/src/main/resources/application.yml"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("strategy:\\s*\\$\\{HOLDFAST_STRATEGY:([^}]*)}").matcher(yml);
        assertThat(m.find())
                .as("application.yml 에서 holdfast.strategy 자리표시자를 찾지 못했다")
                .isTrue();

        assertThat(m.group(1).trim()).isIn(namesFromAnnotations());
    }

    /**
     * 경합도 시나리오 이름이 전략 목록에 <b>없어야</b> 한다. 이번 사고에서 실제로
     * 섞인 값이 {@code extreme}이었고, 두 어휘가 겹치면 실행기의 검증도 그것을
     * 잡지 못한다.
     */
    @Test
    @DisplayName("경합도 시나리오 이름은 전략이 아니다 — 두 어휘가 겹치지 않는다")
    void scenarioNamesAreNotStrategies() {
        assertThat(namesFromAnnotations())
                .doesNotContain("low", "high", "extreme", "sustained");
    }
}
