#!/usr/bin/env bash
# run-pages.sh — PER-002 화면별 응답시간 측정 실행기 (이슈 #105).
#
# 프로토콜과 판정은 docs/results/per-002-page-timing.md에 있다. 이 파일은 그
# 프로토콜을 실행할 뿐이며, 값을 정하는 곳이 아니다.
#
#   0. 사전 점검 — 컨테이너 전수 + nginx 너머 /api/health (7.4.0)
#   1. 시드 초기화 (저경합 프로필 — 1000석)
#   2. 화면용 픽스처 — 예약 250건·발권·검표 (sql/pages-fixture.sql)
#   3. 조건별 측정
#        none : 배경 부하 없음
#        low  : 배경에 저경합 부하(1000석 / 100 VU)를 깔고 그 위에서 화면을 잰다
#   4. 조건당 3회 반복, 화면별 p95의 중앙값 (summarize-pages.mjs)
#
# 사용:
#   load-test/scripts/run-pages.sh              # 두 조건 전부
#   load-test/scripts/run-pages.sh none         # 무부하만
#   REPEATS=1 load-test/scripts/run-pages.sh    # 빠른 확인 (숫자는 결과에 싣지 않는다)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

# Git Bash가 /scenarios/... 를 Windows 경로로 바꾸는 것을 막는다(run.sh와 같다).
export MSYS_NO_PATHCONV=1

CONDITIONS=("$@")
[ ${#CONDITIONS[@]} -gt 0 ] || CONDITIONS=(none low)

REPEATS="${REPEATS:-3}"          # 7.4와 같은 3회
WARMUP_SEC="${WARMUP_SEC:-30}"   # 7.4와 같은 30초. 화면은 템플릿 파싱도 여기서 녹는다
DURATION_SEC="${DURATION_SEC:-60}"
# 배경 부하는 화면 측정 창을 앞뒤로 감싸야 한다. 화면 쪽이 워밍업 30 + 본측정 60이고
# 배경이 자리를 잡을 때까지 기다리는 시간(BG_LEAD)이 앞에 붙는다.
BG_LEAD_SEC="${BG_LEAD_SEC:-35}"
BG_DURATION_SEC="${BG_DURATION_SEC:-150}"

SEED_SCENARIO=low                # 7.2 저경합 — 1000석. 근거는 결과 문서에 있다
FIXTURE_RESERVATIONS="${FIXTURE_RESERVATIONS:-250}"
DB_SERVICE="${DB_SERVICE:-db}"
DB_USER="${DB_USER:-holdfast}"
DB_NAME="${DB_NAME:-holdfast}"

MEASURE_SESSION="${MEASURE_SESSION:-$(date +%Y%m%d-%H%M)}"
export MEASURE_SESSION

# 7.3 — 무엇을 잰 것인지 결과가 스스로 들고 있어야 한다. run.sh와 같은 방식이다.
MEASURE_COMMIT="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
MEASURE_DIRTY=no
[ -n "$(git status --porcelain 2>/dev/null)" ] && MEASURE_DIRTY=yes
APP_CID="$(docker compose ps -q app1 2>/dev/null || echo '')"
APP_IMAGE_ID="$(docker inspect "$APP_CID" --format '{{.Image}}' 2>/dev/null || echo '')"
MEASURE_IMAGE="$(printf %s "${APP_IMAGE_ID}" | cut -c8-19)"
[ -n "$MEASURE_IMAGE" ] || MEASURE_IMAGE=unknown
MEASURE_IMAGE_BUILT="$(docker image inspect "${APP_IMAGE_ID:-holdfast-app1}" \
  --format '{{.Created}}' 2>/dev/null || echo unknown)"
MEASURE_IMAGE_STALE=unknown
if [ "$MEASURE_IMAGE_BUILT" != "unknown" ]; then
  head_epoch="$(git log -1 --format=%ct 2>/dev/null || echo 0)"
  img_epoch="$(date -d "$MEASURE_IMAGE_BUILT" +%s 2>/dev/null || echo 0)"
  if [ "$img_epoch" -gt 0 ] && [ "$head_epoch" -gt 0 ]; then
    if [ "$img_epoch" -lt "$head_epoch" ]; then MEASURE_IMAGE_STALE=yes; else MEASURE_IMAGE_STALE=no; fi
  fi
fi

# **전략은 이 측정의 변수가 아니지만 기록한다.** 화면은 조회 경로라 락을 지나지
# 않는다. 그래도 배경 부하가 무엇을 하는지는 전략에 달렸으므로, 지금 도는 앱이
# 무엇으로 떠 있는지 읽어서 남긴다 — 바꾸지는 않는다. 전략을 바꾸려면 앱을
# 재기동해야 하고, 그것은 이 측정에 새 변수를 더하는 일이다.
APP_STRATEGY="$(docker inspect "$APP_CID" \
  --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null \
  | sed -n 's/^HOLDFAST_STRATEGY=//p' | head -1)"
[ -n "$APP_STRATEGY" ] || APP_STRATEGY=none

export MEASURE_COMMIT MEASURE_DIRTY MEASURE_IMAGE MEASURE_IMAGE_STALE

psql_run() {
  docker compose exec -T "$DB_SERVICE" psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 "$@"
}

k6_run() {
  local script="$1"; shift
  docker compose -f docker-compose.yml -f docker-compose.k6.yml \
    --profile load run --rm \
    -e "MEASURE_SESSION=$MEASURE_SESSION" \
    -e "MEASURE_COMMIT=$MEASURE_COMMIT" \
    -e "MEASURE_DIRTY=$MEASURE_DIRTY" \
    -e "MEASURE_IMAGE=$MEASURE_IMAGE" \
    -e "MEASURE_IMAGE_STALE=$MEASURE_IMAGE_STALE" \
    "$@" k6 run "$script"
}

# --- 사전 점검 (7.4.0) -------------------------------------------------------
#
# **`./holdfast`의 warn_stopped를 여기서는 경고가 아니라 중단으로 격상한다**(#132).
# 그쪽은 사람이 보는 개발 명령이라 알려주고 진행하는 것이 맞지만, 측정은 죽은
# 컨테이너 하나에 40분을 통째로 버린 적이 있다
# (docs/results/discarded-measurements.md 4번). 사람이 화면을 보고 있지 않은
# 백그라운드 실행이라 경고는 아무도 읽지 않는다.
#
# **/api/health만으로는 부족하다.** nginx가 app1·app2로 라운드로빈하므로 app2가
# 죽어 있어도 절반은 200이 온다. 그 상태로 화면을 재면 인스턴스 한 대에 부하가
# 몰린 값을 재게 된다.
PREFLIGHT_TIMEOUT_SEC="${PREFLIGHT_TIMEOUT_SEC:-90}"

preflight() {
  echo "[pages] 사전 점검 — 컨테이너 전수 확인 (#132의 warn_stopped를 중단으로)"
  local stopped
  stopped="$(docker compose ps --all --format '{{.Service}} {{.State}}' 2>/dev/null \
    | awk '$2 != "running" { print $1 }' | paste -sd' ' -)"
  if [ -n "$stopped" ]; then
    echo "[pages] !! running이 아닌 서비스가 있다: $stopped" >&2
    echo "[pages]    측정을 시작하지 않는다. 전체를 다시 올린 뒤 실행하라:" >&2
    echo "[pages]      ./holdfast up" >&2
    exit 1
  fi

  echo "[pages] 사전 점검 — nginx를 통해 앱이 응답하는지 확인한다 (7.4.0)"
  local waited=0
  while true; do
    if docker compose exec -T nginx \
         wget -q -O- --timeout=5 http://127.0.0.1:80/api/health >/dev/null 2>&1; then
      [ "$waited" -gt 0 ] && echo "[pages]   ${waited}초 만에 응답 — 기동 중이었다."
      return 0
    fi
    [ "$waited" -ge "$PREFLIGHT_TIMEOUT_SEC" ] && break
    sleep 3
    waited=$((waited + 3))
  done
  echo "[pages] !! 사전 점검 실패 — ${PREFLIGHT_TIMEOUT_SEC}초 동안 응답이 없다." >&2
  exit 1
}

# --- 시드 + 화면용 픽스처 -----------------------------------------------------
#
# 회차마다 다시 만든다. 배경 부하가 도는 조건에서는 회차 사이에 재고가 팔려
# 나가므로, 다시 만들지 않으면 3회차는 1회차와 다른 화면을 재게 된다.
prepare_data() {
  "$ROOT/load-test/scripts/seed.sh" "$SEED_SCENARIO" "$APP_STRATEGY" >/dev/null
  echo "[pages] 시드 완료 — 저경합 프로필(1000석). 화면용 픽스처를 얹는다."
  psql_run -v "session_id=1" -v "reservations=$FIXTURE_RESERVATIONS" \
    -f - < "$ROOT/load-test/sql/pages-fixture.sql" | tail -4
}

run_pages() {  # $1 = 조건, $2 = 회차
  k6_run /scenarios/pages.js \
    -e "LOAD_CONDITION=$1" -e "RUN=$2" \
    -e "WARMUP_SEC=$WARMUP_SEC" -e "DURATION_SEC=$DURATION_SEC" \
    -e "RESERVATION_ID=1" -e "USER_ID=1" \
    || echo "[pages] k6가 임계값 위반으로 실패했다 — 결과는 남아 있다"
}

# 배경 부하. **화면 측정과 같은 하네스를 쓴다** — 부하를 새로 만들면 그 부하가
# 무엇인지 다시 검증해야 하고, reservation.js는 이미 60회를 지난 것이다.
#
# 재초기화 마커(HOLDFAST_RESEED_NOW)에 반응하지 않는다. 재초기화는 화면용
# 픽스처를 지우므로, 그것을 돌리면 **관리자 화면이 빈 표를 그린 채로 측정된다.**
# 대신 배경이 재고를 소진해 가는 것을 그대로 두고, 무엇을 했는지는 배경 자신의
# 요약 JSON으로 남긴다.
start_background() {  # $1 = 회차
  k6_run /scenarios/reservation.js \
    -e "SCENARIO=low" -e "STRATEGY=$APP_STRATEGY" -e "RUN=$1" \
    -e "WARMUP_SEC=30" -e "DURATION_SEC=$BG_DURATION_SEC" \
    > "$ROOT/load-test/results/pages-bg-$MEASURE_SESSION-run$1.log" 2>&1 &
  BG_PID=$!
}

preflight

echo "[pages] 측정 세션 = $MEASURE_SESSION"
echo "[pages] 커밋 = $MEASURE_COMMIT (작업 트리 변경: $MEASURE_DIRTY)"
echo "[pages] 이미지 = $MEASURE_IMAGE (빌드 $MEASURE_IMAGE_BUILT, HEAD보다 오래됨: $MEASURE_IMAGE_STALE)"
echo "[pages] 앱 전략 = $APP_STRATEGY (읽기만 한다 — 이 측정은 전략을 바꾸지 않는다)"
if [ "$MEASURE_DIRTY" = "yes" ]; then
  echo "[pages]   !! 커밋되지 않은 변경이 있다. 이 해시만으로는 재현되지 않는다."
fi
if [ "$DURATION_SEC" -lt 60 ] || [ "$REPEATS" -lt 3 ]; then
  echo "[pages]   ※ 확인용 실행이다(본 측정 ${DURATION_SEC}초 × ${REPEATS}회)."
  echo "[pages]     이 실행의 숫자는 결과 문서에 싣지 않는다."
fi

for condition in "${CONDITIONS[@]}"; do
  case "$condition" in
    none|low) ;;
    *) echo "[pages] 조건은 none|low 중 하나여야 한다: $condition" >&2; exit 2 ;;
  esac

  for run in $(seq 1 "$REPEATS"); do
    echo
    echo "════ 부하 조건 $condition — ${run}회차 (총 ${REPEATS}회) ════"
    prepare_data

    if [ "$condition" = "low" ]; then
      echo "[pages] 배경 부하 시작 — 저경합(1000석 / 100 VU), ${BG_DURATION_SEC}초"
      start_background "$run"
      echo "[pages]   배경이 목표 VU에 오를 때까지 ${BG_LEAD_SEC}초 기다린다"
      sleep "$BG_LEAD_SEC"
    fi

    run_pages "$condition" "$run"

    if [ "$condition" = "low" ]; then
      echo "[pages] 배경 부하가 끝나기를 기다린다"
      wait "$BG_PID" || true
      # 배경이 실제로 부하였는지 결과 문서가 확인할 수 있어야 한다.
      grep -E '측정 구간|TPS|409|오류' \
        "$ROOT/load-test/results/pages-bg-$MEASURE_SESSION-run$run.log" | tail -8 || true
    fi
  done
done

echo
echo "[pages] 완료. 요약:"
echo "  load-test/scripts/summarize-pages.mjs --session $MEASURE_SESSION"
