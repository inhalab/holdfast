package com.inhalab.holdfast.reservation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>시나리오 프로파일을 아는 두 곳이 어긋나지 않는지 본다.</b> 이슈 #191.
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>좌석 수·VU·사용자 수가 {@code scenarios/lib/config.js}와
 * {@code scripts/seed.sh} <b>두 곳에 적혀 있다.</b> 시드가 만드는 사용자 수와
 * k6가 요청하는 사용자 번호가 갈리면, 앱이 할당량 행을 찾지 못해
 * <b>500을 낸다</b> — {@code IllegalStateException: 할당량 행이 없습니다}.
 *
 * <p>두 파일의 주석이 서로를 가리키며 "반드시 같은 값이어야 한다"고 적고 있는데,
 * <b>그것을 지키는 것은 사람뿐이었다.</b> {@code erd.md} 4절이 경계한
 * "같은 사실을 두 곳에서 지키면 어긋난다"이고, 이 저장소의 답은 그럴 때
 * <b>테스트로 묶는 것</b>이다({@code StrategyArgumentTest}가 전략 이름에 한 것).
 *
 * <p>7.2.3의 할당량 경합 시나리오를 더하면서 <b>지켜야 할 자리가 넷에서 다섯으로
 * 늘었다.</b> 그래서 여기서 묶는다. 7.2.4의 취소·재선점이 여섯째다(#200).
 *
 * <h2>{@code sustained}는 좌석 수를 비교하지 않는다</h2>
 *
 * <p>그쪽 좌석 수는 {@code SUSTAINED_SEATS}로 보정하는 값이라 양쪽 기본값이
 * 다를 수 있다(7.2.2). VU와 사용자 수는 고정이므로 그 둘만 본다.
 */
@DisplayName("시나리오 프로파일이 config.js와 seed.sh에서 같다 (#191)")
class ScenarioProfileTest {

    private record Profile(int seats, int vus, int users) {}

    @Test
    @DisplayName("좌석 수·VU·사용자 수가 두 파일에서 일치한다")
    void profilesMatch() throws IOException {
        Path root = repositoryRoot();
        Map<String, Profile> fromConfig = parseConfigJs(
                Files.readString(root.resolve("load-test/scenarios/lib/config.js"), StandardCharsets.UTF_8));
        Map<String, Profile> fromSeed = parseSeedSh(
                Files.readString(root.resolve("load-test/scripts/seed.sh"), StandardCharsets.UTF_8));

        assertThat(fromConfig.keySet())
                .as("시나리오 이름 목록 — 한쪽에만 있으면 그 시나리오는 시드 없이 돌거나 안 돈다")
                .isEqualTo(fromSeed.keySet());

        for (String name : fromConfig.keySet()) {
            Profile c = fromConfig.get(name);
            Profile s = fromSeed.get(name);
            assertThat(c.vus()).as("%s VU", name).isEqualTo(s.vus());
            assertThat(c.users())
                    .as("%s 사용자 수 — 갈리면 앱이 할당량 행을 못 찾아 500을 낸다", name)
                    .isEqualTo(s.users());
            if (!"sustained".equals(name)) {
                assertThat(c.seats()).as("%s 좌석 수", name).isEqualTo(s.seats());
            }
        }
    }

    @Test
    @DisplayName("할당량 경합은 사용자 수가 VU보다 작다 — 그것이 CS-6을 밟는 조건이다")
    void quotaProfileHasFewerUsersThanVus() throws IOException {
        Map<String, Profile> p = parseConfigJs(Files.readString(
                repositoryRoot().resolve("load-test/scenarios/lib/config.js"), StandardCharsets.UTF_8));

        assertThat(p).as("quota 프로파일이 있어야 한다 (7.2.3)").containsKey("quota");
        assertThat(p.get("quota").users())
                .as("사용자 수가 VU 이상이면 같은 사용자의 동시 요청이 안 생겨 CS-6이 안 밟힌다 (1.1)")
                .isLessThan(p.get("quota").vus());

        // **quota 만 반대다.** 나머지는 사용자 수가 VU 이상이어야 같은 할당량 행을
        // 두 VU가 동시에 다투지 않는다 — cancel(7.2.4)도 그쪽이다. 거기서 겹치는
        // 둘은 «같은 예약을 취소하는 둘»이고, 그것은 할당량 경합이 아니다.
        for (String name : new String[] {"low", "high", "extreme", "cancel"}) {
            assertThat(p.get(name).users())
                    .as("%s — 사용자 수가 VU 이상이어야 CS-6이 섞이지 않는다 (7.3)", name)
                    .isGreaterThanOrEqualTo(p.get(name).vus());
        }
    }

    /** {@code low: { seats: 1000, vus: 100, userPool: 400, ... }} */
    private static Map<String, Profile> parseConfigJs(String src) {
        Pattern p = Pattern.compile(
                "(\\w+):\\s*\\{\\s*seats:\\s*([^,]+),\\s*vus:\\s*(\\d+),\\s*userPool:\\s*(\\d+)");
        Map<String, Profile> out = new LinkedHashMap<>();
        Matcher m = p.matcher(src);
        while (m.find()) {
            String seats = m.group(2).trim();
            out.put(m.group(1), new Profile(
                    seats.matches("\\d+") ? Integer.parseInt(seats) : -1,
                    Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))));
        }
        return out;
    }

    /** {@code low)     SEATS=1000; VUS=100; USERS=400 ;;} */
    private static Map<String, Profile> parseSeedSh(String src) {
        Pattern p = Pattern.compile(
                "(?m)^\\s*(\\w+)\\)\\s+SEATS=(\\S+?);\\s*VUS=(\\d+);\\s*USERS=(\\d+)");
        Map<String, Profile> out = new LinkedHashMap<>();
        Matcher m = p.matcher(src);
        while (m.find()) {
            String seats = m.group(2).trim();
            out.put(m.group(1), new Profile(
                    seats.matches("\\d+") ? Integer.parseInt(seats) : -1,
                    Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4))));
        }
        return out;
    }

    /** {@code load-test/}가 보일 때까지 부모로 올라간다. 실행 조건은 {@code README.md}에 있다(#189). */
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
