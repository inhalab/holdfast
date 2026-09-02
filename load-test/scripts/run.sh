#!/usr/bin/env bash
# run.sh — 측정 프로토콜 실행기 (설계서 7.4).
#
#   1. 시드 초기화
#   2. 워밍업 30초 — 집계에서 제외 (시나리오가 처리한다)
#   3. **본 측정 시작 시 시드 재초기화** — 아래 설명
#   4. 본 측정
#   5. 전략당 3회 반복, 중앙값 채택 (summarize.mjs가 계산한다)
#
# ## 3단계가 필요한 이유
#
# 좌석은 유한 자원이고 확정된 좌석은 돌아오지 않는다. 그래서 워밍업 30초가
# 재고를 전부 소모해 버리고, 정작 측정 구간에는 경합이 남지 않는다. 실제로
# 세 경합도 모두 측정 구간 성공 0건 · 409율 100%로 나왔고, 극단(1석/200VU)은
# 램프업이 도착을 직렬화해 초과 확정(V-1)이 0건이었다.
#
# 워밍업의 목적은 JIT과 커넥션 풀을 데우는 것이지 경합 구간을 버리는 것이
# 아니다. 재초기화는 그 목적을 살리면서 경합을 측정 구간으로 옮긴다. VU가
# 전부 올라온 상태에서 좌석이 열리므로 실제 티켓팅 오픈 순간에 더 가깝다.
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

# 7.1의 Actuator 출처 지표(커넥션 풀, 낙관적 재시도·소진, 제약 위반)를 파일로
# 남긴다. 값이 인스턴스 기동 이후 누적이라, 7.4.2의 대조군으로 넘어가며 앱을
# 재기동하는 순간 사라진다 — 그래서 회차 직후에 자동으로 받아둔다.
#
# node는 MSYS 경로 변환이 필요한 네이티브 exe다(위 docker용 /scenarios/... 보호와
# 반대 상황). MSYS_NO_PATHCONV는 "값이 있으면 비활성화"라 =0으로는 안 풀린다 —
# env -u로 아예 지워야 실제 호스트 경로로 정상 변환된다.
snapshot_metrics() {  # $1 = run 라벨
  env -u MSYS_NO_PATHCONV node "$ROOT/load-test/scripts/metrics-snapshot.mjs" \
    --label "$STRATEGY/$SCENARIO $1" \
    --strategy "$STRATEGY" --scenario "$SCENARIO" --run "$1" \
    --out "$ROOT/load-test/results/metrics-$STRATEGY-$SCENARIO-$1.json" || true
}

# 기준선. 1회차의 델타를 내려면 첫 실행 **전** 값이 있어야 한다.
snapshot_metrics "run0"

for run in $(seq 1 "$REPEATS"); do
  echo
  echo "════ $STRATEGY / $SCENARIO — ${run}회차 (총 ${REPEATS}회) ════"

  # 7.4-1 시드 초기화. 매 회차마다 같은 상태에서 시작한다(7.3).
  "$ROOT/load-test/scripts/seed.sh" "$SCENARIO" "$STRATEGY"

  # 7.4-2,3,4 워밍업 → 본 측정 시작 시 시드 재초기화 → 본 측정
  #
  # 재초기화 시점은 시나리오가 알려준다. 워밍업 경계를 넘은 첫 요청이
  # RESEED_MARKER를 찍으면 그 줄을 보고 시드를 다시 돌린다. 호스트에서
  # 시계로 재면 컨테이너 기동 시간만큼 어긋나 재초기화가 워밍업 안으로
  # 들어갈 수 있고, 그러면 열린 재고를 워밍업이 도로 소모해 버린다.
  # k6가 임계값 위반으로 0이 아닌 코드를 내도 결과 JSON은 남으므로 여기서
  # 중단하지 않는다. 파이프라인 전체를 묶어 pipefail이 스크립트를 죽이지 않게 한다.
  {
    k6_run /scenarios/reservation.js \
      -e "SCENARIO=$SCENARIO" -e "STRATEGY=$STRATEGY" -e "RUN=$run" \
      -e "WARMUP_SEC=$WARMUP_SEC" -e "DURATION_SEC=$DURATION_SEC" 2>&1 \
      || echo "[run] k6가 임계값 위반으로 실패했다 — 결과는 남아 있다"
  } | {
    reseeded=0
    while IFS= read -r line; do
      printf '%s\n' "$line"
      case "$line" in
        *HOLDFAST_RESEED_NOW*)
          if [ "$reseeded" = "0" ]; then
            reseeded=1
            echo "[run] 본 측정 시작 — 시드 재초기화 (7.4-3)"
            # 실패를 삼키지 않는다. 재초기화가 안 된 채로 이어지면 매진 상태를
            # 재면서 숫자는 그럴듯하게 나온다 — 가장 알아채기 어려운 오염이다.
            if ! "$ROOT/load-test/scripts/seed.sh" "$SCENARIO" "$STRATEGY" --reset; then
              echo "[run] !! RESEED_FAILED — 이 회차는 폐기한다 (7.4-3)"
            fi
          fi
          ;;
      esac
    done
  }

  # 7.1 초과 예약은 k6가 아니라 DB 검증 쿼리로 센다.
  # **콘솔로만 흘리지 않고 파일에도 남긴다.** 다음 회차의 시드가 DB를 덮으므로,
  # 여기서 받아두지 않으면 그 회차의 V-1·V-2를 되찾을 방법이 없다.
  "$ROOT/load-test/scripts/verify.sh" 2>&1 \
    | tee "$ROOT/load-test/results/verify-$STRATEGY-$SCENARIO-run${run}.txt" || true

  # 7.1 Actuator 출처 지표. **전략 전환 전에 마지막 스냅샷이 확보된다.**
  snapshot_metrics "run${run}"
done

echo
echo "[run] 완료. 요약:"
echo "  node load-test/scripts/summarize.mjs --strategy $STRATEGY --scenario $SCENARIO"
