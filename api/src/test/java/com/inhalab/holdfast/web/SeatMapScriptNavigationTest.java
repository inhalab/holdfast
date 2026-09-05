package com.inhalab.holdfast.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>{@code seatmap.js}가 예약 확인으로 갈 때 {@code userId}를 싣는지 본다.</b>
 *
 * <h2>왜 파일을 읽나</h2>
 *
 * <p>결제 뒤 화면 이동은 <b>브라우저의 {@code window.location.href}</b>다. 서버
 * 리다이렉트가 아니라서 어떤 JVM 테스트에서도 실행되지 않는다 —
 * {@code MinimumScopeFlowTest}는 {@code POST /api/payments} 뒤에
 * {@code GET /reservations/{id}?userId=7}을 <b>직접</b> 부르므로 이 파일이 만드는
 * 주소를 지나지 않는다. 그래서 그 8건이 통과하는 동안 사용자 2는 결제 직후
 * 404를 만났다(#126).
 *
 * <p>{@code RedirectShapeTest}처럼 진짜 Tomcat을 띄워도 소용없다. JavaScript는
 * 돌지 않는다. 브라우저 자동화는 {@code MinimumScopeFlowTest} 클래스 주석이 이미
 * 근거를 대고 배제했다 — 최소 완결선이 묻는 것은 API 계약이고, 도구와 CI 비용이
 * 함께 는다.
 *
 * <p>남은 것이 <b>파일을 읽는 것</b>이다. {@code SeedScriptSequenceTest}가 시드
 * SQL에 대해 하는 일과 같은 형태다.
 *
 * <h2>이 테스트가 지키는 것은 좁다</h2>
 *
 * <p><b>"회귀를 막는다"가 아니라 "이 한 줄이 지워지는 것을 막는다"이다.</b>
 * 문자열 매칭이라 <b>주소를 다른 방식으로 조립하면 통과하면서 깨진다</b> —
 * 템플릿 리터럴로 바꾸거나 헬퍼 함수로 빼면 아래 정규식이 못 찾는다.
 *
 * <p>{@code SeedScriptSequenceTest}가 성립하는 것은 시드 SQL이 선언적이고 형태가
 * 잘 안 바뀌기 때문인데, <b>JS의 주소 조립은 그렇지 않다.</b> 그러므로 이 검사는
 * <b>지금 이 형태로 쓰여 있는 동안만</b> 유효하고, 조립 방식을 바꾸면 이 테스트도
 * 함께 고쳐야 한다. 안 고치면 초록불이 아무것도 지키지 않는다.
 *
 * <p>{@code requirements.md} 2절이 그 위험에 이름을 붙여 두었다 — <b>"미구현과
 * 구현했지만 검증 수단이 없음은 다르며, 뒤엣것이 더 위험하다."</b> 약한 검증을
 * 강한 검증으로 세어 두면 같은 위험이 된다. 그래서 무엇을 못 잡는지를 여기 적는다.
 *
 * <p><b>주소가 실제로 열리는지는 못 본다.</b> 그쪽은 손으로 확인한다 —
 * {@code ?userId=2}로 좌석맵을 열어 결제까지 가서 예약 확인이 그 사용자로 뜨는지.
 */
@DisplayName("seatmap.js: 예약 확인으로 갈 때 userId를 싣는다")
class SeatMapScriptNavigationTest {

    /**
     * {@code window.location.href = ... "/reservations/" ... ;} 한 문장.
     *
     * <p>줄바꿈을 넘어 문장 끝({@code ;})까지 잡는다 — 지금 코드가 두 줄로
     * 나뉘어 있다.
     */
    private static final Pattern REDIRECT =
            Pattern.compile("window\\.location\\.href\\s*=\\s*([^;]*\"/reservations/\"[^;]*);");

    @Test
    @DisplayName("예약 확인으로 보내는 주소에 userId가 들어 있다")
    void redirectCarriesUserId() throws IOException {
        Path file = repositoryRoot()
                .resolve("api/src/main/resources/static/js/seatmap.js");
        assertThat(file).exists();

        String js = Files.readString(file, StandardCharsets.UTF_8);
        Matcher m = REDIRECT.matcher(js);

        assertThat(m.find())
                .as("seatmap.js에서 예약 확인으로 보내는 window.location.href 문장을 "
                        + "찾지 못했다. 주소 조립 방식을 바꿨다면 이 테스트도 함께 "
                        + "고쳐야 한다 — 클래스 주석의 '이 테스트가 지키는 것은 좁다' 참조.")
                .isTrue();

        assertThat(m.group(1))
                .as("예약 확인 화면은 소유자의 예약만 보여준다(ReservationService#get). "
                        + "userId가 빠지면 서버 기본값 1로 열려, 사용자 2로 예매한 사람이 "
                        + "결제 직후 404를 만난다 (#126).")
                .contains("userId");
    }

    /** {@code load-test/}가 보일 때까지 올라간다 — IDE와 Gradle의 작업 디렉토리가 다르다. */
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
}
