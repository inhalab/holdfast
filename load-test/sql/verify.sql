-- verify.sql — 초과 예약 검증. **7.6 기록 양식의 "초과 예약" 열은 이 쿼리가 채운다.**
--
-- 7.1이 지표별 출처를 못박아 두었다: 초과 예약 건수의 출처는 k6가 아니라
-- "DB 검증 쿼리"다. k6는 자기가 받은 응답만 알기 때문에, 서버가 두 요청 모두에
-- 201을 돌려준 경우(= 초과 예약이 실제로 발생한 경우)를 k6는 성공 2건으로 셀 뿐
-- 그것이 같은 좌석이었는지 모른다. **초과 예약은 응답이 아니라 DB 상태로만 드러난다.**
--
-- 사용: psql -v session_id=1 -f verify.sql
--
-- 검수 기준은 V-1이 0이다(REQ-01, 국립 SFR-001). none 베이스라인에서는
-- 0이 아니어야 정상이다 — 그 숫자가 2개월차 산출물인 실패 데이터다.

\set ON_ERROR_STOP on

-- V-1 초과 확정: 같은 재고 행이 두 건 이상의 유효한 예약에 팔린 경우.
-- 이것이 REQ-01의 "정원·좌석 초과 확정"이며 검수 기준 0건의 대상이다.
SELECT 'V-1 초과 확정(좌석 수)' AS 검증,
       COUNT(*) AS 건수,
       '0이어야 한다 (none 제외)' AS 기준
FROM (
  SELECT rs.seat_inventory_id
  FROM reservation_seat rs
  JOIN reservation r ON r.id = rs.reservation_id
  WHERE r.status = 'CONFIRMED'
  GROUP BY rs.seat_inventory_id
  HAVING COUNT(*) > 1
) t;

-- V-2 초과 홀드: 같은 (회차, 좌석)에 활성 홀드가 둘 이상.
-- U-2가 걸린 전략에서는 제약이 막으므로 0이고, none에서는 여기서 실패가 드러난다.
SELECT 'V-2 초과 홀드(좌석 수)' AS 검증,
       COUNT(*) AS 건수,
       '0이어야 한다 (none 제외)' AS 기준
FROM (
  SELECT session_id, seat_id
  FROM seat_hold
  WHERE status = 'HELD'
  GROUP BY session_id, seat_id
  HAVING COUNT(*) > 1
) t;

-- V-3 1인 최대 매수 초과(CS-6 / REQ-11). 좌석 단위 락으로는 막히지 않는 축이라
-- 별도로 센다. 검수 기준은 상한 초과 승인 0건이다(docs/requirements.md REQ-11).
SELECT 'V-3 매수 상한 초과(사용자 수)' AS 검증,
       COUNT(*) AS 건수,
       '0이어야 한다' AS 기준
FROM (
  SELECT r.user_id
  FROM reservation r
  JOIN reservation_seat rs ON rs.reservation_id = r.id
  JOIN event_session es ON es.id = r.session_id
  WHERE r.session_id = :session_id
    AND r.status IN ('HELD', 'CONFIRMED')
  GROUP BY r.user_id, es.max_per_user
  HAVING COUNT(rs.id) > MIN(es.max_per_user)
) t;

-- V-4 재고 상태와 예약의 불일치: SOLD인데 확정된 예약좌석이 없거나 둘 이상.
-- seat_hold가 정본이고 seat_inventory.status는 파생이므로(state-transitions.md 0절),
-- 둘이 어긋났다면 같은 트랜잭션에서 갱신한다는 전제가 깨진 것이다.
SELECT 'V-4 재고-예약 불일치(좌석 수)' AS 검증,
       COUNT(*) AS 건수,
       '0이어야 한다' AS 기준
FROM (
  SELECT si.id
  FROM seat_inventory si
  LEFT JOIN reservation_seat rs ON rs.seat_inventory_id = si.id
  LEFT JOIN reservation r ON r.id = rs.reservation_id AND r.status = 'CONFIRMED'
  WHERE si.session_id = :session_id AND si.status = 'SOLD'
  GROUP BY si.id
  HAVING COUNT(r.id) <> 1
) t;

-- V-5 참고용 분포. 검수 기준이 아니라 해석용이다.
SELECT 'V-5 재고 상태 분포' AS 검증,
       status AS 상태,
       COUNT(*) AS 좌석수
FROM seat_inventory
WHERE session_id = :session_id
GROUP BY status
ORDER BY status;
