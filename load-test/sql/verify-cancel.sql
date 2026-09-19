-- verify-cancel.sql — 취소·재선점 경합 시나리오(7.2.4) 전용 검증. CS-4.
--
-- **이 시나리오의 판정은 여기가 전부다.** k6 요약의 p95·TPS는 보지 않는다 —
-- 요청 종류가 넷이면(홀드·확정·취소×2) 그 값이 "홀드+확정의 성능"이 아니고
-- (api-spec 8.1), M3의 60회가 비교 대상을 잃는다.
--
-- **verify.sql 을 대체하지 않는다.** 저쪽 V-1~V-5는 경합도 3단계의 정본이고,
-- 여기는 CS-4 구역만 본다 — verify-sustained.sql 이 V-6을, verify-quota.sql 이
-- Q-1~Q-3을 따로 둔 것과 같다.
--
-- 사용: psql ... -v session_id=1 -f verify-cancel.sql

\set ON_ERROR_STOP on
\pset border 2

-- C-1 **이 파일의 본체.** 같은 좌석을 두 예약이 «동시에» 확정 보유한 적이 있는가.
--
-- ## V-1 로는 안 보인다
--
-- V-1은 status = 'CONFIRMED' 인 행만 센다. 이 시나리오는 확정한 좌석을 곧바로
-- 취소하므로 **둘 다 CANCELLED 가 되면 V-1이 0이다** — 두 사람이 같은 좌석을
-- 동시에 들고 있던 구간이 있었어도 끝 상태에는 흔적이 없다.
--
-- **끝 상태가 아니라 구간을 본다.** verify-sustained.sql 의 V-6과
-- verify-quota.sql 의 Q-1이 같은 문제를 같은 방식으로 푼 선례다. 다른 점은
-- 저쪽이 «사용자별 점유 구간»을 보고 여기는 «좌석별 확정 구간»을 본다는 것이다.
--
-- ## 구간의 정의
--
-- 확정 시각이 confirmed_at, 반환 시각이 cancelled_at 이다(erd 4절). 아직
-- 살아 있으면 infinity. **confirmed_at 이 NULL 인 행은 제외한다** — 홀드만 하고
-- 해제한 예약이며 좌석이 SOLD 가 된 적이 없어 CS-4 구역에 들어오지 않는다.
--
-- ## 쓸어 담기 — 쌍끼리 조인하지 않는다
--
-- 좌석 하나에 수천 건이 쌓이므로 self-join 이면 좌석당 O(n²)로 폭발한다.
-- 시작을 +1, 끝을 -1로 놓고 시각 순으로 누적하면 O(n log n)에 «동시 보유
-- 최댓값»이 나온다. 같은 시각이면 **끝(-1)을 먼저** 처리한다 — 한 사람이
-- 반환하는 순간 다음 사람이 잡는 것을 겹침으로 세지 않기 위해서다.
SELECT 'C-1 좌석 동시 확정 보유(좌석 수)' AS 검증,
       count(*) AS 건수,
       '0이어야 한다' AS 기준
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
      SELECT rs.seat_inventory_id, coalesce(r.cancelled_at, 'infinity'::timestamptz) AS ts, -1 AS delta
        FROM reservation r JOIN reservation_seat rs ON rs.reservation_id = r.id
       WHERE r.session_id = :session_id AND r.confirmed_at IS NOT NULL
    ) e
  ) w
  GROUP BY seat_inventory_id
) p
WHERE p.peak > 1;

-- C-2 확정된 예약의 좌석이 재고에서 비어 있는가 — «남의 좌석을 풀었다».
--
-- ReservationService#cancel 은 reservation 을 **읽은 뒤** 할당량 행을 FOR UPDATE
-- 로 잡는다. READ COMMITTED 에서 뒤엣 트랜잭션은 락을 기다리는 동안 갱신되지
-- 않은 스냅샷을 쥐므로, 이미 취소된 예약을 한 번 더 취소할 수 있다.
-- releaseSold 는 status = 'SOLD' 조건부라 보통은 무해하지만, **그 사이에 좌석이
-- 다시 팔렸다면 조건이 참이 되어 남의 좌석을 푼다.**
--
-- **V-4 가 이 방향을 보지 않는다.** 저쪽은 si.status = 'SOLD' 인 행에서
-- 출발하므로 «SOLD 인데 예약이 없다»만 잡고, 여기서 보는 «확정 예약이 있는데
-- SOLD 가 아니다»는 출발점 밖이다.
SELECT 'C-2 확정인데 재고가 SOLD가 아님(좌석 수)' AS 검증,
       count(*) AS 건수,
       '0이어야 한다' AS 기준
FROM reservation r
JOIN reservation_seat rs ON rs.reservation_id = r.id
JOIN seat_inventory si ON si.id = rs.seat_inventory_id
WHERE r.session_id = :session_id
  AND r.status = 'CONFIRMED'
  AND si.status <> 'SOLD';

-- C-3 할당량 카운터가 현실과 갈리는가 — «이중 감산».
--
-- held_count 는 홀드에서 +requested, 해제와 취소에서 -n 이다. **확정은 이 값을
-- 바꾸지 않는다** — 그래서 현실은 "RELEASED 가 아닌 홀드 행 수"다
-- (seat_hold 는 HELD → CONFIRMED → RELEASED 로 가고, 취소도 RELEASED 로 간다).
--
-- 취소가 겹쳐 두 번 감산되면 이 값이 영구히 어긋난다. **다만 과소 계수된다** —
-- 감산 쪽이 Math.max(0, ...) 라 0 에서 눌리면 흔적이 지워진다(7.2.3이 같은 성질을
-- 적었다). **0 이 나와도 "이중 감산이 없었다"가 아니라 "남은 흔적이 없다"이다.**
SELECT 'C-3 할당량 카운터 불일치(사용자 수)' AS 검증,
       count(*) AS 건수,
       '0이어야 한다 (0에 눌린 것은 안 보인다)' AS 기준
FROM (
  SELECT q.user_id
  FROM user_session_quota q
  LEFT JOIN seat_hold sh
         ON sh.session_id = q.session_id
        AND sh.user_id = q.user_id
        AND sh.status IN ('HELD', 'CONFIRMED')
  WHERE q.session_id = :session_id
  GROUP BY q.user_id, q.held_count
  HAVING q.held_count <> count(sh.id)
) t;

-- C-4 기존 기준 둘. verify.sql 의 V-1·V-4 와 같은 질의다.
--
-- **이 둘만으로는 판정이 안 된다.** 위 C-1 머리말이 이유다 — 확정한 좌석을
-- 곧바로 취소하므로 끝 상태에 위반이 남지 않을 수 있다. 그 사실을 드러내려고
-- 함께 싣는다.
SELECT 'C-4a V-1 초과 확정(좌석 수)' AS 검증,
       count(*) AS 건수,
       '0이어야 한다 (끝 상태 기준이라 구간 위반은 못 본다)' AS 기준
FROM (
  SELECT rs.seat_inventory_id
  FROM reservation_seat rs
  JOIN reservation r ON r.id = rs.reservation_id
  WHERE r.status = 'CONFIRMED' AND r.session_id = :session_id
  GROUP BY rs.seat_inventory_id
  HAVING count(*) > 1
) t;

SELECT 'C-4b V-4 재고-예약 불일치(좌석 수)' AS 검증,
       count(*) AS 건수,
       '0이어야 한다' AS 기준
FROM (
  SELECT si.id
  FROM seat_inventory si
  LEFT JOIN reservation_seat rs ON rs.seat_inventory_id = si.id
  LEFT JOIN reservation r ON r.id = rs.reservation_id AND r.status = 'CONFIRMED'
  WHERE si.session_id = :session_id AND si.status = 'SOLD'
  GROUP BY si.id
  HAVING count(r.id) <> 1
) t;

-- 참고 — 재선점이 실제로 일어났는지.
--
-- **0 이면 판정이 아니라 시나리오 실패다**(7.2.4). 좌석이 팔린 뒤 돌아와 다시
-- 팔리는 일이 없었다면 CS-4 구역을 밟지 않은 것이고, 그때 C-1 의 0 은
-- "안 깨졌다"가 아니라 "밟지 않았다"이다. 7.2.3이 QUOTA_EXCEEDED 거절 수로
-- 같은 일을 했다.
--
-- «확정된 예약 수 − 확정된 적이 있는 좌석 수» 가 재선점 횟수다.
SELECT '참고: 재선점 횟수' AS 검증,
       count(*) - count(DISTINCT rs.seat_inventory_id) AS 건수,
       '0보다 커야 한다' AS 기준
FROM reservation r
JOIN reservation_seat rs ON rs.reservation_id = r.id
WHERE r.session_id = :session_id AND r.confirmed_at IS NOT NULL;

-- 참고 — 좌석별 동시 확정 보유 최댓값. C-1 과 같은 계산이며 값 자체를 보인다.
-- 1 이면 정상이고 2 이상이면 C-1 이 잡은 것이다.
SELECT '참고: 좌석 동시 확정 보유 최댓값' AS 검증,
       coalesce(max(peak), 0) AS 건수,
       '1이어야 한다' AS 기준
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
      SELECT rs.seat_inventory_id, coalesce(r.cancelled_at, 'infinity'::timestamptz) AS ts, -1 AS delta
        FROM reservation r JOIN reservation_seat rs ON rs.reservation_id = r.id
       WHERE r.session_id = :session_id AND r.confirmed_at IS NOT NULL
    ) e
  ) w
  GROUP BY seat_inventory_id
) p;
