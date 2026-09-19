#!/usr/bin/env bash
# run-cancel.sh — 취소·재선점 경합 시나리오 (설계서 7.2.4). **정합성 축 전용.**
#
# 경합도 3단계(run.sh)·지속 경합(run-sustained.sh)·할당량 경합(run-quota.sh)과
# **별도로 돌리고 기록 양식에 넣지 않는다.** 이쪽은 p95·TPS를 재지 않는다 —
# 요청 종류가 넷이면(홀드·확정·취소×2) 그 값이 "홀드+확정의 성능"이 아니고
# (api-spec 8.1), M3의 60회가 비교 대상을 잃는다.
#
# 사용:
#   load-test/scripts/run-cancel.sh [strategy]       # 기본 pessimistic, 3회
#   DURATION_SEC=30 REPEATS=1 ... pessimistic        # 파일럿
#
# ## 전략을 하나만 돈다
#
# **재는 임계 구역이 전략 밖이다.** ReservationService#cancel 에는 전략 분기가
# 없고 다섯 전략이 같은 코드를 탄다 — 7.2.2가 홀드 해제를 회수 수단으로 채택한
# 근거("회수 경로가 전략 밖 공통 코드다")와 같다.
#
# **재선점의 홀드 경로는 전략 안이지만 그쪽은 판정 대상이 아니다.** 그것은
# CS-1이고 60회가 이미 쟀다. 전략별로 돌리면 변수만 늘고 판정은 같다.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
export MSYS_NO_PATHCONV=1

MEASURE_SESSION="${MEASURE_SESSION:-$(date +%Y%m%d-%H%M)}"
export MEASURE_SESSION

STRATEGY="${1:-pessimistic}"
SCENARIO=cancel
REPEATS="${REPEATS:-3}"
WARMUP_SEC="${WARMUP_SEC:-30}"
DURATION_SEC="${DURATION_SEC:-30}"
SESSION_ID="${SESSION_ID:-1}"
DB_SERVICE="${DB_SERVICE:-db}"
DB_USER="${DB_USER:-holdfast}"
DB_NAME="${DB_NAME:-holdfast}"
FINAL_DURATION_SEC=120

psql_q() {
  docker compose exec -T "$DB_SERVICE" psql -U "$DB_USER" -d "$DB_NAME" -tAc "$1" 2>/dev/null | tr -d '[:space:]'
}

if [ "$DURATION_SEC" -lt "$FINAL_DURATION_SEC" ]; then
  echo "[cancel] 개발/파일럿 실행이다(본 측정 ${DURATION_SEC}초 < ${FINAL_DURATION_SEC}초)."
  echo "[cancel] 최종: DURATION_SEC=${FINAL_DURATION_SEC} $0 $STRATEGY"
  echo
fi

# 좌석별 «확정 보유 구간»을 쓸어 담는다. C-1과 같은 계산이다 — 끝 상태가 아니라
# 구간을 보는 이유는 verify-cancel.sql 머리말에 있다.
PEAK_SQL="SELECT seat_inventory_id, max(running) AS peak FROM (SELECT seat_inventory_id, sum(delta) OVER (PARTITION BY seat_inventory_id ORDER BY ts, delta ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS running FROM (SELECT rs.seat_inventory_id, r.confirmed_at AS ts, 1 AS delta FROM reservation r JOIN reservation_seat rs ON rs.reservation_id = r.id WHERE r.session_id = $SESSION_ID AND r.confirmed_at IS NOT NULL UNION ALL SELECT rs.seat_inventory_id, coalesce(r.cancelled_at,'infinity'::timestamptz), -1 FROM reservation r JOIN reservation_seat rs ON rs.reservation_id = r.id WHERE r.session_id = $SESSION_ID AND r.confirmed_at IS NOT NULL) e) w GROUP BY seat_inventory_id"

FAILED=0
REPORT=""
for run in $(seq 1 "$REPEATS"); do
  echo
  echo "════ 취소·재선점(CS-4) — $STRATEGY / ${run}회차 (총 ${REPEATS}회) ════"

  "$ROOT/load-test/scripts/seed.sh" "$SCENARIO" "$STRATEGY"

  docker compose -f docker-compose.yml -f docker-compose.k6.yml \
    --profile load run --rm -e "MEASURE_SESSION=$MEASURE_SESSION" \
    -e "SCENARIO=$SCENARIO" -e "STRATEGY=$STRATEGY" -e "RUN=$run" \
    -e "WARMUP_SEC=$WARMUP_SEC" -e "DURATION_SEC=$DURATION_SEC" \
    k6 run /scenarios/cancel.js \
    || echo "[cancel] k6가 임계값 위반으로 실패했다 — 결과는 남아 있다"

  echo
  docker compose exec -T "$DB_SERVICE" psql -U "$DB_USER" -d "$DB_NAME" \
    -v ON_ERROR_STOP=1 -v "session_id=$SESSION_ID" -f - < "$ROOT/load-test/sql/verify-cancel.sql"

  # --- 자동 판정 -----------------------------------------------------------
  #
  # **경합 구역을 밟았는지를 먼저 본다.** 재선점이 0이면 좌석이 팔린 뒤 돌아와
  # 다시 팔린 일이 없었다는 뜻이고, 그때 C-1의 0은 "안 깨졌다"가 아니라
  # "밟지 않았다"이다 — 판정이 아니라 시나리오 실패다(7.2.4).
  OVER_SEAT="$(psql_q "SELECT count(*) FROM ($PEAK_SQL) p WHERE p.peak > 1")"
  MAX_HOLD="$(psql_q "SELECT coalesce(max(peak),0) FROM ($PEAK_SQL) p")"
  RECLAIM="$(psql_q "SELECT count(*) - count(DISTINCT rs.seat_inventory_id) FROM reservation r JOIN reservation_seat rs ON rs.reservation_id = r.id WHERE r.session_id = $SESSION_ID AND r.confirmed_at IS NOT NULL")"
  STRANDED="$(psql_q "SELECT count(*) FROM reservation r JOIN reservation_seat rs ON rs.reservation_id = r.id JOIN seat_inventory si ON si.id = rs.seat_inventory_id WHERE r.session_id = $SESSION_ID AND r.status = 'CONFIRMED' AND si.status <> 'SOLD'")"
  DRIFT="$(psql_q "SELECT count(*) FROM (SELECT q.user_id FROM user_session_quota q LEFT JOIN seat_hold sh ON sh.session_id = q.session_id AND sh.user_id = q.user_id AND sh.status IN ('HELD','CONFIRMED') WHERE q.session_id = $SESSION_ID GROUP BY q.user_id, q.held_count HAVING q.held_count <> count(sh.id)) t")"

  REPORT="${REPORT}  ${run}회차 — 재선점 ${RECLAIM}회 · 동시 보유 최댓값 ${MAX_HOLD}"$'\n'

  if [ "${OVER_SEAT:-1}" != "0" ] || [ "${STRANDED:-1}" != "0" ] || [ "${DRIFT:-1}" != "0" ]; then
    FAILED=1
    cat <<MSG

❌ ${run}회차 — CS-4가 샜다
   (동시 확정 보유 좌석 ${OVER_SEAT}석, 확정인데 재고가 빈 좌석 ${STRANDED}석, 카운터 불일치 ${DRIFT}명)

  **이것이 이 시나리오가 찾던 것이다.** 취소 트랜잭션 둘이 겹쳤을 때 좌석이
  두 번 반환됐다는 뜻이다. ReservationService#cancel 이 reservation 을 읽는
  시점과 할당량 행을 FOR UPDATE 로 잡는 시점 사이를 확인한다 — READ COMMITTED
  에서 그 사이 스냅샷은 갱신되지 않는다.

  **고치는 것은 이 이슈가 아니다**(#200). 별도 이슈로 연다.
MSG
  elif [ "${RECLAIM:-0}" -le 0 ]; then
    FAILED=1
    cat <<MSG

❌ ${run}회차 폐기 — 경합 구역을 밟지 못했다 (재선점 ${RECLAIM}회)

  좌석이 팔린 뒤 돌아와 다시 팔린 일이 없었다는 뜻이다. **이때 C-1의 0은
  "안 깨졌다"가 아니라 "밟지 않았다"이다** — 판정이 아니라 시나리오 실패다.

  확인할 것: 확정까지 간 반복이 있었는지(k6 요약), 좌석 수가 VU에 비해 너무
  많아 돌아온 좌석을 아무도 안 기다리지는 않았는지.
MSG
  else
    echo "✅ ${run}회차 — C-1 0, C-2 0, C-3 0 (재선점 ${RECLAIM}회, 동시 보유 최댓값 ${MAX_HOLD})"
  fi
done

echo
echo "════ 정리 ════"
printf '%s' "$REPORT"
if [ "$FAILED" = "0" ]; then
  cat <<'MSG'

✅ 전 회차 통과.

  **"못 깨진다"가 아니라 "이 조건에서 안 깨졌다"까지다**(7.2.4). 경합이 실제로
  발현되는가는 확률이고, 0은 그것을 관측하지 못했다는 뜻이다 — 7.5.1이 redis의
  제약 위반 0을 두고 쓴 규율과 같다.

  **이 축의 잡음 바닥은 「얼마나 밟았는가」의 회차 간 편차다.** 위 회차별 재선점
  횟수가 그것이고, 판정의 근거는 그중 **최솟값**이다 — 가장 적게 밟은 회차도
  임계를 넘겨야 한다. 다른 측정의 잡음(7.7.1의 세션 변동)을 빌려오지 않는다.

  **C-3의 0은 한쪽으로만 읽는다.** 감산이 Math.max(0, ...) 라 0에서 눌린 이중
  감산은 흔적이 남지 않는다(verify-cancel.sql).
MSG
else
  echo "❌ 실패한 회차가 있다. 위 메시지를 본다."
  exit 1
fi
