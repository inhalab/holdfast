package com.inhalab.holdfast.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 이 요청을 처리한 앱 인스턴스의 이름. 이슈 #151.
 *
 * <h2>이 클래스는 빈이 아니다</h2>
 *
 * <p><b>이름을 정하는 규칙만 담는다.</b> 부르는 곳은
 * {@link InstanceIdentityInitializer} 하나이고, 그것이 기동 직전에
 * {@code holdfast.instance-id}를 확정한다. {@code StatusController}(JSON)와
 * {@code PageModelAdvice}(화면 바닥)는 <b>그 프로퍼티를 {@code @Value}로 읽을 뿐</b>이다.
 *
 * <p><b>빈으로 두면 화면 슬라이스 테스트가 깨진다.</b> {@code @WebMvcTest}는 웹 계층
 * 빈만 싣고 {@code support}의 {@code @Component}는 안 싣는다 — 그 사정과 판정은
 * {@link InstanceIdentityInitializer}에 적었다.
 *
 * <h2>로컬이 이긴다</h2>
 *
 * <p>{@code docker-compose.yml}이 서비스마다 {@code INSTANCE_ID}를 {@code app1}·
 * {@code app2}로 박아 넣는다. <b>그 이름이 시연에서 값을 한다</b> — 새로고침할 때마다
 * 번갈아 찍히는 것이 로드밸런싱의 가장 짧은 증거이고, 사람이 읽을 수 있어야 그렇다.
 * 그래서 <b>환경변수가 있으면 그것을 그대로 쓰고 아무것도 더 하지 않는다.</b>
 *
 * <h2>Fargate에서는 그 방법이 통하지 않는다</h2>
 *
 * <p>Fargate는 <b>태스크 정의 하나를 desired count 2로 띄운다.</b> 두 태스크가 같은
 * 환경변수를 받으므로 {@code INSTANCE_ID}가 둘 다 같은 값이 된다. 그러면 새로고침해도
 * 배지가 안 바뀌고 <b>화면이 «앱이 한 대»라고 말한다</b> — 구성은 2대인데 화면 증거가
 * 죽는다.
 *
 * <p>그게 왜 문제인지는 {@code infra-decision.md} 2.1에 있다. #42의 목적이 <b>«클라우드에
 * 배포도 했다»를 보이는 것</b> 하나로 좁혀졌고 그 증거를 보이는 수단이 화면이다.
 * <b>«앱 2대»는 이 프로젝트의 전제이고</b>({@code concurrency-spec} 7.3의 고정 변수),
 * 분산락을 쓸 이유 자체가 거기서 나온다. 태스크를 둘 띄웠는데 화면이 하나라고 하면
 * <b>증거가 스스로를 부정한다.</b>
 *
 * <h2>그래서 태스크 메타데이터를 읽는다</h2>
 *
 * <p>Fargate는 {@code ECS_CONTAINER_METADATA_URI_V4}를 <b>태스크마다 자동으로 주입</b>하고,
 * 그 응답에 이 태스크의 ARN이 들어 있다. ARN 끝의 태스크 ID는 태스크마다 다르므로
 * 그 <b>뒤 8자</b>를 이름으로 쓴다 — 전체를 쓰면 화면 바닥에 64자가 들어간다.
 *
 * <h2>기동 시 한 번만 부른다</h2>
 *
 * <p><b>매 요청 부르지 않는다.</b> 그것은 측정 경로에 HTTP 호출을 하나 더하는
 * 일이다({@code concurrency-spec} 7.3). 생성자에서 한 번 읽어 {@code final} 필드에
 * 담는다.
 *
 * <p><b>실패해도 앱은 뜬다.</b> 메타데이터를 못 읽는 것은 배지가 덜 예쁜 문제이지
 * 서비스가 못 도는 문제가 아니다 — 그 자리에서 죽으면 배포 자체가 실패한다.
 */
public final class InstanceIdentity {

    /** ARN 뒤에서 이만큼만 쓴다. 화면 바닥에 들어가는 길이다. */
    private static final int SUFFIX_LENGTH = 8;

    /** 메타데이터 엔드포인트는 태스크 안의 로컬 주소다. 오래 기다릴 이유가 없다. */
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    /** {@code "TaskARN": "arn:aws:ecs:...:task/cluster/1234abcd..."} 에서 마지막 마디. */
    private static final Pattern TASK_ARN =
            Pattern.compile("\"TaskARN\"\\s*:\\s*\"[^\"]*/([^\"/]+)\"");

    private final String name;

    public InstanceIdentity(String configured, String metadataUri) {
        this.name = resolve(configured, metadataUri);
    }

    /** 이 요청을 처리한 인스턴스. 화면 바닥과 {@code /api/status}가 같은 값을 쓴다. */
    public String name() {
        return name;
    }

    private static String resolve(String configured, String metadataUri) {
        // **환경변수가 이긴다.** 로컬은 app1/app2 라는 읽기 좋은 이름을 그대로 쓴다.
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        // 메타데이터가 없으면 AWS가 아니다 — 로컬에서 환경변수 없이 띄운 경우다.
        if (metadataUri == null || metadataUri.isBlank()) {
            return "local";
        }
        String taskId = fetchTaskId(metadataUri);
        return taskId == null ? "unknown" : shorten(taskId);
    }

    private static String fetchTaskId(String metadataUri) {
        // `/task` 가 태스크 단위 정보를 준다 — 컨테이너 단위인 루트에는 ARN이 없을 수 있다.
        String uri = metadataUri.endsWith("/") ? metadataUri + "task" : metadataUri + "/task";
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            HttpResponse<String> res = client.send(
                    HttpRequest.newBuilder(URI.create(uri)).timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                return null;
            }
            Matcher m = TASK_ARN.matcher(res.body());
            return m.find() ? m.group(1) : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            // **삼킨다.** 배지가 unknown 인 것과 앱이 못 뜨는 것은 무게가 다르다.
            return null;
        }
    }

    private static String shorten(String taskId) {
        return taskId.length() <= SUFFIX_LENGTH
                ? taskId
                : taskId.substring(taskId.length() - SUFFIX_LENGTH);
    }
}
