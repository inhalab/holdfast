#!/usr/bin/env bash
# run.sh — 측정 프로토콜 실행기 (설계서 7.4).
#
#   0. **사전 점검** — 부하 대상이 살아 있는지 확인 (7.4-0)
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

# **측정 세션 태그.** 결과 파일명에 들어가 재측정이 이전 묶음을 덮지 않게 한다.
#
# 이것이 없어서 7.8 확장 측정이 optimistic·unique·redis의 M3 고경합 원본을
# 같은 이름으로 덮었다(docs/results/discarded-measurements.md 4번).
#
# **전략을 바꿔 가며 여러 번 부를 때는 밖에서 고정한다.** run.sh 한 번이 곧
# 한 세션이 아니라, 5전략을 도는 루프 전체가 한 세션이다. 고정하지 않으면
# 전략마다 다른 태그가 붙어 summarize.mjs가 마지막 전략 하나만 잡는다.
#
#   export MEASURE_SESSION=$(date +%Y%m%d-%H%M)
#   for s in none pessimistic optimistic unique redis; do ... done
MEASURE_SESSION="${MEASURE_SESSION:-$(date +%Y%m%d-%H%M)}"
export MEASURE_SESSION

# **측정 대상 커밋.** 어느 코드를 잰 것인지 결과에 남긴다(7.3 고정 변수).
#
# 이것이 없으면 나중에 결과 해석이 흔들린다. 실제로 7.8 확장 측정 40분 사이에
# main에 세 번 머지가 일어났고, 그때는 "무엇을 잰 것인가"를 컨테이너 이미지
# 빌드 시각으로 역추적해야 했다.
#
# **dirty 여부를 함께 남기는 이유는 해시만으로 재현되지 않기 때문이다.**
# 커밋되지 않은 변경으로 잰 결과는 그 해시를 체크아웃해도 같은 코드가 아니다.
#
# 앱은 이미지로 도는데 이 값은 작업 트리에서 읽는다. 둘이 어긋날 수 있으므로
# (--build 없이 up -d 하면 이미지는 그대로다) 이 값은 "그때 트리가 무엇이었나"의
# 기록이지 "이미지가 무엇으로 빌드됐나"의 보증이 아니다. 재측정 전에
# docker compose up -d --build로 맞추는 것이 전제다.
# **`git -C`를 쓰지 않는다.** 위에서 MSYS_NO_PATHCONV=1을 걸어 두어 Windows git이
# `/e/Project/...` 같은 MSYS 경로를 해석하지 못한다 — 실제로 해시가 조용히
# unknown으로 찍혔다. 스크립트가 이미 $ROOT로 cd 해 두었으므로 그냥 부른다.
MEASURE_COMMIT="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
MEASURE_DIRTY=no
if [ -n "$(git status --porcelain 2>/dev/null)" ]; then
  MEASURE_DIRTY=yes
fi
export MEASURE_COMMIT MEASURE_DIRTY

# **실제로 잰 것은 트리가 아니라 이미지다.** 위 해시는 "그때 트리가 무엇이었나"의
# 기록일 뿐이고, 앱은 --build 없이 up -d 하면 예전에 빌드된 이미지로 계속 돈다.
# 7.8.2가 정확히 그 상태였다 — 트리는 #93까지 가 있었는데 이미지는 15:18
# 빌드본이었고, "무엇을 잰 것인가"를 사람이 사후에 역추적해야 했다.
#
# **오래된 이미지가 곧 잘못은 아니다.** 같은 조건을 유지하려고 일부러 다시
# 빌드하지 않는 경우가 있다(7.3). 모르고 그런 것과 알고 그런 것이 다를 뿐이라
# 눈에 보이게만 한다.
#
# 실행 중인 컨테이너에서 읽는다 — `docker compose config --images app1`은 서비스
# 이름을 필터로 받지 않고 첫 이미지를 돌려준다(실제로 redis가 나왔다).
APP_IMAGE_ID="$(docker inspect "$(docker compose ps -q app1 2>/dev/null)" \
  --format '{{.Image}}' 2>/dev/null || echo '')"
MEASURE_IMAGE="$(printf %s "${APP_IMAGE_ID}" | cut -c8-19)"
[ -n "$MEASURE_IMAGE" ] || MEASURE_IMAGE=unknown
MEASURE_IMAGE_BUILT="$(docker image inspect "${APP_IMAGE_ID:-holdfast-app1}" \
  --format '{{.Created}}' 2>/dev/null || echo unknown)"
MEASURE_IMAGE_STALE=unknown
if [ "$MEASURE_IMAGE_BUILT" != "unknown" ]; then
  head_epoch="$(git log -1 --format=%ct 2>/dev/null || echo 0)"
  img_epoch="$(date -d "$MEASURE_IMAGE_BUILT" +%s 2>/dev/null || echo 0)"
  if [ "$img_epoch" -gt 0 ] && [ "$head_epoch" -gt 0 ]; then
    if [ "$img_epoch" -lt "$head_epoch" ]; then MEASURE_IMAGE_STALE=yes;
    else MEASURE_IMAGE_STALE=no; fi
  fi
fi
export MEASURE_IMAGE MEASURE_IMAGE_BUILT MEASURE_IMAGE_STALE

# docker compose run은 -e 옵션이 서비스명(k6) 앞에 와야 한다.
#   run [옵션...] <서비스> <커맨드...>
# 첫 인자로 스크립트 경로를 받고, 나머지 인자는 -e 플래그로 그대로 넘긴다.
k6_run() {
  local script="$1"
  shift
  docker compose -f docker-compose.yml -f docker-compose.k6.yml \
    --profile load run --rm \
    -e "MEASURE_SESSION=$MEASURE_SESSION" \
    -e "MEASURE_COMMIT=$MEASURE_COMMIT" \
    -e "MEASURE_DIRTY=$MEASURE_DIRTY" \
    -e "MEASURE_IMAGE=$MEASURE_IMAGE" \
    -e "MEASURE_IMAGE_STALE=$MEASURE_IMAGE_STALE" \
    "$@" k6 run "$script"
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
    --out "$ROOT/load-test/results/metrics-$STRATEGY-$SCENARIO-$MEASURE_SESSION-$1.json" || true
}

# 7.4-0 사전 점검. **부하를 보낼 대상이 살아 있는지 먼저 본다.**
#
# 이것이 없어서 7.8 확장 측정 15회를 통째로 버렸다. nginx가 측정 1시간 45분
# 전에 종료(255)돼 있었는데 아무도 몰랐고, 15회 전부 오류율 100% · 성공 0건 ·
# p95 0ms로 끝났다(docs/results/discarded-measurements.md 4번). k6는
# `http://nginx:80`으로 보내므로 그 앞단 하나가 없으면 앱이 아무리 멀쩡해도
# 요청이 도달하지 않는다.
#
# 헬스 경로를 nginx **너머로** 통과시킨다. nginx만 살아 있고 업스트림이 죽은
# 경우까지 같은 한 번으로 걸린다.
#
# **127.0.0.1을 쓴다. localhost가 아니다.** nginx 이미지의 BusyBox wget은
# localhost를 ::1로 먼저 푸는데 그쪽은 연결이 거부돼, 스택이 멀쩡한데도 점검이
# 실패한다. 측정을 막는 오탐은 측정을 그냥 돌리는 것만큼 나쁘다.
# **한 번만 보고 포기하지 않는다.** docker compose가 "Started"를 돌려주는 시점과
# 앱이 실제로 응답하는 시점이 다르다 — Spring Boot 기동에 5~6초가 걸리고
# (기동 로그의 "Started HoldfastApplication in 5.7 seconds") 그동안 nginx는
# 업스트림에 붙지 못해 502를 낸다. 실제로 그 창에 점검이 걸려 멈췄다.
#
# **상한을 두는 이유는 이 장치의 목적 그 자체다.** 무한 대기하면 죽은 스택 앞에서
# 조용히 서 있게 되어, 40분을 버리지 않으려던 것이 시간을 무한정 버리는 것으로
# 바뀐다. 90초는 관측된 기동 시간의 15배가 넘고 한 세션(약 40분)의 4%다 —
# 정상 기동을 놓치지 않을 만큼 넉넉하고, 잘못됐을 때 빨리 알려줄 만큼 짧다.
PREFLIGHT_TIMEOUT_SEC="${PREFLIGHT_TIMEOUT_SEC:-90}"
PREFLIGHT_INTERVAL_SEC=3

preflight() {
  echo "[run] 사전 점검 — nginx를 통해 앱이 응답하는지 확인한다 (7.4.0)"
  local waited=0
  while true; do
    if docker compose exec -T nginx          wget -q -O- --timeout=5 http://127.0.0.1:80/api/health >/dev/null 2>&1; then
      if [ "$waited" -gt 0 ]; then
        echo "[run]   ${waited}초 만에 응답 — 기동 중이었다."
      fi
      return 0
    fi
    if [ "$waited" -ge "$PREFLIGHT_TIMEOUT_SEC" ]; then
      break
    fi
    if [ "$waited" = "0" ]; then
      echo "[run]   아직 응답하지 않는다. ${PREFLIGHT_TIMEOUT_SEC}초까지 기다린다."
    fi
    sleep "$PREFLIGHT_INTERVAL_SEC"
    waited=$((waited + PREFLIGHT_INTERVAL_SEC))
  done

  echo "[run] !! 사전 점검 실패 — ${PREFLIGHT_TIMEOUT_SEC}초 동안 nginx를 통한" >&2
  echo "[run]    /api/health가 응답하지 않았다. 측정을 시작하지 않는다." >&2
  echo "[run]    컨테이너 상태와 앱 로그를 확인하라:" >&2
  echo "[run]      docker compose ps -a" >&2
  echo "[run]      docker compose logs --tail 50 app1 nginx" >&2
  exit 1
}

preflight

echo "[run] 측정 세션 = $MEASURE_SESSION"
echo "[run]   결과 파일명에 들어간다. 여러 전략을 한 묶음으로 재려면 루프 밖에서"
echo "[run]   MEASURE_SESSION을 고정하라 — 그러지 않으면 전략마다 세션이 갈린다."
echo "[run] 측정 대상 커밋 = $MEASURE_COMMIT (작업 트리 변경 있음: $MEASURE_DIRTY)"
echo "[run] 측정 대상 이미지 = $MEASURE_IMAGE (빌드 $MEASURE_IMAGE_BUILT)"
if [ "$MEASURE_IMAGE_STALE" = "yes" ]; then
  echo "[run]   ※ 이미지가 HEAD 커밋보다 오래됐다. 트리가 아니라 이 이미지를 잰다."
  echo "[run]     의도한 것이면 그대로 두고, 아니면 docker compose up -d --build."
fi
if [ "$MEASURE_DIRTY" = "yes" ]; then
  echo "[run]   !! 커밋되지 않은 변경이 있다. 이 해시만으로는 재현되지 않는다."
fi

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
    | tee "$ROOT/load-test/results/verify-$STRATEGY-$SCENARIO-$MEASURE_SESSION-run${run}.txt" || true

  # 7.1 Actuator 출처 지표. **전략 전환 전에 마지막 스냅샷이 확보된다.**
  snapshot_metrics "run${run}"

  # 7.4-0 못 쓸 것이 분명한 실행은 계속하지 않는다.
  #
  # 1회차 측정 구간의 성공이 0건이면 나머지 회차도 같은 결과가 나온다. 앞의
  # 폐기 셋은 "숫자가 그럴듯해서" 못 잡은 것이었지만, 이 경우는 시끄러운데도
  # 40분을 더 썼다(discarded-measurements.md 4번). 사전 점검을 지나고도
  # 성공이 0이면 측정 중에 무언가 죽은 것이므로 거기서 멈춘다.
  if [ "$run" = "1" ]; then
    RESULT_JSON="$ROOT/load-test/results/$STRATEGY-$SCENARIO-$MEASURE_SESSION-run1.json"
    if [ -f "$RESULT_JSON" ] && ! env -u MSYS_NO_PATHCONV node -e '
      const r = require(process.argv[1]).row;
      if (!r || !r.counts) process.exit(0);          // 모양이 다르면 판단하지 않는다
      process.exit(r.counts.success > 0 ? 0 : 1);
    ' "$RESULT_JSON"; then
      echo "[run] !! 1회차 측정 구간 성공 0건 — 남은 회차를 돌리지 않는다 (7.4-0)" >&2
      echo "[run]    오류율과 컨테이너 상태를 먼저 확인하라. 이 실행은 폐기 대상이다." >&2
      exit 1
    fi
  fi
done

echo
echo "[run] 완료. 요약:"
# node를 앞에 붙이지 않는다. Git Bash가 mintty에서 node를 winpty로 별칭 처리해,
# 이 명령을 그대로 복사해 파일로 리다이렉트하면 stdin is not a tty로 실패한다
# (load-test/README.md의 Windows 주의). shebang 직접 실행은 별칭을 지나지 않는다.
echo "  load-test/scripts/summarize.mjs --strategy $STRATEGY --scenario $SCENARIO --session $MEASURE_SESSION"
