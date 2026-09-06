-- pages-fixture.sql — PER-002 화면 측정용 데이터 (이슈 #105).
--
-- `seed.sql`이 만드는 것은 **카탈로그와 빈 재고**뿐이다. 그 상태로 화면을 재면
-- 예약 조회·마이페이지·관리자 예약관리가 전부 **빈 표**를 그린다. PER-002 원문은
-- "요청한 화면·**조회 결과**가 3초 이내 완전 표시"이므로, 조회 결과가 없는 화면을
-- 재면 재라고 한 것을 재지 않은 것이 된다.
--
-- 사용 (scripts/run-pages.sh가 감싸서 호출한다):
--   psql -v session_id=1 -v reservations=250 -f pages-fixture.sql
--
-- ## 250건을 만드는 이유 — 관리자 화면의 상한이 실제로 걸려야 한다
--
-- `/admin/reservations`는 **최근 200건**을 그리고, 줄마다
-- `ReservationService#seatsOf`를 한 번씩 부른다(N+1을 의도적으로 받아들인
-- 자리다 — `AdminPageController` 주석). 예약이 10건이면 그 비용이 드러나지
-- 않는다. 상한보다 많이 만들어 **200줄 전부가 그려지는 상태**를 잰다.
--
-- ## 좌석을 SOLD로 바꾸는 것은 화면을 위해서이지 정합성을 위해서가 아니다
--
-- 확정된 예약의 좌석이 `AVAILABLE`로 남아 있으면 회차 목록의 잔여석과 관리자
-- 통계가 서로 다른 이야기를 하게 되고(#104), 그 상태를 재면 **화면이 실제
-- 운영에서 그릴 일 없는 조합**을 그린다.
--
-- ## 시퀀스를 반드시 다시 맞춘다
--
-- id를 명시해 INSERT하면 IDENTITY 시퀀스가 올라가지 않는다(erd.md 2.1).
-- 그대로 두면 **부하 배경이 도는 조건에서 앱이 id 1을 다시 발급해
-- `reservation_pkey`에 걸린다** — 화면을 재려고 만든 데이터가 부하 쪽 오류율을
-- 올리는 것이라, 알아채기 전까지는 앱 결함으로 보인다.

\set ON_ERROR_STOP on

BEGIN;

-- 이 파일은 seed.sql 직후에만 돈다. 예약 계열이 비어 있는 것이 전제다.
DELETE FROM ticket_scan;
DELETE FROM ticket;
DELETE FROM reservation_seat;
DELETE FROM reservation;

-- 좌석재고 중 앞쪽 :reservations * 2 개를 화면용으로 쓴다. 뒤쪽은 배경 부하가
-- 겨룰 몫으로 남긴다 — 부하가 잡을 좌석이 없으면 측정 구간이 전부
-- SEAT_ALREADY_SOLD가 되어 배경이 "부하"가 아니라 "거절 처리"가 된다.
CREATE TEMP TABLE fixture_seat ON COMMIT DROP AS
SELECT si.id AS seat_inventory_id,
       row_number() OVER (ORDER BY si.id) AS n
  FROM seat_inventory si
 WHERE si.session_id = :session_id
 ORDER BY si.id
 LIMIT (:reservations * 2);

-- 예약 :reservations 건. 상태를 넷으로 돌려 관리자 화면의 상태 열이 실제로
-- 갈리게 한다. 1번은 조회 화면이 여는 예약이라 반드시 CONFIRMED다.
INSERT INTO reservation (id, session_id, user_id, hold_id, status,
                         total_amount, created_at, confirmed_at, cancelled_at)
SELECT
  g,
  :session_id,
  ((g - 1) % 200) + 1,                       -- 사용자 1..200. 시드의 사용자 풀 안이다
  'fixture-' || g,
  CASE
    WHEN g = 1 THEN 'CONFIRMED'
    WHEN g % 10 = 0 THEN 'CANCELLED'
    WHEN g % 7 = 0 THEN 'EXPIRED'
    WHEN g % 5 = 0 THEN 'HELD'
    ELSE 'CONFIRMED'
  END,
  10000,
  now() - (g || ' minutes')::interval,
  CASE WHEN g = 1 OR (g % 10 <> 0 AND g % 7 <> 0 AND g % 5 <> 0)
       THEN now() - (g || ' minutes')::interval END,
  CASE WHEN g <> 1 AND g % 10 = 0
       THEN now() - (g || ' minutes')::interval END
FROM generate_series(1, :reservations) AS g;

-- 확정·선점된 예약에만 좌석을 붙인다. 취소·만료는 좌석을 놓은 상태다
-- (state-transitions.md — 해제 사유는 reservation.status가 담당한다).
INSERT INTO reservation_seat (reservation_id, seat_inventory_id)
SELECT r.id, f.seat_inventory_id
  FROM reservation r
  JOIN fixture_seat f
    ON f.n IN (2 * r.id - 1, 2 * r.id)
 WHERE r.status IN ('CONFIRMED', 'HELD');

-- 화면이 그리는 재고 상태를 예약과 맞춘다.
UPDATE seat_inventory si
   SET status = 'SOLD'
  FROM reservation_seat rs
  JOIN reservation r ON r.id = rs.reservation_id
 WHERE rs.seat_inventory_id = si.id AND r.status = 'CONFIRMED';

UPDATE seat_inventory si
   SET status = 'HELD',
       hold_id = 'fixture-hold-' || si.id,
       held_until = now() + interval '30 minutes'
  FROM reservation_seat rs
  JOIN reservation r ON r.id = rs.reservation_id
 WHERE rs.seat_inventory_id = si.id AND r.status = 'HELD';

-- 발권. 확정 예약의 좌석마다 한 장이고(U-9), 절반은 검표를 통과한 상태로 둔다 —
-- 관리자 화면의 발권·검표 열이 갈리는 것을 보려면 둘 다 있어야 한다(#104).
INSERT INTO ticket (reservation_seat_id, qr_token, status, issued_at, used_at)
SELECT rs.id,
       'fixture-qr-' || rs.id,
       CASE WHEN rs.id % 2 = 0 THEN 'USED' ELSE 'ISSUED' END,
       now(),
       CASE WHEN rs.id % 2 = 0 THEN now() END
  FROM reservation_seat rs
  JOIN reservation r ON r.id = rs.reservation_id
 WHERE r.status = 'CONFIRMED';

-- **시퀀스를 데이터 뒤로 민다.** seed.sql과 같은 이유이고 같은 방식이다 —
-- 테이블을 열거하지 않고 IDENTITY 컬럼을 가진 것을 전부 훑는다. 목록에 추가하는
-- 것을 잊는 것이 이 버그의 원인과 같은 종류이기 때문이다(erd.md 2.1).
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

-- 화면 시나리오가 무엇을 열지 확정해 두고 찍는다. run-pages.sh가 이 값을
-- 읽어 k6에 넘기므로, 여기서 바꾸면 시나리오도 함께 바뀐다.
SELECT 'fixture' AS what,
       (SELECT count(*) FROM reservation) AS reservations,
       (SELECT count(*) FROM reservation_seat) AS reservation_seats,
       (SELECT count(*) FROM ticket) AS tickets,
       (SELECT count(*) FROM seat_inventory WHERE status = 'AVAILABLE') AS available,
       (SELECT user_id FROM reservation WHERE id = 1) AS lookup_user_id;
