-- demo-seed.sql — M4 최소 완결 흐름을 화면에서 시연하기 위한 시드.
--
-- **load-test/sql/seed.sql과 목적이 다르다.** 그쪽은 부하 측정용이라
-- 검표를 하지 않으므로 입장 가능 시간을 내일로 두고, 홀드 TTL도 10초로
-- 짧게 잡는다. 그 값으로 화면 데모를 하면 세 곳에서 막힌다.
--
--   선점이 10초 만에 만료된다        → HOLD_TTL_SECONDS=300 으로 띄운다
--   만료된 좌석이 회수되지 않는다     → HOLDFAST_STRATEGY=pessimistic 으로 띄운다
--                                      (none은 회수 경로가 아예 없다. 그것이
--                                       베이스라인의 정의다 — NoneSeatHoldStrategy)
--   검표가 항상 "입장 시간 아님"      → 이 파일이 입장 창을 지금으로 연다
--
-- 실행법은 README의 "데모 실행" 절에 있다.

BEGIN;

-- 예약·발권 계열만 지우고 배치도는 다시 만든다. load-test 시드와 같은 목록이다.
TRUNCATE TABLE
  ticket_scan, ticket, outbox, idempotency_record,
  payment, reservation_seat, reservation,
  seat_hold, seat_inventory, user_session_quota,
  seat, zone, seat_layout, event_session, program
RESTART IDENTITY CASCADE;

INSERT INTO program (id, name, description) VALUES (1, '데모 공연', '화면 시연용');
INSERT INTO seat_layout (id, name) VALUES (1, '데모 배치도');

-- 두 구역 × 12석. 한 줄짜리 격자보다 좌석맵이 좌석맵처럼 보인다.
INSERT INTO zone (id, seat_layout_id, name, sort_order) VALUES
  (1, 1, 'A구역', 1),
  (2, 1, 'B구역', 2);

INSERT INTO seat (id, zone_id, seat_no, row_index, col_index)
SELECT 100 + g, 1, 'A-' || (g + 1), (g / 6) + 1, (g % 6) + 1
FROM generate_series(0, 11) AS g;

INSERT INTO seat (id, zone_id, seat_no, row_index, col_index)
SELECT 200 + g, 2, 'B-' || (g + 1), (g / 6) + 1, (g % 6) + 1
FROM generate_series(0, 11) AS g;

-- 회차. **입장 가능 시간이 지금을 포함한다** — 이것이 부하 측정용 시드와의
-- 핵심 차이다. 그래야 검표(REQ-06)가 통과한다.
INSERT INTO event_session (
  id, program_id, seat_layout_id,
  starts_at, ends_at, entry_opens_at, entry_closes_at, reserve_opens_at,
  max_per_user, status
) VALUES (
  1, 1, 1,
  now() + interval '1 hour', now() + interval '3 hours',
  now() - interval '1 hour', now() + interval '6 hours',
  now() - interval '1 hour',
  4, 'OPEN'
);

-- 좌석재고는 회차 × 좌석으로 사전 생성한다(concurrency-spec 0.4).
INSERT INTO seat_inventory (session_id, seat_id, status, hold_id, held_until, version)
SELECT 1, s.id, 'AVAILABLE', NULL, NULL, 0 FROM seat s;

-- 1인 최대 매수 집계 행도 사전 생성한다. **없으면 홀드가 500으로 실패한다**
-- (SeatHoldService가 "할당량 행이 없습니다"로 던진다). 화면은 X-User-Id=1을
-- 쓰지만(SeatMapPageController.DEV_USER_ID) 여유 있게 열 명을 만들어 둔다.
INSERT INTO user_session_quota (session_id, user_id, held_count)
SELECT 1, u, 0 FROM generate_series(1, 10) AS u;

-- **시퀀스를 데이터에 맞춘다.** id를 명시해 INSERT하면 IDENTITY 시퀀스는 올라가지
-- 않고, TRUNCATE ... RESTART IDENTITY는 시퀀스를 1로 되돌린다. 그 상태에서 앱이
-- 자동 생성으로 INSERT하면 id=1을 다시 발급해 program_pkey에 걸린다 — 관리자
-- 등록 화면(#101)이 실제로 그 500을 냈다.
--
-- **id를 명시하는 쪽은 그대로 둔다.** k6의 SESSION_ID·SEAT_ID_BASE 기본값 1과
-- verify.sql의 -v session_id=1이 이 값을 전제하므로, 시퀀스에 맡기면 측정 절차가
-- 바뀐다. 시드가 id를 정하고 시퀀스를 그 뒤로 밀어 두면 양쪽이 같은 테이블을
-- 나눠 쓸 수 있다.
--
-- 테이블을 열거하지 않는다 — 목록에 추가하는 것을 잊는 것이 이 버그의 원인과
-- 같은 종류다. IDENTITY 컬럼을 가진 테이블을 전부 훑는다.
-- 근거와 다른 두 선택지는 support/IdentitySequences.java에 적었다.
DO $$
DECLARE
    r   record;
    seq text;
    mx  bigint;
BEGIN
    FOR r IN
        SELECT c.table_name, c.column_name
          FROM information_schema.columns c
          JOIN information_schema.tables t
            ON t.table_schema = c.table_schema AND t.table_name = c.table_name
         WHERE c.table_schema = current_schema()
           AND t.table_type = 'BASE TABLE'
           AND c.is_identity = 'YES'
    LOOP
        seq := pg_get_serial_sequence(quote_ident(r.table_name), r.column_name);
        CONTINUE WHEN seq IS NULL;
        EXECUTE format('SELECT max(%I) FROM %I', r.column_name, r.table_name) INTO mx;
        PERFORM setval(seq, COALESCE(mx, 1), mx IS NOT NULL);
    END LOOP;
END
$$;

COMMIT;

-- U-2 활성 홀드 중복 차단. none을 제외한 네 전략의 최후 방어선이며 데모는
-- pessimistic으로 돌리므로 있어야 한다(erd.md 3.1). 정의는
-- load-test/sql/u2-create.sql과 같아야 한다.
CREATE UNIQUE INDEX IF NOT EXISTS ux_seat_hold_active
  ON seat_hold (session_id, seat_id)
  WHERE status = 'HELD';
