-- verify-cancel-selfcheck.sql — **검사가 실제로 잡는지 확인한다.** CS-4 / 7.2.4.
--
-- ## 왜 필요한가
--
-- `verify-cancel.sql` 이 0을 내면 두 가지 중 하나다 — **안 깨졌거나, 못 보거나.**
-- 그것을 가르지 못하면 그 0은 판정이 아니다. #194가 CS-6에서 겪은 것이
-- 정확히 그 형태다: 끝 상태로 세는 설계에서는 위반이 있어도 0이 나왔다.
--
-- **그래서 일부러 어긋내 본다.** 위반을 셋 심고, 세 검사가 각각 집어내는지
-- 본다. 통과만 보고 회귀 수단이라고 하지 않는다.
--
-- ## 데이터를 남기지 않는다
--
-- 전부 한 트랜잭션 안에서 하고 **ROLLBACK 으로 끝낸다.** 측정용 DB에 그대로
-- 쏴도 상태가 바뀌지 않는다. 실행 중인 부하와 겹쳐 돌리지는 않는다 — 심은
-- 행이 그 순간의 집계에 섞인다.
--
-- 사용: psql ... -v session_id=1 -f verify-cancel-selfcheck.sql
--
-- **기대 결과: 세 줄 모두 「잡았다」이고 건수가 각각 1, 마지막 줄은 「못 본다」.**
-- 하나라도 「못 잡았다」면 그 검사는 회귀 수단이 아니며 `verify-cancel.sql` 의
-- 해당 지표를 판정에 쓰면 안 된다.

\set ON_ERROR_STOP on
\pset border 2

BEGIN;

-- 심을 자리. 이 회차에서 실제로 쓰인 좌석 둘과 사용자 하나를 빌린다.
-- **seat_b 는 «지금 열려 있는 확정 구간이 없는» 좌석이어야 한다.** SOLD 인
-- 좌석을 빌리면 그 좌석에 살아 있는 확정 구간이 이미 있어, 위반 2를 심는 순간
-- 구간이 둘이 되어 C-1 까지 함께 올라간다 — 검사마다 기대값이 1이 되도록
-- 자리를 갈라 둔다.
CREATE TEMP TABLE sc_fix ON COMMIT DROP AS
SELECT (SELECT min(id) FROM seat_inventory WHERE session_id = :session_id) AS seat_a,
       (SELECT max(id) FROM seat_inventory
         WHERE session_id = :session_id AND status <> 'SOLD'
           AND id <> (SELECT min(id) FROM seat_inventory WHERE session_id = :session_id)) AS seat_b,
       (SELECT min(user_id) FROM user_session_quota WHERE session_id = :session_id) AS usr;

-- 빌릴 자리가 없으면 자체 검사가 성립하지 않는다. 조용히 지나가지 않는다.
DO $$
BEGIN
  IF (SELECT seat_a IS NULL OR seat_b IS NULL OR usr IS NULL FROM sc_fix) THEN
    RAISE EXCEPTION '자체 검사에 빌릴 좌석/사용자가 없다 — 시드를 먼저 돌린다';
  END IF;
END $$;

-- ── 위반 1 — 같은 좌석을 두 예약이 «동시에» 확정 보유했다 ───────────────
--
-- **끝 상태로는 안 보이게 심는다.** 둘 다 CANCELLED 로 두고 구간만 겹친다 —
-- 이것이 V-1 이 못 보는 자리이고 C-1 이 있어야 하는 이유다.
--
-- **시각을 앞이 아니라 뒤에 둔다.** 처음에는 now() - 9분 같은 과거로 심었는데,
-- 방금 끝난 회차의 구간이 그 안에 들어와 **빌린 좌석 말고 다른 좌석까지
-- 겹쳐 올라갔다**(C-1 이 1 대신 2). 실제 데이터보다 뒤에 두면 겹칠 대상이
-- 심은 것뿐이라 검사마다 기대값이 1로 확정된다.
WITH ins AS (
  INSERT INTO reservation (session_id, user_id, hold_id, status, total_amount,
                           created_at, confirmed_at, cancelled_at)
  SELECT :session_id, usr, 'selfcheck-c1-a', 'CANCELLED', 0,
         now(), now() + interval '1 min', now() + interval '3 min' FROM sc_fix
  UNION ALL
  SELECT :session_id, usr, 'selfcheck-c1-b', 'CANCELLED', 0,
         now(), now() + interval '2 min', now() + interval '4 min' FROM sc_fix
  RETURNING id, hold_id
)
INSERT INTO reservation_seat (reservation_id, seat_inventory_id)
SELECT ins.id, sc_fix.seat_a FROM ins, sc_fix;

-- ── 위반 2 — 확정된 예약인데 재고가 SOLD 가 아니다 ─────────────────────
--
-- 「남의 좌석을 풀었다」의 흔적이다. V-4 는 SOLD 행에서 출발하므로 이 방향을
-- 보지 않는다.
WITH ins AS (
  INSERT INTO reservation (session_id, user_id, hold_id, status, total_amount,
                           created_at, confirmed_at)
  SELECT :session_id, usr, 'selfcheck-c2', 'CONFIRMED', 0,
         now(), now() + interval '1 min' FROM sc_fix
  RETURNING id
)
INSERT INTO reservation_seat (reservation_id, seat_inventory_id)
SELECT ins.id, sc_fix.seat_b FROM ins, sc_fix;

UPDATE seat_inventory SET status = 'AVAILABLE', hold_id = NULL, held_until = NULL
WHERE id = (SELECT seat_b FROM sc_fix);

-- ── 위반 3 — 할당량 카운터가 현실과 갈린다 ──────────────────────────────
--
-- 이중 감산의 흔적이다. **0 에서 눌린 경우는 여기서도 못 심는다** — 그것이
-- verify-cancel.sql 이 「0에 눌린 것은 안 보인다」라고 적은 한계이고, 이
-- 자체 검사도 그 한계를 넘지 못한다.
UPDATE user_session_quota SET held_count = held_count + 7
WHERE session_id = :session_id AND user_id = (SELECT usr FROM sc_fix);

-- ── 검사가 잡는가 ────────────────────────────────────────────────────────

SELECT 'C-1 이 구간 위반을 잡는가' AS 자체검사,
       CASE WHEN count(*) > 0 THEN '잡았다' ELSE '❌ 못 잡았다' END AS 결과,
       count(*) AS 건수
FROM (
  SELECT seat_inventory_id, max(running) AS peak
  FROM (
    SELECT seat_inventory_id,
           sum(delta) OVER (PARTITION BY seat_inventory_id ORDER BY ts, delta
                            ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS running
    FROM (
      SELECT rs.seat_inventory_id, r.confirmed_at AS ts, 1 AS delta
        FROM reservation r JOIN reservation_seat rs ON rs.reservation_id = r.id
       WHERE r.session_id = :session_id AND r.confirmed_at IS NOT NULL
      UNION ALL
      SELECT rs.seat_inventory_id, coalesce(r.cancelled_at, 'infinity'::timestamptz), -1
        FROM reservation r JOIN reservation_seat rs ON rs.reservation_id = r.id
       WHERE r.session_id = :session_id AND r.confirmed_at IS NOT NULL
    ) e
  ) w
  GROUP BY seat_inventory_id
) p
WHERE p.peak > 1;

SELECT 'C-2 가 풀린 좌석을 잡는가' AS 자체검사,
       CASE WHEN count(*) > 0 THEN '잡았다' ELSE '❌ 못 잡았다' END AS 결과,
       count(*) AS 건수
FROM reservation r
JOIN reservation_seat rs ON rs.reservation_id = r.id
JOIN seat_inventory si ON si.id = rs.seat_inventory_id
WHERE r.session_id = :session_id AND r.status = 'CONFIRMED' AND si.status <> 'SOLD';

SELECT 'C-3 이 카운터 드리프트를 잡는가' AS 자체검사,
       CASE WHEN count(*) > 0 THEN '잡았다' ELSE '❌ 못 잡았다' END AS 결과,
       count(*) AS 건수
FROM (
  SELECT q.user_id
  FROM user_session_quota q
  LEFT JOIN seat_hold sh
         ON sh.session_id = q.session_id AND sh.user_id = q.user_id
        AND sh.status IN ('HELD', 'CONFIRMED')
  WHERE q.session_id = :session_id
  GROUP BY q.user_id, q.held_count
  HAVING q.held_count <> count(sh.id)
) t;

-- **대조 — V-1 은 같은 위반을 못 본다.** 위 위반 1이 둘 다 CANCELLED 라서다.
-- 이 줄이 0 이고 C-1 이 1 이어야 «구간으로 보는 것»의 값이 증명된다.
SELECT 'V-1 은 그 구간 위반을 보는가' AS 자체검사,
       CASE WHEN count(*) > 0 THEN '본다' ELSE '못 본다 — C-1이 필요한 이유' END AS 결과,
       count(*) AS 건수
FROM (
  SELECT rs.seat_inventory_id
  FROM reservation_seat rs JOIN reservation r ON r.id = rs.reservation_id
  WHERE r.status = 'CONFIRMED' AND r.session_id = :session_id
  GROUP BY rs.seat_inventory_id
  HAVING count(*) > 1
) t;

ROLLBACK;
