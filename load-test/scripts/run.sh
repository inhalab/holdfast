#!/usr/bin/env bash
# run.sh — 측정 프로토콜 실행기 (설계서 7.4).
#
#   1. 시드 초기화
#   2. 워밍업 30초 — 집계에서 제외 (시나리오가 처리한다)
#   3. 본 측정
#   4. 전략당 3회 반복, 중앙값 채택 (summarize.mjs가 계산한다)
#
# 사용:
#   load-test/scripts/run.sh smoke                     # 스모크만
#   load-test/scripts/run.sh <scenario> <strategy>     # 전략 1개 × 3회
#   예) load-test/scripts/run.sh high pessimistic
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

# Git Bash가 /scenarios/... 를 Windows 경로로 바꾸는 것을 막는다.
export MSYS_NO_PATHCONV=1

# docker compose run은 -e 옵션이 서비스명(k6) 앞에 와야 한다.
#   run [옵션...] <서비스> <커맨드...>
# 첫 인자로 스크립트 경로를 받고, 나머지 인자는 -e 플래그로 그대로 넘긴다.
k6_run() {
  local script="$1"
  shift
  docker compose -f docker-compose.yml -f docker-compose.k6.yml \
    --profile load run --rm "$@" k6 run "$script"
}

if [ "${1:-}" = "smoke" ]; then
  echo "[run] 스모크 — k6 실행 환경과 집계 파이프라인 확인"
  k6_run /scenarios/smoke.js
  exit $?
fi

SCENARIO="${1:-high}"
STRATEGY="${2:?전략을 지정한다: none|pessimistic|optimistic|unique|redis}"
REPEATS="${REPEATS:-3}"   # 7.4: 전략당 3회 반복
WARMUP_SEC="${WARMUP_SEC:-30}"
# 7.4: 개발 확인용 30초(기본값) / 최종 측정용 120초.
# 45회(5전략 × 3회 × 3시나리오)를 120초로 돌리면 두 시간을 넘는다.
DURATION_SEC="${DURATION_SEC:-30}"
FINAL_DURATION_SEC=120

if [ "$DURATION_SEC" -lt "$FINAL_DURATION_SEC" ]; then
  echo "[run] 개발 확인용 실행이다(본 측정 ${DURATION_SEC}초 < ${FINAL_DURATION_SEC}초)."
  echo "[run] 이 실행의 숫자는 7.6 기록 양식에 싣지 않는다."
  echo "[run] 최종 측정: DURATION_SEC=${FINAL_DURATION_SEC} $0 $SCENARIO $STRATEGY"
  echo
fi

for run in $(seq 1 "$REPEATS"); do
  echo
  echo "════ $STRATEGY / $SCENARIO — ${run}회차 (총 ${REPEATS}회) ════"

  # 7.4-1 시드 초기화. 매 회차마다 같은 상태에서 시작한다(7.3).
  "$ROOT/load-test/scripts/seed.sh" "$SCENARIO" "$STRATEGY"

  # 7.4-2,3 워밍업 + 본 측정
  k6_run /scenarios/reservation.js \
    -e "SCENARIO=$SCENARIO" -e "STRATEGY=$STRATEGY" -e "RUN=$run" \
    -e "WARMUP_SEC=$WARMUP_SEC" -e "DURATION_SEC=$DURATION_SEC" \
    || echo "[run] k6가 임계값 위반으로 실패했다 — 결과는 남아 있다"

  # 7.1 초과 예약은 k6가 아니라 DB 검증 쿼리로 센다.
  "$ROOT/load-test/scripts/verify.sh" || true
done

echo
echo "[run] 완료. 요약:"
echo "  node load-test/scripts/summarize.mjs --strategy $STRATEGY --scenario $SCENARIO"
