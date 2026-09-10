package com.inhalab.holdfast.support;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>인스턴스 이름이 어디서 오는가.</b> 이슈 #151.
 *
 * <p>세 갈래가 있고 <b>순서가 곧 판정</b>이다 — 환경변수 → 태스크 메타데이터 → 대체값.
 * 순서가 뒤집히면 Fargate에서 두 태스크가 같은 이름을 갖고, 그러면 화면이
 * <b>«앱이 한 대»라고 말한다.</b>
 *
 * <p>메타데이터 엔드포인트는 <b>실제로 띄운다.</b> URL을 문자열로 넘기고 응답을
 * 파싱하는 것이 이 클래스가 하는 일 전부라, 그 왕복을 흉내 내면 정작 재는 것이
 * 없어진다.
 */
@DisplayName("인스턴스 이름: 환경변수 → 태스크 메타데이터 → local")
class InstanceIdentityTest {

    private static final String TASK_JSON = """
            {"Cluster":"holdfast","TaskARN":\
            "arn:aws:ecs:ap-northeast-2:123456789012:task/holdfast/9f8e7d6c5b4a3210",\
            "Family":"holdfast-app"}""";

    @Nested
    @DisplayName("환경변수가 있으면 그것이 이긴다")
    class Configured {

        @Test
        void 메타데이터를_보지_않는다() {
            // 메타데이터 주소를 넣어도 쓰지 않는다 — 없는 주소라 부르면 실패한다.
            InstanceIdentity id = new InstanceIdentity("app1", "http://127.0.0.1:1/none");
            assertThat(id.name()).isEqualTo("app1");
        }

        @Test
        void 시연에서_읽을_수_있는_이름이_그대로_남는다() {
            assertThat(new InstanceIdentity("app2", "").name()).isEqualTo("app2");
        }
    }

    @Nested
    @DisplayName("환경변수가 비어 있으면 태스크 메타데이터에서 읽는다")
    class FromMetadata {

        @Test
        void 태스크_ARN의_뒤_8자를_쓴다() throws Exception {
            withServer(TASK_JSON, 200, uri -> {
                // ARN 마지막 마디가 9f8e7d6c5b4a3210 이므로 뒤 8자는 5b4a3210 이다.
                assertThat(new InstanceIdentity("", uri).name()).isEqualTo("5b4a3210");
            });
        }

        @Test
        void 태스크가_다르면_이름도_다르다() throws Exception {
            // **이 테스트가 이슈의 본체다.** 같은 태스크 정의에서 뜬 두 태스크가
            // 서로 다른 이름을 가져야 화면이 "앱 2대"를 말할 수 있다.
            String other = TASK_JSON.replace("9f8e7d6c5b4a3210", "1111222233334444");
            withServer(TASK_JSON, 200, a ->
                    withServerUnchecked(other, 200, b ->
                            assertThat(new InstanceIdentity("", a).name())
                                    .isNotEqualTo(new InstanceIdentity("", b).name())));
        }

        @Test
        void 응답이_200이_아니면_unknown이다() throws Exception {
            withServer("{}", 500, uri ->
                    assertThat(new InstanceIdentity("", uri).name()).isEqualTo("unknown"));
        }

        @Test
        void ARN이_없으면_unknown이다() throws Exception {
            withServer("{\"Cluster\":\"holdfast\"}", 200, uri ->
                    assertThat(new InstanceIdentity("", uri).name()).isEqualTo("unknown"));
        }
    }

    @Nested
    @DisplayName("둘 다 없으면")
    class Neither {

        @Test
        void local이다() {
            assertThat(new InstanceIdentity("", "").name()).isEqualTo("local");
        }

        @Test
        void 닿지_않는_주소여도_앱은_뜬다() {
            // **예외를 던지지 않는다.** 배지가 unknown 인 것과 배포가 실패하는 것은
            // 무게가 다르다 — 그 자리에서 죽으면 서비스 자체가 안 뜬다.
            assertThat(new InstanceIdentity("", "http://127.0.0.1:1/none").name())
                    .isEqualTo("unknown");
        }
    }

    // ── 메타데이터 서버를 잠깐 띄운다 ───────────────────────────────────

    private interface UriConsumer {
        void accept(String uri) throws Exception;
    }

    private static void withServer(String body, int status, UriConsumer block) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 실제 엔드포인트가 `${URI}/task` 이므로 그 경로에 매단다.
        server.createContext("/v4/abc/task", ex -> {
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        try {
            block.accept("http://127.0.0.1:" + server.getAddress().getPort() + "/v4/abc");
        } finally {
            server.stop(0);
        }
    }

    private static void withServerUnchecked(String body, int status, UriConsumer block) {
        try {
            withServer(body, status, block);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
