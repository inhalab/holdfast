#!/usr/bin/env bash
# run-quota.sh — 할당량 경합 시나리오 (설계서 7.2.3). **정합성 축 전용.**
#
# 경합도 3단계(run.sh)·지속 경합(run-sustained.sh)과 **별도로 돌리고 기록 양식에
# 넣지 않는다.** 이쪽은 p95·TPS를 재지 않는다 — 요청 종류가 둘이면 그 값이
# "홀드+확정의 성능"이 아니고(api-spec 8.1), M3의 60회가 비교 대상을 잃는다.
#
# 사용:
#   load-test/scripts/run-quota.sh [strategy]        # 기본 pessimistic, 3회
#   DURATION_SEC=30 REPEATS=1 ... pessimistic        # 파일럿
#
# ## 전략을 하나만 돈다
#
# 1.1이 **"이 구역은 락 전략 비교 대상이 아니다"**라고 못박았다. 다섯 전략이
# 전부 같은 방식으로 처리하며, 막는 것은 좌석 락이 아니라 user_session_quota
# 행 하나다. 전략별로 돌리면 변수만 늘고 판정은 같다.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
export MSYS_NO_PATHCONV=1

MEASURE_SESSION="${MEASURE_SESSION:-$(date +%Y%m%d-%H%M)}"
export MEASURE_SESSION

STRATEGY="${1:-pessimistic}"
SCENARIO=quota
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
  echo "[quota] 개발/파일럿 실행이다(본 측정 ${DURATION_SEC}초 < ${FINAL_DURATION_SEC}초)."
  echo "[quota] 최종: DURATION_SEC=${FINAL_DURATION_SEC} $0 $STRATEGY"
  echo
fi

FAILED=0
for run in $(seq 1 "$REPEATS"); do
  echo
  echo "════ 할당량 경합(CS-6) — $STRATEGY / ${run}회차 (총 ${REPEATS}회) ════"

  "$ROOT/load-test/scripts/seed.sh" "$SCENARIO" "$STRATEGY"

  docker compose -f docker-compose.yml -f docker-compose.k6.yml \
    --profile load run --rm -e "MEASURE_SESSION=$MEASURE_SESSION" \
    -e "SCENARIO=$SCENARIO" -e "STRATEGY=$STRATEGY" -e "RUN=$run" \
    -e "WARMUP_SEC=$WARMUP_SEC" -e "DURATION_SEC=$DURATION_SEC" \
    k6 run /scenarios/quota.js \
    || echo "[quota] k6가 임계값 위반으로 실패했다 — 결과는 남아 있다"

  echo
  docker compose exec -T "$DB_SERVICE" psql -U "$DB_USER" -d "$DB_NAME" \
    -v ON_ERROR_STOP=1 -v "session_id=$SESSION_ID" -f - < "$ROOT/load-test/sql/verify-quota.sql"

  # --- 자동 판정 -----------------------------------------------------------
  #
  # **경합 구역을 밟았는지를 먼저 본다.** 할당량 거절이 0이면 같은 사용자의
  # 요청이 겹치지 않았다는 뜻이고, 그때 Q-1의 0은 "안 깨졌다"가 아니라
  # "밟지 않았다"이다 — 판정이 아니라 시나리오 실패다(7.2.3).
  #
  # **끝 상태를 세지 않는다.** 홀드한 좌석을 곧바로 해제하므로 실행이 끝나면
  # 활성 홀드가 0이다 — CS-6이 새도 0으로 나온다. 점유 «구간»이 겹쳤는지를 본다.
  PEAK_SQL="SELECT user_id, max(running) AS peak FROM (SELECT user_id, sum(delta) OVER (PARTITION BY user_id ORDER BY ts, delta ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS running FROM (SELECT r.user_id, r.created_at AS ts, 1 AS delta FROM reservation r JOIN reservation_seat rs ON rs.reservation_id = r.id WHERE r.session_id = $SESSION_ID UNION ALL SELECT r.user_id, coalesce(r.cancelled_at,'infinity'::timestamptz), -1 FROM reservation r JOIN reservation_seat rs ON rs.reservation_id = r.id WHERE r.session_id = $SESSION_ID) e) w GROUP BY user_id"
  OVER_HOLD="$(psql_q "SELECT count(*) FROM ($PEAK_SQL) p JOIN event_session es ON es.id = $SESSION_ID WHERE p.peak > es.max_per_user")"
  MAX_HELD="$(psql_q "SELECT coalesce(max(peak),0) FROM ($PEAK_SQL) p")"
  LIMIT_N="$(psql_q "SELECT max_per_user FROM event_session WHERE id = $SESSION_ID")"
  DRIFT="$(psql_q "SELECT count(*) FROM (SELECT q.user_id FROM user_session_quota q LEFT JOIN seat_hold sh ON sh.session_id = q.session_id AND sh.user_id = q.user_id AND sh.status = 'HELD' AND (sh.held_until IS NULL OR sh.held_until > now()) WHERE q.session_id = $SESSION_ID GROUP BY q.user_id, q.held_count HAVING q.held_count <> count(sh.id)) t")"

  if [ "${OVER_HOLD:-1}" != "0" ] || [ "${DRIFT:-1}" != "0" ]; then
    FAILED=1
    cat <<MSG

❌ ${run}회차 — CS-6이 샜다 (상한 초과 사용자 ${OVER_HOLD}명, 카운터 불일치 ${DRIFT}명)

  **이것이 이 시나리오가 찾던 것이다.** 좌석 단위 락은 이 축을 막지 못하고
  (1.1), 막는 것은 user_session_quota 행 하나뿐이다. 그 행을 잠그는 순서나
  범위를 확인한다 — 5.1의 전역 락 순서(사용자 행 → 좌석 행)가 지켜졌는지.
MSG
  elif [ "${MAX_HELD:-0}" -lt "${LIMIT_N:-4}" ]; then
    FAILED=1
    cat <<MSG

❌ ${run}회차 폐기 — 경합 구역을 밟지 못했다 (동시 점유 최댓값 ${MAX_HELD} < 상한 ${LIMIT_N})

  같은 사용자의 요청이 겹치지 않았다는 뜻이다. **이때 Q-1의 0은 "안 깨졌다"가
  아니라 "밟지 않았다"이다** — 판정이 아니라 시나리오 실패다(7.2.3).

  확인할 것: 사용자 풀이 VU보다 충분히 작은지, 좌석이 모자라 좌석 행에서
  먼저 직렬화되지는 않았는지.
MSG
  else
    echo "✅ ${run}회차 — Q-1 0, Q-2 0 (동시 점유 최댓값 ${MAX_HELD} = 상한 ${LIMIT_N})"
  fi
done

echo
echo "════ 정리 ════"
if [ "$FAILED" = "0" ]; then
  cat <<'MSG'
✅ 전 회차 통과.

  **"못 깨진다"가 아니라 "이 조건에서 안 깨졌다"까지다**(7.2.3). 경합이 실제로
  발현되는가는 확률이고, 0은 그것을 관측하지 못했다는 뜻이다 — 7.5.1이 redis의
  제약 위반 0을 두고 쓴 규율과 같다.

  **동시 점유 최댓값이 상한에 닿았는지를 함께 본다.** 위 회차별 줄에 있다 —
  닿지 못했으면 이 스크립트가 그 회차를 폐기한다.
MSG
else
  echo "❌ 실패한 회차가 있다. 위 메시지를 본다."
  exit 1
fi
