package com.inhalab.holdfast.admin;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 좌석배치도·구역·좌석 등록. 이슈 #102 — SFR-005(관리자 운영 편의).
 *
 * <p>회차 등록(#101)은 배치도를 <b>고르기만</b> 한다. 그 배치도를 만드는 화면이
 * 없어서 지금까지 {@code seat_layout}·{@code zone}·{@code seat}는 시드 SQL로만
 * 넣을 수 있었다.
 *
 * <h2>왜 JdbcTemplate인가</h2>
 *
 * <p>{@link AdminCatalogService}와 같은 이유다 — 엔티티 기본 생성자가
 * {@code protected}라 {@code seat/} 밖에서 만들 수 없고, 격자 생성은 행 하나씩
 * 저장하는 것보다 {@code INSERT ... SELECT generate_series}가 자연스럽다.
 * 5행 × 10열이면 왕복 50번이 1번이 된다.
 *
 * <h2>물어보는 것을 줄인다</h2>
 *
 * <p>처음에는 구역마다 <b>이름·정렬 순서·행·열·접두어·시작 번호</b> 여섯을
 * 받았다. 화면이 읽히지 않았다. 여섯 중 넷은 <b>이미 있는 값에서 정해지거나
 * 다른 방식으로 물어야 하는 것</b>이었다.
 *
 * <table border="1">
 *   <caption>없앤 입력과 그 자리를 대신한 것</caption>
 *   <tr><th>없앤 것</th><th>대신</th></tr>
 *   <tr><td>정렬 순서(숫자)</td>
 *       <td>{@link #moveZone} — 위/아래 버튼. "작을수록 위"를 설명할 필요가 없다</td></tr>
 *   <tr><td>좌석 추가의 열 수</td>
 *       <td>그 구역의 현재 열 수. 다른 값을 넣으면 격자가 직사각형이 아니게 된다</td></tr>
 *   <tr><td>좌석 추가의 접두어·시작 번호</td>
 *       <td>{@link #currentNumbering} — 이미 붙어 있는 좌석번호에서 읽는다</td></tr>
 *   <tr><td>구역 등록의 접두어</td>
 *       <td>{@link #derivePrefix} — 구역 이름 앞부분. 직접 정하려면 화면의
 *           "고급"에서 여전히 넣을 수 있다</td></tr>
 * </table>
 *
 * <p><b>없앤 것이 아니라 옮긴 것이다.</b> 접두어는 고급 입력으로 남아 있고,
 * 나머지 셋은 값이 사라진 게 아니라 사람 대신 이 클래스가 구한다.
 *
 * <h2>격자로 만든다</h2>
 *
 * <p>좌석을 한 칸씩 등록하게 하면 50석짜리 구역에 50번을 입력해야 한다. 실제로
 * 시드 스크립트도 {@code generate_series}로 만들었으므로, 구역을 만들 때 받는
 * 값은 <b>이름 · 행 수 · 열 수</b> 셋이면 충분하다.
 *
 * <pre>
 *   좌석번호  prefix-n   (n = 시작번호부터 행 우선으로 증가)
 *   row_index 1..행 수   (이어 넣을 때는 기존 최대 행 다음부터)
 *   col_index 1..열 수
 * </pre>
 *
 * <p><b>{@code rowIndex}·{@code colIndex}를 반드시 채운다.</b> 좌석맵 화면이 이
 * 두 값으로 격자를 그리므로 비면 화면이 무너진다(#102 주의사항). 스키마도
 * {@code NOT NULL}이지만, 그보다 먼저 "격자로만 만든다"는 이 규칙이 값을 보장한다.
 *
 * <h2>삭제 정책</h2>
 *
 * <p>이슈가 정하라고 한 것이다. <b>판매 여부가 아니라 참조 유무로 가른다.</b>
 *
 * <ul>
 *   <li><b>어느 회차도 쓰지 않는 배치도</b> — 구역·좌석·배치도를 자유롭게 지운다.
 *       회차가 없으면 {@code seat_inventory} 행도 있을 수 없으므로 지울 것이
 *       배치도 안에서 끝난다</li>
 *   <li><b>회차가 참조하는 배치도</b> — 구역·좌석을 <b>더하거나 지우지</b> 못한다.
 *       이름과 순서는 바꿀 수 있다 — {@code seat_inventory}가 참조하는 것은
 *       {@code seat.id}이지 이름이 아니고, 순서는 그리는 차례일 뿐이다</li>
 * </ul>
 *
 * <p><b>"아직 안 팔렸으면 지워도 되지 않나"를 택하지 않은 이유</b>가 이 정책의
 * 핵심이다. 좌석을 지우려면 그 좌석의 {@code seat_inventory} 행을 먼저 지워야
 * FK가 통과하는데, 그 행이 곧 <b>그 회차의 좌석맵</b>이다. 지우는 순간 판매 중인
 * 회차에서 좌석이 조용히 사라지고, 마침 그 좌석을 보고 있던 사람의 홀드 요청은
 * {@code SEAT_NOT_IN_SESSION}으로 떨어진다. "안 팔렸다"는 조회 시점의 사실이지
 * 삭제가 끝날 때까지 유지되는 사실이 아니다 — 그 창을 없애려면 회차 전체를 잠가야
 * 하고, 그것은 배치도를 고치자고 판매를 멈추는 일이다.
 *
 * <p>대신 <b>회차가 배치도를 참조하기 시작하면 그 배치도의 구조는 고정</b>이라고
 * 정했다. 회차 수정이 {@code seat_layout_id}를 바꾸지 못하게 한 것(#101,
 * {@link AdminCatalogService#updateSession})과 같은 규칙을 반대편에서 건 것이다.
 *
 * <p>{@code seat_inventory}를 회차 수와 <b>따로</b> 한 번 더 세는 이유는 그 둘이
 * 어긋난 DB가 있을 수 있기 때문이다 — 시드 스크립트가 배치도로 거르지 않고
 * {@code FROM seat s}로 전부 넣으면 그렇게 된다. FK 위반으로 500을 내는 대신
 * 읽을 수 있는 문장으로 막는다.
 */
@Service
public class AdminSeatLayoutService {

    /**
     * 한 번에 만들 수 있는 좌석 수. 오타 한 번으로 십만 행이 들어가는 것을 막는다.
     *
     * <p>화면이 이 값을 그대로 안내하므로 공개한다 — 상한을 문구에 따로 적으면
     * 둘이 어긋난다.
     */
    public static final int MAX_SEATS_PER_GRID = 2000;

    /** {@code seat.seat_no}가 {@code varchar(20)}이라 접두어를 제한한다. */
    static final int MAX_PREFIX_LENGTH = 10;

    private final JdbcTemplate jdbc;

    public AdminSeatLayoutService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── 배치도 ──────────────────────────────────────────────────────────

    @Transactional
    public long createLayout(String name) {
        require(notBlank(name), "배치도 이름을 입력하세요.");
        Long id = jdbc.queryForObject(
                "INSERT INTO seat_layout (name, created_at) VALUES (?, now()) RETURNING id",
                Long.class, name.strip());
        if (id == null) {
            throw new IllegalStateException("배치도를 만들었는데 id를 돌려받지 못했다.");
        }
        return id;
    }

    @Transactional
    public void updateLayout(long layoutId, String name) {
        require(notBlank(name), "배치도 이름을 입력하세요.");
        jdbc.update("UPDATE seat_layout SET name = ? WHERE id = ?", name.strip(), layoutId);
    }

    @Transactional
    public void deleteLayout(long layoutId) {
        requireUnused(layoutId);
        jdbc.update(
                "DELETE FROM seat WHERE zone_id IN (SELECT id FROM zone WHERE seat_layout_id = ?)",
                layoutId);
        jdbc.update("DELETE FROM zone WHERE seat_layout_id = ?", layoutId);
        jdbc.update("DELETE FROM seat_layout WHERE id = ?", layoutId);
    }

    // ── 구역 ────────────────────────────────────────────────────────────

    /**
     * 구역을 만들고 좌석 격자를 함께 채운다.
     *
     * <p><b>구역만 만들 수는 없다.</b> 좌석 없는 구역은 좌석맵에 빈 칸으로 뜨고,
     * 그 배치도로 회차를 만들면 재고가 비어 예약이 막힌다
     * ({@link AdminCatalogService#createSession}이 "좌석이 없습니다"로 거절한다).
     * 행·열을 함께 받으면 그 상태가 생기지 않는다.
     *
     * <p><b>정렬 순서는 묻지 않는다.</b> 만든 차례대로 뒤에 붙이고, 바꾸고 싶으면
     * {@link #moveZone}이 위/아래로 옮긴다.
     *
     * @param seatNoPrefix 좌석번호 접두어. 비우면 구역 이름에서 만든다({@link #derivePrefix})
     */
    @Transactional
    public long createZone(long layoutId, String name, int rows, int cols, String seatNoPrefix) {
        require(notBlank(name), "구역 이름을 입력하세요.");
        String prefix = resolvePrefix(seatNoPrefix, name);
        validateGrid(rows, cols, prefix, 1);
        requireUnused(layoutId);

        int sortOrder = (int) count(
                "SELECT COALESCE(max(sort_order), 0) FROM zone WHERE seat_layout_id = ?", layoutId) + 1;

        Long zoneId;
        try {
            zoneId = jdbc.queryForObject("""
                    INSERT INTO zone (seat_layout_id, name, sort_order) VALUES (?, ?, ?)
                    RETURNING id
                    """, Long.class, layoutId, name.strip(), sortOrder);
        } catch (DuplicateKeyException e) {
            // U-3 (seat_layout_id, name)
            throw new IllegalArgumentException(
                    "이 배치도에 [" + name.strip() + "] 구역이 이미 있습니다.");
        }
        if (zoneId == null) {
            throw new IllegalStateException("구역을 만들었는데 id를 돌려받지 못했다.");
        }

        insertGrid(zoneId, rows, cols, prefix, 0, 1);
        return zoneId;
    }

    /**
     * 이름만 바꾼다. <b>회차가 쓰는 배치도에서도 된다</b> —
     * {@code seat_inventory}가 참조하는 것은 {@code seat.id}이지 구역 이름이 아니다.
     */
    @Transactional
    public void updateZone(long zoneId, String name) {
        require(notBlank(name), "구역 이름을 입력하세요.");
        try {
            jdbc.update("UPDATE zone SET name = ? WHERE id = ?", name.strip(), zoneId);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException(
                    "이 배치도에 [" + name.strip() + "] 구역이 이미 있습니다.");
        }
    }

    /**
     * 구역을 한 칸 위/아래로 옮긴다. 정렬 순서 숫자를 직접 받는 대신 두는 것이다.
     *
     * <p><b>이웃과 값을 맞바꾸지 않고 전부 다시 매긴다.</b> 시드로 들어온 구역은
     * {@code sort_order}가 모두 0이거나 겹칠 수 있어서, 맞바꾸기만 하면 눌러도
     * 아무 일이 일어나지 않는다. 지금 보이는 차례를 1..N으로 확정한 뒤 옮긴다.
     *
     * <p>구조가 아니라 그리는 차례라 회차가 쓰는 배치도에서도 허용한다.
     */
    @Transactional
    public void moveZone(long zoneId, boolean up) {
        long layoutId = layoutIdOfZone(zoneId);
        List<Long> ordered = jdbc.queryForList(
                "SELECT id FROM zone WHERE seat_layout_id = ? ORDER BY sort_order, id",
                Long.class, layoutId);

        int from = ordered.indexOf(zoneId);
        int to = up ? from - 1 : from + 1;
        if (to < 0 || to >= ordered.size()) {
            return; // 끝이다. 화면도 그 버튼을 비활성으로 그리지만 요청이 오면 조용히 넘긴다.
        }
        ordered.set(from, ordered.set(to, zoneId));

        for (int i = 0; i < ordered.size(); i++) {
            jdbc.update("UPDATE zone SET sort_order = ? WHERE id = ?", i + 1, ordered.get(i));
        }
    }

    @Transactional
    public void deleteZone(long zoneId) {
        requireUnused(layoutIdOfZone(zoneId));
        jdbc.update("DELETE FROM seat WHERE zone_id = ?", zoneId);
        jdbc.update("DELETE FROM zone WHERE id = ?", zoneId);
    }

    // ── 좌석 ────────────────────────────────────────────────────────────

    /**
     * 구역 맨 아래에 행을 이어 붙인다. <b>받는 값은 행 수 하나뿐이다.</b>
     *
     * <p>열 수·접두어·시작 번호는 그 구역에 이미 있는 좌석에서 읽는다 — 열 수가
     * 다르면 격자가 직사각형이 아니게 되고, 접두어가 다르면 한 구역에 두 가지
     * 번호 체계가 생긴다. 물어봐야 나올 답이 하나뿐인 질문은 하지 않는다.
     *
     * <p>행 인덱스는 <b>지금 있는 최대 행 다음부터</b> 매긴다 — 1부터 다시 매기면
     * 좌석맵에서 두 무리가 겹쳐 그려진다.
     *
     * @return 새로 만든 좌석 수
     */
    @Transactional
    public int addSeatRows(long zoneId, int rows) {
        long layoutId = layoutIdOfZone(zoneId);
        requireUnused(layoutId);

        int cols = (int) count(
                "SELECT COALESCE(max(col_index), 0) FROM seat WHERE zone_id = ?", zoneId);
        require(cols > 0,
                "좌석이 하나도 없는 구역이라 몇 열로 붙일지 알 수 없습니다. "
                        + "이 구역을 지우고 행·열을 정해 다시 만드세요.");

        Numbering numbering = currentNumbering(zoneId);
        validateGrid(rows, cols, numbering.prefix(), numbering.nextNo());

        int rowOffset = (int) count(
                "SELECT COALESCE(max(row_index), 0) FROM seat WHERE zone_id = ?", zoneId);
        return insertGrid(zoneId, rows, cols, numbering.prefix(), rowOffset, numbering.nextNo());
    }

    /**
     * 좌석 한 칸을 지운다. 통로·기둥처럼 격자에서 빠지는 자리를 만들 때 쓴다.
     *
     * <p>번호를 다시 매기지 않는다 — 남은 좌석의 번호가 바뀌면 이미 안내된
     * 좌석번호와 어긋난다. 격자에 구멍이 나는 것이 의도한 결과다.
     */
    @Transactional
    public void deleteSeat(long seatId) {
        Long zoneId = jdbc.queryForObject(
                "SELECT zone_id FROM seat WHERE id = ?", Long.class, seatId);
        if (zoneId == null) {
            throw new IllegalArgumentException("좌석을 찾을 수 없습니다: " + seatId);
        }
        requireUnused(layoutIdOfZone(zoneId));
        jdbc.update("DELETE FROM seat WHERE id = ?", seatId);
    }

    // ── 좌석번호 ────────────────────────────────────────────────────────

    /** 그 구역이 지금 쓰고 있는 좌석번호 체계. */
    record Numbering(String prefix, int nextNo) {
    }

    /**
     * 이미 붙어 있는 좌석번호에서 접두어와 다음 번호를 읽는다.
     *
     * <p><b>좌석 수 + 1이 아니라 "가장 큰 번호 + 1"이다.</b> 중간 좌석을 지운 구역은
     * 좌석 수보다 번호가 앞서 있어서, 수로 세면 이미 쓰는 번호를 다시 발급해
     * U-4에 걸린다.
     *
     * <p>{@code 접두어-숫자} 꼴이 아닌 번호(시드가 손으로 넣은 것 등)는 세지 않고,
     * 그런 좌석만 있으면 구역 이름에서 만든 접두어로 1번부터 시작한다. 그래도
     * 겹치면 {@link #insertGrid}가 무엇을 고치라고 말해 준다.
     */
    private Numbering currentNumbering(long zoneId) {
        List<Map<String, Object>> found = jdbc.queryForList("""
                SELECT substring(seat_no from '^(.*)-[0-9]+$') AS prefix,
                       max(CAST(substring(seat_no from '-([0-9]+)$') AS int)) AS last_no
                  FROM seat
                 WHERE zone_id = ? AND seat_no ~ '^.+-[0-9]+$'
                 GROUP BY 1
                 ORDER BY 2 DESC
                 LIMIT 1
                """, zoneId);

        if (found.isEmpty()) {
            return new Numbering(derivePrefix(zoneName(zoneId)), 1);
        }
        Map<String, Object> row = found.get(0);
        return new Numbering((String) row.get("prefix"),
                ((Number) row.get("last_no")).intValue() + 1);
    }

    /**
     * 구역 이름에서 좌석번호 접두어를 만든다 — {@code A구역} → {@code A}.
     *
     * <p>앞쪽의 영문·숫자만 딴다. 그것이 없으면({@code 대강당}) 이름을 그대로 쓴다.
     * <b>결과가 격자에 바로 보이므로</b> 마음에 안 들면 지우고 접두어를 직접 넣어
     * 다시 만들면 된다 — 화면의 "고급"에 그 입력이 있다.
     */
    static String derivePrefix(String zoneName) {
        String name = zoneName.strip();
        int end = 0;
        while (end < name.length() && isAsciiAlnum(name.charAt(end))) {
            end++;
        }
        return end > 0 ? name.substring(0, end) : name;
    }

    private static boolean isAsciiAlnum(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    // ── 격자 생성 ───────────────────────────────────────────────────────

    /**
     * {@code rows × cols} 좌석을 한 번에 넣는다.
     *
     * <p>번호는 <b>행 우선</b>으로 증가한다 — 1행이 1..cols, 2행이 cols+1.. 이다.
     * {@code infra/demo-seed.sql}이 만드는 배열과 같은 규칙이라 시드로 만든
     * 배치도와 화면으로 만든 배치도가 같은 모양이 된다.
     */
    private int insertGrid(long zoneId, int rows, int cols, String prefix,
                           int rowOffset, int startNo) {
        try {
            return jdbc.update("""
                    INSERT INTO seat (zone_id, seat_no, row_index, col_index)
                    SELECT ?,
                           CAST(? AS text) || '-' || CAST(? + (r - 1) * ? + (c - 1) AS text),
                           ? + r,
                           c
                      FROM generate_series(1, ?) AS r,
                           generate_series(1, ?) AS c
                    """, zoneId, prefix, startNo, cols, rowOffset, rows, cols);
        } catch (DuplicateKeyException e) {
            // U-4 (zone_id, seat_no)
            throw new IllegalArgumentException(
                    "이미 있는 좌석번호와 겹칩니다 (접두어 [" + prefix + "], " + startNo
                            + "번부터). 이 구역의 좌석번호가 한 체계가 아닙니다 — "
                            + "구역을 지우고 다시 만드는 편이 빠릅니다.");
        }
    }

    private void validateGrid(int rows, int cols, String prefix, int startNo) {
        require(rows >= 1 && cols >= 1, "행 수와 열 수는 1 이상이어야 합니다.");
        require((long) rows * cols <= MAX_SEATS_PER_GRID,
                "한 번에 만들 수 있는 좌석은 " + MAX_SEATS_PER_GRID + "석까지입니다 ("
                        + rows + "행 × " + cols + "열 = " + ((long) rows * cols) + "석).");

        // seat_no가 varchar(20)이다. 넘치면 DB가 22001로 끊는데, 그 오류로는
        // 접두어를 줄이라는 말이 전달되지 않는다.
        int longest = prefix.length() + 1 + String.valueOf(startNo + rows * cols - 1).length();
        require(longest <= 20,
                "좌석번호가 20자를 넘습니다 (예상 최대 " + longest + "자). 접두어를 줄이세요.");
    }

    /** 접두어가 비면 구역 이름에서 만든다. */
    private String resolvePrefix(String seatNoPrefix, String zoneName) {
        String prefix = (seatNoPrefix == null || seatNoPrefix.isBlank())
                ? derivePrefix(zoneName) : seatNoPrefix.strip();
        require(notBlank(prefix), "좌석번호 접두어를 입력하세요.");
        require(prefix.length() <= MAX_PREFIX_LENGTH,
                "좌석번호 접두어는 " + MAX_PREFIX_LENGTH + "자 이내여야 합니다: " + prefix);
        return prefix;
    }

    // ── 삭제 정책 ───────────────────────────────────────────────────────

    /**
     * 클래스 주석의 삭제 정책을 한 곳에서 건다. 구역·좌석을 <b>더하거나 지우는</b>
     * 모든 길이 이것을 지난다. 이름 변경과 순서 변경은 지나지 않는다.
     */
    private void requireUnused(long layoutId) {
        long sessions = count(
                "SELECT count(*) FROM event_session WHERE seat_layout_id = ?", layoutId);
        require(sessions == 0,
                "이 배치도를 쓰는 회차가 " + sessions + "개 있습니다. 회차가 참조하기 시작하면 "
                        + "구역·좌석을 더하거나 지울 수 없습니다 — 좌석을 지우면 그 회차의 "
                        + "좌석맵에서 좌석이 사라집니다. 배치를 바꾸려면 새 배치도를 만들어 "
                        + "새 회차에 쓰세요.");

        long inventory = count("""
                SELECT count(*) FROM seat_inventory i
                  JOIN seat s ON s.id = i.seat_id
                  JOIN zone z ON z.id = s.zone_id
                 WHERE z.seat_layout_id = ?
                """, layoutId);
        require(inventory == 0,
                "이 배치도의 좌석을 참조하는 좌석재고가 " + inventory + "행 있습니다. "
                        + "회차 목록에는 없지만 재고가 남아 있는 상태입니다 — 그 회차를 "
                        + "먼저 정리해야 합니다.");
    }

    private long layoutIdOfZone(long zoneId) {
        Long layoutId = jdbc.queryForObject(
                "SELECT seat_layout_id FROM zone WHERE id = ?", Long.class, zoneId);
        if (layoutId == null) {
            throw new IllegalArgumentException("구역을 찾을 수 없습니다: " + zoneId);
        }
        return layoutId;
    }

    private String zoneName(long zoneId) {
        String name = jdbc.queryForObject(
                "SELECT name FROM zone WHERE id = ?", String.class, zoneId);
        if (name == null) {
            throw new IllegalArgumentException("구역을 찾을 수 없습니다: " + zoneId);
        }
        return name;
    }

    // ── 공통 ────────────────────────────────────────────────────────────

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * 어기면 {@link IllegalArgumentException}. 메시지가 <b>그대로 화면에 뜬다</b> —
     * {@link AdminCatalogService}와 같은 규칙으로, 조건이 아니라 결과와 다음 행동을 적는다.
     */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
