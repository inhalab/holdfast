-- verify-quota.sql — 할당량 경합 시나리오(7.2.3) 전용 검증.
--
-- **이 시나리오의 판정은 여기가 전부다.** k6 요약의 p95·TPS는 보지 않는다 —
-- 요청 종류가 둘이면 그 값이 "홀드+확정의 성능"이 아니고(api-spec 8.1), M3의
-- 60회가 비교 대상을 잃는다.
--
-- **verify.sql 을 대체하지 않는다.** 저쪽 V-1~V-5는 경합도 3단계의 정본이고,
-- 여기는 CS-6 구역만 본다 — verify-sustained.sql 이 V-6을 따로 둔 것과 같다.
--
-- 사용: psql ... -v session_id=1 -f verify-quota.sql

\set ON_ERROR_STOP on
\pset border 2

-- Q-1 **이 파일의 본체.** 같은 사용자가 상한을 넘겨 «동시에» 점유한 적이 있는가.
--
-- ## 끝 상태를 세면 안 된다
--
-- 이 시나리오는 홀드한 좌석을 곧바로 해제하므로 **실행이 끝나면 활성 홀드가
-- 0이다.** 끝 상태의 활성 홀드를 세면 CS-6이 새도 0으로 나온다 — 위반이
-- 순간적이기 때문이다. **구간이 겹쳤는지를 봐야 한다.**
--
-- V-6(verify-sustained.sql)이 같은 문제를 같은 방식으로 풀었다. 다른 점은
-- 거기는 «겹쳤는가»(2 이상)를 보고 여기는 «몇 개까지 겹쳤는가»를 본다는 것이다.
--
-- ## 왜 reservation 인가
--
-- seat_hold 에는 시각 컬럼이 held_until 하나뿐이라 «언제부터 언제까지»가 없다.
-- 예약 행이 홀드 시점에 만들어지고 해제 시 cancelled_at 이 찍히므로(erd 4절),
-- 점유 구간은 [created_at, cancelled_at) 이다. 아직 살아 있으면 infinity.
--
-- ## 쓸어 담기 — 쌍끼리 조인하지 않는다
--
-- 사용자 20명에 수만 건이 쌓이므로 self-join 이면 사용자당 O(n²)로 폭발한다.
-- 시작을 +1, 끝을 -1로 놓고 시각 순으로 누적하면 O(n log n)에 «동시 점유
-- 최댓값»이 나온다. 같은 시각이면 **끝(-1)을 먼저** 처리한다 — 한 구간이
-- 끝나는 순간 다른 구간이 시작하는 것을 겹침으로 세지 않기 위해서다.
SELECT 'Q-1 동시 점유 상한 초과(사용자 수)' AS 검증,
       count(*) AS 건수,
       '0이어야 한다' AS 기준
FROM (
  SELECT user_id, max(running) AS peak
  FROM (
    SELECT user_id,
           sum(delta) OVER (PARTITION BY user_id ORDER BY ts, delta
                            ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS running
    FROM (
      SELECT r.user_id, r.created_at AS ts, 1 AS delta
        FROM reservation r JOIN reservation_seat rs ON rs.reservation_id = r.id
       WHERE r.session_id = :session_id
      UNION ALL
      SELECT r.user_id, coalesce(r.cancelled_at, 'infinity'::timestamptz) AS ts, -1 AS delta
        FROM reservation r JOIN reservation_seat rs ON rs.reservation_id = r.id
       WHERE r.session_id = :session_id
    ) e
  ) w
  GROUP BY user_id
) p
JOIN event_session es ON es.id = :session_id
WHERE p.peak > es.max_per_user;

-- Q-2 카운터가 현실과 갈리는가.
--
-- held_count 는 증가(홀드)와 감소(해제)가 동시에 다투는 값이고, 감소 쪽은
-- Math.max(0, heldCount - released) 로 깎는다 — **어긋나도 음수로 드러나지 않고
-- 조용히 0에 눌린다.** 상한 검사가 이 값 위에서 도므로, 이것이 틀리면 Q-1이
-- 0이어도 의미가 없다.
--
-- **만료된 홀드는 활성이 아니다**(erd 4.1) — held_until 이 지난 HELD 행은 세지
-- 않는다. none 은 회수하지 않으므로 그 행이 남아 있을 수 있다.
SELECT 'Q-2 할당량 카운터 불일치(사용자 수)' AS 검증,
       count(*) AS 건수,
       '0이어야 한다' AS 기준
FROM (
  SELECT q.user_id
  FROM user_session_quota q
  LEFT JOIN seat_hold sh
         ON sh.session_id = q.session_id
        AND sh.user_id = q.user_id
        AND sh.status = 'HELD'
        AND (sh.held_until IS NULL OR sh.held_until > now())
  WHERE q.session_id = :session_id
  GROUP BY q.user_id, q.held_count
  HAVING q.held_count <> count(sh.id)
) t;

-- Q-3 예약 기준 상한 초과. verify.sql 의 V-3과 같은 질의다.
--
-- **확정을 하지 않는 시나리오라 구조적으로 0이고, 그래서 이것만으로는 판정이
-- 안 된다.** 그 사실을 드러내려고 함께 싣는다 — 8절이 "CS-6은 V-3으로 확인한다"
-- 고 적어 두었는데, **V-3은 홀드 단계를 보지 않는다.**
SELECT 'Q-3 예약 기준 상한 초과(사용자 수)' AS 검증,
       count(*) AS 건수,
       '0이어야 한다 (이 시나리오에서는 구조적으로 0)' AS 기준
FROM (
  SELECT r.user_id
  FROM reservation r
  JOIN reservation_seat rs ON rs.reservation_id = r.id
  JOIN event_session es ON es.id = r.session_id
  WHERE r.session_id = :session_id
    AND r.status IN ('HELD', 'CONFIRMED')
  GROUP BY r.user_id, es.max_per_user
  HAVING count(rs.id) > min(es.max_per_user)
) t;

-- 참고 — 경합 구역을 실제로 밟았는지.
--
-- **상한에 닿지 못했다면 판정이 아니라 시나리오 실패다**(7.2.3). 동시 점유
-- 최댓값이 상한보다 작다면 같은 사용자의 요청이 겹치지 않았다는 뜻이고,
-- 그러면 Q-1의 0은 "안 깨졌다"가 아니라 "밟지 않았다"이다.
SELECT '참고: 동시 점유 최댓값' AS 검증,
       coalesce(max(peak), 0) AS 건수,
       '상한(4)에 닿아야 한다' AS 기준
FROM (
  SELECT user_id, max(running) AS peak
  FROM (
    SELECT user_id,
           sum(delta) OVER (PARTITION BY user_id ORDER BY ts, delta
                            ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS running
    FROM (
      SELECT r.user_id, r.created_at AS ts, 1 AS delta
        FROM reservation r JOIN reservation_seat rs ON rs.reservation_id = r.id
       WHERE r.session_id = :session_id
      UNION ALL
      SELECT r.user_id, coalesce(r.cancelled_at, 'infinity'::timestamptz) AS ts, -1 AS delta
        FROM reservation r JOIN reservation_seat rs ON rs.reservation_id = r.id
       WHERE r.session_id = :session_id
    ) e
  ) w
  GROUP BY user_id
) p;
