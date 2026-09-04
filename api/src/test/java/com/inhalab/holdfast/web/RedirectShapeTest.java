package com.inhalab.holdfast.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>리다이렉트의 {@code Location}이 상대 경로인지 본다.</b>
 *
 * <h2>왜 MockMvc로는 안 되나</h2>
 *
 * <p>{@code /admin/programs} 등록이 {@code localhost:8080}에서 눌렸는데
 * {@code http://localhost/}로 튀어 연결이 거부됐다. <b>{@code MockMvc}는 이것을
 * 잡지 못한다</b> — 서블릿 컨테이너를 띄우지 않고
 * {@code MockHttpServletResponse}에 문자열을 적을 뿐이라, 절대 URL을 만드는
 * 것은 Tomcat의 {@code Response#sendRedirect}인데 그 코드가 아예 돌지 않는다.
 * {@code redirectedUrl("/admin/programs")}는 <b>양쪽 모두에서 통과한다.</b>
 *
 * <p>그래서 이 테스트만 {@code RANDOM_PORT}로 <b>진짜 Tomcat</b>을 띄운다.
 *
 * <h2>이 테스트가 못 잡는 것 — 프록시</h2>
 *
 * <p><b>nginx는 이 상대 경로를 그대로 두지 않는다.</b> 업스트림의 상대
 * {@code Location}을 자기가 업스트림에 넘긴 {@code Host}로 절대화한다
 * ({@code proxy_redirect off}로도 막히지 않는다). 그래서 <b>nginx 뒤에서는
 * nginx의 {@code Host} 설정이 여전히 정답을 가른다</b> — 그 조합은 이 테스트로
 * 못 잡는다.
 *
 * <p>그래도 이 테스트가 지키는 것이 있다. <b>ALB는 {@code Location}을 고쳐
 * 쓰지 않고 그대로 흘린다.</b> AWS로 옮기면 앱이 내는 이 상대 경로가 곧
 * 정답이 되고, 절대 URL이었다면 ALB가 HTTPS를 끊는 표준 구성에서
 * {@code http://}로 리다이렉트해 브라우저가 막았을 것이다.
 *
 * <p>프록시까지 넣은 e2e는 만들지 않았다. 네 가지 조합을 손으로 확인해
 * {@code docs/design-spec.md} 5.4에 표로 남겼다 — CI에 컨테이너 다섯 개를
 * 매번 띄우는 값보다, 그 표와 {@code ./holdfast up}의 nginx reload가 더 곧은
 * 대응이라고 봤다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "holdfast.strategy=pessimistic",
                "holdfast.outbox.scheduler.enabled=false"
        })
@Testcontainers
@DisplayName("리다이렉트: Location이 상대 경로다 — 프록시 뒤에서도 안전하다")
class RedirectShapeTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    // Redisson 자동설정이 기동 시 접속을 시도하므로 컨텍스트를 띄우려면 필요하다.
    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @LocalServerPort
    int port;

    @Test
    @DisplayName("프로그램 등록의 Location에 스킴도 호스트도 포트도 없다")
    void createProgramRedirectsRelatively() throws Exception {
        String body = "name=" + URLEncoder.encode("리다이렉트 확인", StandardCharsets.UTF_8);

        HttpResponse<Void> res = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)   // Location을 그대로 봐야 한다
                .build()
                .send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/admin/programs"))
                                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                                .build(),
                        HttpResponse.BodyHandlers.discarding());

        assertThat(res.statusCode()).isEqualTo(302);

        String location = res.headers().firstValue("Location").orElseThrow();

        // 절대 URL이면 "http://호스트[:포트]/..." 모양이 된다. 그 호스트·포트를
        // 앱이 Host 헤더에서 짐작해야 하고, 프록시가 포트를 떼면 거기서 깨진다.
        assertThat(location)
                .as("Location은 상대 경로여야 한다. 절대 URL이면 프록시가 넘긴 "
                        + "Host에 의존하게 되고, nginx가 포트를 떼면 사용자가 "
                        + "엉뚱한 주소로 튄다 (application.yml의 use-relative-redirects).")
                .isEqualTo("/admin/programs");
    }
}
