package com.inhalab.holdfast.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

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
     */
    @Transactional
    public void updateSession(long sessionId,
                              Instant startsAt,
                              Instant endsAt,
                              Instant entryOpensAt,
                              Instant entryClosesAt,
                              Instant reserveOpensAt,
                              int maxPerUser,
                              String status) {
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
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
