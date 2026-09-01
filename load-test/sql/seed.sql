-- seed.sql — 측정 프로토콜 1단계 "시드 초기화" (설계서 7.4).
--
-- 7.3 고정 변수: "시드 데이터 | 매 런 초기화". 실행마다 같은 상태에서 시작해야
-- 전략 간 숫자가 비교 가능해진다.
--
-- 사용 (scripts/seed.sh가 감싸서 호출한다):
--   psql -v seats=10 -v users=500 -v session_id=1 -v seat_id_base=1 \
--        -v max_per_user=4 -v strategy=pessimistic -f seed.sql
--
-- 스키마는 docs/erd.md의 확정본을 따른다. **엔티티가 아직 구현되지 않았으므로
-- 이 스크립트는 테이블이 생긴 뒤에야 돈다.** 지금은 계약을 미리 고정해 두는 것이
-- 목적이고, 테이블이 없으면 scripts/seed.sh가 그 사실을 명확히 알려준다.

\set ON_ERROR_STOP on

BEGIN;

-- 매 런 초기화. 좌석·홀드·예약 계열만 지우고 배치도는 다시 만든다.
TRUNCATE TABLE
  ticket_scan, ticket, outbox, idempotency_record,
  payment, reservation_seat, reservation,
  seat_hold, seat_inventory, user_session_quota,
  seat, zone, seat_layout, event_session, program
RESTART IDENTITY CASCADE;

-- 프로그램·배치도·구역
INSERT INTO program (id, name, description, created_at)
VALUES (1, '부하 테스트 프로그램', '설계서 7장 측정용', now());

INSERT INTO seat_layout (id, name, created_at)
VALUES (1, '부하 테스트 배치도', now());

INSERT INTO zone (id, seat_layout_id, name, sort_order)
VALUES (1, 1, 'A', 1);

-- 좌석 N석. ID는 :seat_id_base 부터 연속으로 둔다 — k6가 좌석을 고를 때
-- 이 연속 구간을 그대로 쓴다(scenarios/lib/config.js).
INSERT INTO seat (id, zone_id, seat_no, row_index, col_index)
SELECT
  :seat_id_base + g,
  1,
  'A-' || (g + 1),
  (g / 20) + 1,
  (g % 20) + 1
FROM generate_series(0, :seats - 1) AS g;

-- 회차. 예약은 이미 열려 있어야 하고(RESERVATION_NOT_OPEN이 나오면 안 된다),
-- 1인 최대 매수는 :max_per_user 로 둔다(REQ-03 / CS-6).
INSERT INTO event_session (
  id, program_id, seat_layout_id,
  starts_at, ends_at, entry_opens_at, entry_closes_at, reserve_opens_at,
  max_per_user, status
) VALUES (
  :session_id, 1, 1,
  now() + interval '1 day', now() + interval '1 day 2 hours',
  now() + interval '1 day' - interval '30 minutes', now() + interval '1 day 2 hours',
  now() - interval '1 hour',
  :max_per_user, 'OPEN'
);

-- 좌석재고를 회차 × 좌석 행으로 **사전 생성**한다(concurrency-spec 0.4).
-- 이 결정이 없으면 행 단위 락과 유니크 제약을 둘 다 쓸 수 없다.
INSERT INTO seat_inventory (session_id, seat_id, status, hold_id, held_until, version)
SELECT :session_id, s.id, 'AVAILABLE', NULL, NULL, 0
FROM seat s;

-- 1인 최대 매수 집계 행도 사전 생성한다(concurrency-spec 1.1의 채택안).
-- k6는 VU 번호를 사용자 ID로 쓰므로 1..:users 를 만들어 둔다.
INSERT INTO user_session_quota (session_id, user_id, held_count)
SELECT :session_id, u, 0
FROM generate_series(1, :users) AS u;

COMMIT;

-- U-2(활성 홀드 중복 차단)는 **여기서 만들지 않는다.**
--
-- 전략에 따라 켜고 꺼야 하는데, psql 조건문으로 분기하면 읽기 어려워져서
-- scripts/seed.sh가 sql/u2-create.sql / u2-drop.sql 중 하나를 골라 실행한다.
--
-- **none에서만 제약을 뺀다.** none은 "락 없이 돌렸을 때 초과 예약이 몇 건 나는가"를
-- 만드는 베이스라인이라, 제약을 걸면 초과 예약이 0건으로 나와 2개월차 산출물인
-- 실패 데이터를 얻을 수 없다(erd.md 3.1, concurrency-spec 2.1).
