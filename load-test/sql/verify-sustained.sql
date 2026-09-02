-- verify-sustained.sql — 지속 경합 시나리오 검증 (설계서 7.2.2).
--
-- 이 시나리오는 홀드 → 해제를 반복하므로 예약이 전부 CANCELLED가 된다.
-- 그래서 `status='CONFIRMED'`만 세는 V-1이 **구조적으로 0**이 되어 쓸 수 없다.
-- 대신 **점유 구간이 겹쳤는지**를 본다.
--
-- 사용: psql -v session_id=1 -f verify-sustained.sql
--
-- **검수 기준의 정본은 여전히 경합도 3단계다.** 여기의 V-6은 회수를 섞은
-- 상태에서도 초과 점유가 없었는지 확인하는 보조 검증이다.

\set ON_ERROR_STOP on

-- V-6 점유 구간 중첩: 같은 재고 행을 두 예약이 동시에 점유한 적이 있는가.
--
-- 예약 하나의 점유 구간은 [created_at, cancelled_at) 이다. 예약 행은 홀드
-- 시점에 만들어지고(erd 4절) 해제 시 CANCELLED + cancelled_at 이 찍힌다.
-- 아직 살아 있는 홀드는 끝이 없으므로 infinity로 둔다.
--
-- **쌍끼리 조인하지 않는다.** 이 시나리오는 좌석 몇 개에 수만 건의 예약을
-- 쌓으므로 self-join이 좌석당 O(n²)로 폭발한다(3석 × 1만 건이면 1.5억 쌍).
-- 좌석별로 시작 시각 순으로 훑으면서 "앞선 구간들의 최대 종료 시각"과만
-- 비교하면 O(n log n)으로 같은 답을 얻는다.
SELECT 'V-6 점유 구간 중첩(건수)' AS 검증,
       count(*) AS 건수,
       '0이어야 한다 (none 제외)' AS 기준
FROM (
  SELECT r.created_at,
         max(coalesce(r.cancelled_at, 'infinity'::timestamptz))
           OVER (PARTITION BY rs.seat_inventory_id
                 ORDER BY r.created_at
                 ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS prev_end
  FROM reservation_seat rs
  JOIN reservation r ON r.id = rs.reservation_id
  JOIN seat_inventory si ON si.id = rs.seat_inventory_id
  WHERE si.session_id = :session_id
) t
WHERE t.prev_end IS NOT NULL AND t.created_at < t.prev_end;

-- L-1 회수 누수 — 실행이 끝났는데 활성 홀드가 남아 있는가.
--
-- **0이 아니면 그 실행을 폐기한다(7.2.2).** 홀드해 놓고 해제하지 못한 좌석은
-- 순환에서 빠진다. 청소 스케줄러가 없으므로 none에서는 영구히 빠지고, 나머지
-- 네 전략은 TTL 만료 뒤 다음 요청이 정리할 때까지 죽어 있다. 좌석이 몇 개뿐인
-- 시나리오라 한 건만 새도 재고의 상당 부분이 사라진다.
SELECT 'L-1 잔여 활성 홀드' AS 검증,
       count(*) AS 건수,
       '0이어야 한다 — 아니면 실행 폐기' AS 기준
FROM seat_hold
WHERE session_id = :session_id AND status = 'HELD';

-- L-2 재고가 AVAILABLE로 돌아오지 않은 좌석. L-1과 짝이다.
-- 홀드 응답을 못 받아 k6가 누수를 인지하지 못한 경우까지 여기서 잡힌다.
SELECT 'L-2 미반환 좌석' AS 검증,
       count(*) AS 건수,
       '0이어야 한다 — 아니면 실행 폐기' AS 기준
FROM seat_inventory
WHERE session_id = :session_id AND status <> 'AVAILABLE';

-- 참고 — 좌석이 실제로 얼마나 돌았는지. 회전이 없으면 지속 경합이 아니다.
SELECT '좌석 회전 수(총 홀드 사이클)' AS 검증,
       count(*) AS 건수,
       '클수록 경합이 지속됐다는 뜻' AS 기준
FROM seat_hold
WHERE session_id = :session_id;

SELECT '좌석당 평균 회전' AS 검증,
       round(count(*)::numeric / greatest(count(DISTINCT seat_id), 1), 1) AS 건수,
       '참고' AS 기준
FROM seat_hold
WHERE session_id = :session_id;

-- 참고 — 재고 상태 분포. 정상 종료라면 전부 AVAILABLE이어야 한다.
SELECT '재고 상태 분포' AS 검증,
       status AS 상태,
       count(*) AS 좌석수
FROM seat_inventory
WHERE session_id = :session_id
GROUP BY status
ORDER BY status;
