#!/usr/bin/env bash
# run-sustained.sh — 지속 경합 시나리오 (설계서 7.2.2).
#
# 경합도 3단계(scripts/run.sh)와 **별도로 돌리고 별도 표에 기록한다.** 이쪽
# p95에는 락 대기가 들어 있고 저쪽에는 들어 있지 않아, 같은 열에 놓으면 두 값이
# 같은 것을 재는 것처럼 보인다.
#
# 사용:
#   load-test/scripts/run-sustained.sh <strategy>
#   SUSTAINED_SEATS=5 load-test/scripts/run-sustained.sh pessimistic   # 좌석 수 보정
#   DURATION_SEC=30 REPEATS=1 ... pessimistic                          # 파일럿
#
# ## 재초기화(7.4.1)를 하지 않는다
#
# 그 단계는 "좌석은 유한 자원이라 워밍업이 재고를 전부 소모한다"를 풀려고
# 넣은 것이다. 이 시나리오는 홀드한 좌석을 곧바로 반납해 재고가 순환하므로
# 그 문제가 없고, 측정 구간 한복판에 쓰기 스톨을 넣을 이유도 없다.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
export MSYS_NO_PATHCONV=1

# 측정 세션 태그. run.sh와 같은 이유로 결과 파일명에 들어간다 —
# 없으면 재실행이 이전 결과를 같은 이름으로 덮는다
# (docs/results/discarded-measurements.md 4번).
MEASURE_SESSION="${MEASURE_SESSION:-$(date +%Y%m%d-%H%M)}"
export MEASURE_SESSION


STRATEGY="${1:?전략을 지정한다: none|pessimistic|optimistic|unique|redis}"
SCENARIO=sustained
REPEATS="${REPEATS:-3}"
WARMUP_SEC="${WARMUP_SEC:-30}"
DURATION_SEC="${DURATION_SEC:-30}"
SEATS="${SUSTAINED_SEATS:-1}"
SLEEP_MS="${SUSTAINED_SLEEP_MS:-50}"   # 도착률 손잡이 (7.2.2)
SESSION_ID="${SESSION_ID:-1}"
DB_SERVICE="${DB_SERVICE:-db}"
DB_USER="${DB_USER:-holdfast}"
DB_NAME="${DB_NAME:-holdfast}"
FINAL_DURATION_SEC=120

psql_q() {
  docker compose exec -T "$DB_SERVICE" psql -U "$DB_USER" -d "$DB_NAME" -tAc "$1" 2>/dev/null | tr -d '[:space:]'
}
acquire_metric() {  # $1 = app
  docker compose exec -T nginx wget -qO- \
    "http://$1:8080/actuator/metrics/hikaricp.connections.$2" 2>/dev/null
}

if [ "$DURATION_SEC" -lt "$FINAL_DURATION_SEC" ]; then
  echo "[sustained] 개발/파일럿 실행이다(본 측정 ${DURATION_SEC}초 < ${FINAL_DURATION_SEC}초)."
  echo "[sustained] 이 실행의 숫자는 기록 양식에 싣지 않는다. 좌석 수 보정에만 쓴다."
  echo
fi

FAILED=0
for run in $(seq 1 "$REPEATS"); do
  echo
  echo "════ 지속 경합 — $STRATEGY / 좌석 ${SEATS}석 / sleep ${SLEEP_MS}ms / ${run}회차 (총 ${REPEATS}회) ════"

  SUSTAINED_SEATS="$SEATS" "$ROOT/load-test/scripts/seed.sh" "$SCENARIO" "$STRATEGY"

  # 커넥션 점유·대기는 7.5 검증의 핵심이라 회차마다 델타로 잡는다(7.2.2).
  for app in app1 app2; do
    echo "[pool:before] $app usage=$(acquire_metric "$app" usage)"
    echo "[pool:before] $app acquire=$(acquire_metric "$app" acquire)"
  done

  docker compose -f docker-compose.yml -f docker-compose.k6.yml \
    --profile load run --rm -e "MEASURE_SESSION=$MEASURE_SESSION" \
    -e "SCENARIO=$SCENARIO" -e "STRATEGY=$STRATEGY" -e "RUN=$run" \
    -e "WARMUP_SEC=$WARMUP_SEC" -e "DURATION_SEC=$DURATION_SEC" \
    -e "SUSTAINED_SEATS=$SEATS" -e "SUSTAINED_SLEEP_MS=$SLEEP_MS" \
    k6 run /scenarios/sustained.js \
    || echo "[sustained] k6가 임계값 위반으로 실패했다 — 결과는 남아 있다"

  for app in app1 app2; do
    echo "[pool:after] $app usage=$(acquire_metric "$app" usage)"
    echo "[pool:after] $app acquire=$(acquire_metric "$app" acquire)"
  done

  # --- 회수 누수 자동 판정 (7.2.2 — 실행 폐기 사유) -------------------------
  LEAKED_HOLDS="$(psql_q "SELECT count(*) FROM seat_hold WHERE session_id = $SESSION_ID AND status = 'HELD'")"
  UNRETURNED="$(psql_q "SELECT count(*) FROM seat_inventory WHERE session_id = $SESSION_ID AND status <> 'AVAILABLE'")"

  echo
  docker compose exec -T "$DB_SERVICE" psql -U "$DB_USER" -d "$DB_NAME" \
    -v ON_ERROR_STOP=1 -v "session_id=$SESSION_ID" -f - < "$ROOT/load-test/sql/verify-sustained.sql"

  if [ "${LEAKED_HOLDS:-1}" != "0" ] || [ "${UNRETURNED:-1}" != "0" ]; then
    FAILED=1
    cat <<MSG

❌ ${run}회차 폐기 — 회수 누수 (잔여 활성 홀드 ${LEAKED_HOLDS}, 미반환 좌석 ${UNRETURNED})

  홀드해 놓고 해제하지 못한 좌석은 순환에서 빠진다. 청소 스케줄러가 없으므로
  none에서는 영구히 빠지고, 나머지 전략도 TTL 만료 전까지 죽어 있다. 좌석이
  ${SEATS}석뿐이라 재고가 그만큼 줄어든 채로 측정된 것이다(7.2.2).

  확인할 것: 해제 요청이 4xx/5xx를 받았는지(k6 요약의 회수 성공률),
  홀드 응답을 받지 못해 holdId를 모르는 경우가 있었는지.
MSG
  else
    echo "✅ ${run}회차 회수 누수 없음 — 잔여 활성 홀드 0, 미반환 좌석 0"
  fi
done

echo
echo "[sustained] 완료. 확인할 것:"
cat <<'MSG'

  확정 설정은 좌석 1석 / VU 500 / sleep 50ms 다 (7.2.2, 파일럿 5회).
  **락 포기율은 판정 지표가 아니다.** 0이 정상이며, 0이기 때문에 7.6.1의
  함정(포기가 많을수록 p95가 좋아 보인다)을 피해 두 전략의 홀드 p95를
  직접 비교할 수 있다.

  7.5 판정은 커넥션 풀 지표로 한다.
    1차: 점유 시간(hikaricp.connections.usage) — 전략이 직접 결정하는 값
    2차: 획득 대기(.acquire)          — 점유의 결과이며 도착률에도 좌우된다
    redis가 홀드 p95도 낮고 두 값도 낮아야 검증. p95만 앞서면 기각.

  요약에서 볼 것: "홀드 p95"(헤드라인), "회수 성공률", 위 pool 지표.
MSG
exit "$FAILED"
