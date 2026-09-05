package com.inhalab.holdfast.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 프로그램·회차 등록. 이슈 #101 — SFR-005(관리자 운영 편의).
 *
 * <p>이 화면이 없으면 프로그램 하나를 열 때마다 개발자가 터미널에서 SQL을
 * 돌려야 한다. 지금까지 회차는 {@code load-test/sql/seed.sql}과
 * {@code infra/demo-seed.sql}이 만들었다.
 *
 * <h2>왜 JdbcTemplate인가</h2>
 *
 * <p>{@code Program}·{@code EventSession}·{@code SeatInventory} 엔티티의 기본
 * 생성자가 {@code protected}라 다른 패키지에서 만들 수 없다. 그 파일들은
 * {@code seat/}·{@code reservation/} 소유라 고치지 않는다(#79 이후 지켜온 경계).
 * 좌석재고 사전 생성은 어차피 {@code INSERT ... SELECT}가 자연스러운 벌크
 * 연산이고, 시드 스크립트가 하던 일과 같은 SQL이다.
 *
 * <h2>회차를 만들면 좌석재고도 함께 만든다</h2>
 *
 * <p><b>좌석재고를 미리 만드는 것이 좌석 단위 락의 전제다</b>
 * (design-spec 3.2, concurrency-spec 2.1). 회차만 만들고 재고가 없으면 좌석맵이
 * 비고 홀드가 {@code SEAT_NOT_IN_SESSION}으로 막힌다.
 *
 * <h2>할당량 행도 함께 만든다</h2>
 *
 * <p>{@code SeatHoldService}는 {@code user_session_quota} 행이 <b>미리 있어야</b>
 * 홀드를 받는다(concurrency-spec 1.1 CS-6 채택안 — 사전 생성). 없으면 홀드가
 * 500으로 실패한다. 회원 개념이 없어 "누구의 행을 만들 것인가"가 정해지지
 * 않으므로, 사용자 풀 크기를 등록 화면에서 받아 1..N을 만든다 — 시드
 * 스크립트가 하던 것과 같다. 회원 기능이 생기면 이 자리가 가입 시점으로 옮겨간다.
 */
@Service
public class AdminCatalogService {

    /** {@code event_session.status}의 세 값. erd.md 2절 정의이며 S-1이 이것만 받는다. */
    public static final String SCHEDULED = "SCHEDULED";
    public static final String OPEN = "OPEN";
    public static final String CLOSED = "CLOSED";

    private final JdbcTemplate jdbc;

    public AdminCatalogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void createProgram(String name, String description) {
        jdbc.update("INSERT INTO program (name, description, created_at) VALUES (?, ?, now())",
                name, blankToNull(description));
    }

    @Transactional
    public void updateProgram(long programId, String name, String description) {
        jdbc.update("UPDATE program SET name = ?, description = ? WHERE id = ?",
                name, blankToNull(description), programId);
    }

    /**
     * 회차를 만들고 좌석재고·할당량 행을 함께 채운다.
     *
     * @param userPoolSize 만들어 둘 {@code user_session_quota} 행 수(사용자 1..N)
     */
    @Transactional
    public long createSession(long programId,
                              long seatLayoutId,
                              Instant startsAt,
                              Instant endsAt,
                              Instant entryOpensAt,
                              Instant entryClosesAt,
                              Instant reserveOpensAt,
                              int maxPerUser,
                              String status,
                              int userPoolSize) {

        validateSchedule(startsAt, endsAt, entryOpensAt, entryClosesAt, reserveOpensAt);
        validateStatus(status);
        require(maxPerUser >= 1, "1인 최대 매수는 1 이상이어야 합니다.");
        require(userPoolSize >= 1, "사용자 풀 크기는 1 이상이어야 합니다.");

        Long sessionId = jdbc.queryForObject("""
                INSERT INTO event_session (
                    program_id, seat_layout_id, starts_at, ends_at,
                    entry_opens_at, entry_closes_at, reserve_opens_at,
                    max_per_user, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class,
                programId, seatLayoutId,
                java.sql.Timestamp.from(startsAt), java.sql.Timestamp.from(endsAt),
                java.sql.Timestamp.from(entryOpensAt), java.sql.Timestamp.from(entryClosesAt),
                java.sql.Timestamp.from(reserveOpensAt),
                maxPerUser, status);

        if (sessionId == null) {
            throw new IllegalStateException("회차를 만들었는데 id를 돌려받지 못했다.");
        }

        // 배치도의 모든 좌석에 대해 재고 행을 만든다. 좌석은 zone을 거쳐 배치도에 속한다.
        int seats = jdbc.update("""
                INSERT INTO seat_inventory (session_id, seat_id, status, hold_id, held_until, version)
                SELECT ?, s.id, 'AVAILABLE', NULL, NULL, 0
                FROM seat s
                JOIN zone z ON z.id = s.zone_id
                WHERE z.seat_layout_id = ?
                """, sessionId, seatLayoutId);

        if (seats == 0) {
            // 좌석 없는 회차는 만들자마자 아무도 예약할 수 없다. 조용히 두면
            // 좌석맵이 빈 격자로 뜨고 원인을 찾기 어렵다.
            throw new IllegalArgumentException(
                    "이 좌석배치도에는 좌석이 없습니다. 좌석배치를 먼저 등록하세요 (배치도 ID " + seatLayoutId + ").");
        }

        jdbc.update("""
                INSERT INTO user_session_quota (session_id, user_id, held_count)
                SELECT ?, u, 0 FROM generate_series(1, ?) AS u
                """, sessionId, userPoolSize);

        return sessionId;
    }

    /**
     * 회차를 수정한다.
     *
     * <p><b>{@code program_id}와 {@code seat_layout_id}는 바꾸지 않는다.</b>
     * 배치도를 바꾸면 이미 만들어 둔 {@code seat_inventory}가 다른 배치도의
     * 좌석을 가리키게 되고, 판매된 좌석이 있으면 되돌릴 수도 없다. 배치도를
     * 바꾸려면 회차를 새로 만든다.
     *
     * <h3>이미 판 뒤에 무엇을 막고 무엇을 허용하는가</h3>
     *
     * <p>근거는 {@code erd.md} 2.2의 S-2~S-5다. 요지는 <b>거짓이 되는 변경은
     * 막고, 운영상 정상인 변경은 알리고 통과시킨다</b>는 것이다.
     *
     * @return 막지는 않았지만 운영자가 알아야 할 경고. 비어 있으면 조용히 성공했다.
     */
    @Transactional
    public List<String> updateSession(long sessionId,
                                      Instant startsAt,
                                      Instant endsAt,
                                      Instant entryOpensAt,
                                      Instant entryClosesAt,
                                      Instant reserveOpensAt,
                                      int maxPerUser,
                                      String status) {

        validateSchedule(startsAt, endsAt, entryOpensAt, entryClosesAt, reserveOpensAt);
        validateStatus(status);
        require(maxPerUser >= 1, "1인 최대 매수는 1 이상이어야 합니다.");

        List<String> warnings = new ArrayList<>();

        // S-2. SCHEDULED는 "아직 판 적 없다"는 뜻이다. 예약이 있는데 그리로
        // 되돌리면 카탈로그에서는 사라지는데 발권된 티켓은 살아 있어 갈린다.
        long liveReservations = count("""
                SELECT count(*) FROM reservation
                 WHERE session_id = ? AND status NOT IN ('CANCELLED', 'EXPIRED')
                """, sessionId);
        require(!(SCHEDULED.equals(status) && liveReservations > 0),
                "이미 예약 " + liveReservations + "건을 받은 회차는 '" + SCHEDULED
                        + "'로 되돌릴 수 없습니다. 접수를 닫으려면 '" + CLOSED + "'로 바꾸세요.");

        // S-3. 판 사실과 모순되는 값이다. saleStateOf가 NOT_YET_OPEN을 띄워
        // 산 사람이 자기 회차를 목록에서 못 찾는다.
        long soldSeats = count(
                "SELECT count(*) FROM seat_inventory WHERE session_id = ? AND status = 'SOLD'",
                sessionId);
        require(!(soldSeats > 0 && reserveOpensAt.isAfter(Instant.now())),
                "이미 좌석 " + soldSeats + "석이 판매된 회차의 예약 오픈을 미래로 옮길 수 없습니다.");

        // S-4. 막지 않는다 — 막으면 한 번 넉넉히 잡은 상한을 영영 못 고친다.
        // 기존 예약은 CS-6이 홀드 시점에만 보므로 소급 무효가 되지 않는다.
        long overQuota = count(
                "SELECT count(*) FROM user_session_quota WHERE session_id = ? AND held_count > ?",
                sessionId, maxPerUser);
        if (overQuota > 0) {
            warnings.add("이미 " + maxPerUser + "매를 넘겨 예약한 사용자가 " + overQuota
                    + "명 있습니다. 기존 예약은 그대로 두고 추가 예약만 막힙니다.");
        }

        // S-5. 입장 창 변경은 정상 운영이다. 다만 영향받는 티켓 수를 알린다.
        if (soldSeats > 0) {
            warnings.add("판매된 좌석 " + soldSeats + "석이 이 회차를 참조합니다. "
                    + "입장 시간을 바꾸면 이미 발권된 티켓에 그대로 적용됩니다.");
        }

        jdbc.update("""
                UPDATE event_session
                   SET starts_at = ?, ends_at = ?,
                       entry_opens_at = ?, entry_closes_at = ?, reserve_opens_at = ?,
                       max_per_user = ?, status = ?
                 WHERE id = ?
                """,
                java.sql.Timestamp.from(startsAt), java.sql.Timestamp.from(endsAt),
                java.sql.Timestamp.from(entryOpensAt), java.sql.Timestamp.from(entryClosesAt),
                java.sql.Timestamp.from(reserveOpensAt),
                maxPerUser, status, sessionId);

        return warnings;
    }

    /**
     * 회차를 지운다. <b>예약이 하나도 없는 회차만</b> 지울 수 있다.
     *
     * <h3>왜 이 기준인가 — {@code erd.md} 2.2의 S-6</h3>
     *
     * <p>S-2가 "예약이 있으면 {@code SCHEDULED}로 되돌릴 수 없다"를 정했다.
     * 되돌리기가 <b>"판 적 없다고 말하는 것"</b>이라면 삭제는 <b>"판 기록을
     * 없애는 것"</b>이다. 되돌리기를 막았으면서 삭제를 허용할 근거가 없다.
     * 예약이 있는 회차를 닫는 수단은 이미 {@code CLOSED}로 있고 S-2가 그쪽을
     * 명시적으로 허용해 뒀다.
     *
     * <h3>S-2의 조건을 그대로 쓰지 않는다</h3>
     *
     * <p>S-2는 <b>살아 있는</b> 예약만 센다
     * ({@code status NOT IN ('CANCELLED','EXPIRED')}). 그 조건으로 삭제를
     * 판정하면 <b>만료된 예약만 남은 회차가 통과하고 곧바로
     * {@code fk_reservation_session}에 걸린다.</b> 두 규칙이 묻는 것이 다르다 —
     * 되돌리기는 "지금 유효한 예약이 있는가", 삭제는 "이 회차에 무슨 일이든
     * 있었는가"다. 그래서 여기서는 {@code status}를 가리지 않는다.
     *
     * <h3>함께 지우는 것은 둘뿐이다</h3>
     *
     * <p>{@code event_session}을 참조하는 FK는 넷인데({@code seat_inventory},
     * {@code user_session_quota}, {@code seat_hold}, {@code reservation})
     * <b>{@code seat_hold}에 걸릴 행은 없다.</b> {@code SeatHoldService}가 홀드를
     * 넣는 같은 트랜잭션에서 {@code reservation} 행을 만들고({@code erd.md} 4절
     * "예약은 홀드 시점에 생성된다"), 만료돼도 그 행을 지우지 않는다. 예약이
     * 0이면 홀드도 0이다.
     *
     * <h3>회차 행을 먼저 잠근다</h3>
     *
     * <p>세는 것과 지우는 것 사이에 홀드가 들어오면 FK 위반으로 500이 된다.
     * {@code FOR UPDATE}는 예약 INSERT가 부모 행에 거는 {@code KEY SHARE}와
     * 충돌하므로 그 창을 닫는다. <b>홀드 경로는 바뀌지 않는다</b> — 그쪽이
     * 부모 행에 {@code KEY SHARE}를 거는 것은 FK가 원래 하던 일이고, 이 락은
     * 삭제하는 쪽에만 있다.
     */
    @Transactional
    public void deleteSession(long sessionId) {
        Long locked = jdbc.query(
                "SELECT id FROM event_session WHERE id = ? FOR UPDATE",
                rs -> rs.next() ? rs.getLong(1) : null, sessionId);
        require(locked != null, "회차를 찾을 수 없습니다: " + sessionId);

        long reservations = count(
                "SELECT count(*) FROM reservation WHERE session_id = ?", sessionId);
        require(reservations == 0,
                "예약 " + reservations + "건이 있는 회차는 지울 수 없습니다. "
                        + "접수를 닫으려면 상태를 '" + CLOSED + "'로 바꾸세요.");

        jdbc.update("DELETE FROM seat_inventory WHERE session_id = ?", sessionId);
        jdbc.update("DELETE FROM user_session_quota WHERE session_id = ?", sessionId);
        jdbc.update("DELETE FROM event_session WHERE id = ?", sessionId);
    }

    // ── 검증 ────────────────────────────────────────────────────────────

    /**
     * 다섯 시각의 순서를 본다. 근거는 {@code erd.md} 2.2의 T-1~T-4.
     *
     * <p><b>이것은 등록 시점의 입력을 막는 것이지 판정을 대신하지 않는다.</b>
     * 예약 가능 여부와 입장 가능 여부는 {@code SeatHoldService}·
     * {@code TicketService}가 실행 시각으로 다시 본다.
     */
    static void validateSchedule(Instant startsAt,
                                 Instant endsAt,
                                 Instant entryOpensAt,
                                 Instant entryClosesAt,
                                 Instant reserveOpensAt) {
        // T-1
        require(startsAt.isBefore(endsAt),
                "회차 종료가 시작보다 빠릅니다. 시작 < 종료여야 합니다.");
        // T-2 — 어기면 TicketService의 두 비교가 동시에 참이라 아무도 못 들어온다.
        require(entryOpensAt.isBefore(entryClosesAt),
                "입장 종료가 입장 시작보다 빠릅니다. 이대로면 아무도 입장할 수 없습니다.");
        // T-3 — 예약이 열리는 시점에 입장이 이미 끝난 회차다.
        require(reserveOpensAt.isBefore(entryClosesAt),
                "예약 오픈이 입장 종료보다 늦습니다. 예약해도 쓸 수 없는 표가 됩니다.");
        // T-4
        require(entryOpensAt.isBefore(endsAt),
                "입장 시작이 회차 종료보다 늦습니다. 회차가 끝난 뒤에 문이 열립니다.");
    }

    static void validateStatus(String status) {
        require(SCHEDULED.equals(status) || OPEN.equals(status) || CLOSED.equals(status),
                "회차 상태는 " + SCHEDULED + " / " + OPEN + " / " + CLOSED
                        + " 중 하나여야 합니다: " + status);
    }

    /**
     * 어기면 {@link IllegalArgumentException}. 메시지가 <b>그대로 화면에 뜬다</b> —
     * "무엇이 잘못됐는지"를 운영자가 읽을 수 있어야 하므로 조건이 아니라 결과를
     * 적는다("시작 &lt; 종료여야 합니다"가 아니라 "종료가 시작보다 빠릅니다").
     */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
