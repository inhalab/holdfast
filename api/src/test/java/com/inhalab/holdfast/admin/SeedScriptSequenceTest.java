package com.inhalab.holdfast.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>시드 스크립트가 IDENTITY 시퀀스를 맞추는지 파일을 읽어 확인한다.</b>
 *
 * <h2>왜 정적 검사인가</h2>
 *
 * <p>{@code AdminCatalogFlowTest}는 <b>앱이 시드된 DB에서 동작하는가</b>를 본다.
 * 그런데 그 테스트는 시드를 자바로 다시 쓴 것이라, 누군가
 * {@code load-test/sql/seed.sql}에서 시퀀스 맞추기를 지워도 <b>초록불이 유지된다.</b>
 *
 * <p>실제로 난 사고가 정확히 그 모양이었다 — {@code infra/demo-seed.sql}은
 * 시퀀스를 맞추고 있었고 {@code load-test/sql/seed.sql}은 아니었다. 둘이 갈린
 * 것을 아무도 몰랐다.
 *
 * <p>psql 변수({@code :seats}, {@code :session_id})를 쓰는 파일이라 JDBC로 실행할
 * 수 없다. 그래서 <b>실행 대신 읽는다.</b> 검사는 하나다 —
 * <b>id를 명시해 INSERT하는 파일은 시퀀스를 맞춰야 한다.</b>
 *
 * <h2>이 검사가 놓치는 것</h2>
 *
 * <p>{@code setval}이 <b>있는지</b>만 본다. 어느 테이블을 맞추는지는 보지 않는다 —
 * 지금 쓰는 방식이 IDENTITY 컬럼을 전부 훑는 {@code DO} 블록이라 테이블별로
 * 셀 것이 없기 때문이다. 누군가 테이블을 열거하는 방식으로 되돌리면 이 검사는
 * 통과하면서 빠진 테이블을 놓친다. 그때는 이 테스트도 함께 고쳐야 한다.
 */
@DisplayName("시드 스크립트: id를 명시하면 시퀀스도 맞춘다")
class SeedScriptSequenceTest {

    /** {@code INSERT INTO <table> (id, ...} — 시드는 전부 id를 첫 컬럼으로 쓴다. */
    private static final Pattern EXPLICIT_ID_INSERT =
            Pattern.compile("INSERT\\s+INTO\\s+(\\w+)\\s*\\(\\s*id\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 저장소 루트. Gradle 테스트의 작업 디렉토리는 {@code api/}지만, 거기 기대지
     * 않고 {@code load-test/}가 보일 때까지 올라간다 — IDE에서 돌리면 작업
     * 디렉토리가 다르다.
     */
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

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"load-test/sql/seed.sql", "infra/demo-seed.sql"})
    @DisplayName("id를 명시해 INSERT하는 시드는 setval을 부른다")
    void seedResyncsSequences(String relativePath) throws IOException {
        Path file = ROOT.resolve(relativePath);
        assertThat(file).exists();

        String sql = Files.readString(file, StandardCharsets.UTF_8);
        Set<String> tables = new LinkedHashSet<>();
        Matcher m = EXPLICIT_ID_INSERT.matcher(sql);
        while (m.find()) {
            tables.add(m.group(1).toLowerCase());
        }

        // id를 명시하지 않는 시드라면 검사할 것이 없다.
        if (tables.isEmpty()) {
            return;
        }

        assertThat(sql)
                .as("%s는 %s에 id를 명시해 INSERT한다. IDENTITY 시퀀스는 그때 올라가지 "
                        + "않으므로 setval로 맞춰야 한다 — 안 맞추면 관리자 등록이 "
                        + "duplicate key로 500을 낸다 (support/IdentitySequences.java).",
                        relativePath, tables)
                .contains("setval");
    }
}
