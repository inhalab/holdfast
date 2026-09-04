#!/usr/bin/env bash
# measure.sh — 측정 실행기. run.sh·run-deadlock.sh 위에 얹는 껍데기다.
#
# 기존 스크립트를 **대체하지 않는다.** 사전 점검(7.4.0), 시드 재초기화(7.4.1),
# 회차별 캡처(7.4.2), 커밋·이미지 기록(7.3)은 전부 run.sh 것을 그대로 탄다.
# 이 스크립트가 더하는 것은 셋뿐이다.
#
#   1. 무엇을 고를 수 있는지 보여준다 — 시나리오·전략 이름을 외우지 않아도 된다
#   2. 전량 실행에서 MEASURE_SESSION을 **한 번만** 잡는다
#   3. 시작 전에 예상 시간을 알려주고 확인을 받는다
#
# 2번이 이 스크립트를 만든 실질적 이유다. 전략마다 run.sh를 부르면 호출마다
# 세션 태그가 새로 생겨 summarize.mjs가 마지막 전략 하나만 잡는다. 실제로
# 그렇게 잃을 뻔했다(load-test/README의 측정 세션 절).
#
# 사용:
#   measure.sh                          대화형 — 선택지를 보여준다
#   measure.sh high pessimistic         단건. 지속시간은 기본(개발 확인용 30초)
#   measure.sh high pessimistic 120     단건, 최종 측정용
#   measure.sh --preset m3-all          5전략 × 3시나리오 (M3 전량)
#   measure.sh --preset high-controlled 고경합 + 전략별 대조군 (7.8.3 방식)
#   measure.sh --preset deadlock        데드락 회피 검증 5전략 (7.2.1)
#   measure.sh --preset ... --yes       확인 생략. 스크립트에서 부를 때 쓴다
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

SCENARIOS=(low high extreme sustained)
STRATEGIES=(none pessimistic optimistic unique redis)

# 회차 하나의 벽시계 시간(초) = 워밍업 30 + 본 측정 + 시드·검증·스냅샷 여유.
# 실측에서 120초 측정이 회차당 2분 30초~3분이었다(results 파일 타임스탬프).
overhead_sec() { echo 50; }
run_seconds() { echo $(( 30 + $1 + $(overhead_sec) )); }

fmt_minutes() {
  local s=$1
  if [ "$s" -lt 60 ]; then echo "${s}초"; else echo "약 $(( (s + 59) / 60 ))분"; fi
}

usage() {
  cat <<'USAGE'
사용법: measure.sh [시나리오 전략 [지속초]] | --preset <이름> [--yes]

  measure.sh                          대화형 — 선택지를 보여준다
  measure.sh high pessimistic         단건 (개발 확인용 30초)
  measure.sh high pessimistic 120     단건 (최종 측정용)
  measure.sh --preset high-controlled 프리셋
  measure.sh --preset deadlock --yes  확인 생략 (스크립트에서 부를 때)

시나리오  low | high | extreme | sustained
전략      none | pessimistic | optimistic | unique | redis

프리셋
  m3-all           5전략 × 3시나리오 (45회)
  high-controlled  고경합 + 전략별 대조군, 끝에 잡음 바닥 (30회)
  deadlock         데드락 회피 검증 5전략

옵션
  --yes, -y   예상 시간 확인을 건너뛴다
  --help, -h  이 도움말

환경변수
  MEASURE_SESSION      세션 태그를 직접 지정 (기본: 실행 시각)
  REPEATS              회차 수 (기본 3)
  PREFLIGHT_TIMEOUT_SEC 사전 점검 대기 상한 (기본 90)
USAGE
}

# ── 인자 파싱 ────────────────────────────────────────────────────────────
PRESET=""
ASSUME_YES=no
POSITIONAL=()
while [ $# -gt 0 ]; do
  case "$1" in
    --preset) PRESET="${2:?--preset 뒤에 이름이 필요하다}"; shift 2 ;;
    --yes|-y) ASSUME_YES=yes; shift ;;
    -h|--help) usage; exit 0 ;;
    --*) echo "알 수 없는 옵션: $1" >&2; echo >&2; usage >&2; exit 2 ;;
    *) POSITIONAL+=("$1"); shift ;;
  esac
done

contains() { local n="$1"; shift; for x in "$@"; do [ "$x" = "$n" ] && return 0; done; return 1; }

# ── 대화형 선택 ──────────────────────────────────────────────────────────
choose() {
  # $1 프롬프트, $2.. 선택지. 고른 값을 CHOICE에 담는다.
  local prompt="$1"; shift
  local opts=("$@") i=1
  echo >&2
  echo "$prompt" >&2
  for o in "${opts[@]}"; do printf '  %d) %s\n' "$i" "$o" >&2; i=$((i+1)); done
  local reply
  if ! read -r -p "번호 (기본 1): " reply; then
    # 비대화형(파이프·CI)에서는 기본값으로 간다. 여기서 멈추면 스크립트에서
    # 부를 수 없는데, 그러면 이 실행기를 만든 목적 절반이 사라진다.
    echo "  (입력이 없어 1번으로 진행한다)" >&2
    reply=""
  fi
  [ -n "$reply" ] || reply=1
  CHOICE="${opts[$((reply-1))]:-${opts[0]}}"
  echo "  → $CHOICE" >&2
}

confirm() {
  # $1 예상 시간 문구. 확인을 받으면 0.
  [ "$ASSUME_YES" = "yes" ] && return 0
  local reply
  if ! read -r -p "진행할까? [y/N] " reply; then
    echo "입력이 없다. --yes 를 붙여 실행하라." >&2
    return 1
  fi
  case "$reply" in y|Y|yes|YES) return 0 ;; *) echo "취소했다." >&2; return 1 ;; esac
}

# ── 실행 단위 ────────────────────────────────────────────────────────────
# run.sh는 한 번 부를 때마다 REPEATS(기본 3)회를 돈다.
REPEATS_PER_CALL="${REPEATS:-3}"

plan_lines=()   # 사람이 읽을 계획
plan_cmds=()    # 실제로 부를 명령 (전략:시나리오:지속)

add_run() { plan_cmds+=("$1:$2:$3"); plan_lines+=("  $2 / $1 — ${3}초 × ${REPEATS_PER_CALL}회"); }
add_deadlock() { plan_cmds+=("deadlock:$1:$2"); plan_lines+=("  데드락 검증 / $1 — ${2}초"); }

build_preset() {
  case "$1" in
    m3-all)
      # 5전략 × 3시나리오. M3가 실제로 돈 조합이다(sustained는 별도 목적이라 뺀다).
      for sc in low high extreme; do
        for st in "${STRATEGIES[@]}"; do add_run "$st" "$sc" 120; done
      done ;;
    high-controlled)
      # 7.8.3 방식 — 전략마다 같은 세션에 none 대조군을 붙이고, 마지막에
      # none 대 none으로 잡음 바닥을 잰다. 그 블록이 없으면 어떤 비율도
      # 해석할 수 없다(discarded-measurements.md C절).
      for st in pessimistic optimistic unique redis; do
        add_run "$st" high 120
        add_run none high 120
      done
      add_run none high 120
      add_run none high 120 ;;
    deadlock)
      for st in "${STRATEGIES[@]}"; do add_deadlock "$st" 120; done ;;
    *) echo "알 수 없는 프리셋: $1" >&2; echo >&2; usage >&2; exit 2 ;;
  esac
}

# ── 계획 세우기 ──────────────────────────────────────────────────────────
if [ -n "$PRESET" ]; then
  build_preset "$PRESET"
  TITLE="프리셋 $PRESET"
elif [ ${#POSITIONAL[@]} -ge 2 ]; then
  SC="${POSITIONAL[0]}"; ST="${POSITIONAL[1]}"; DUR="${POSITIONAL[2]:-30}"
  contains "$SC" "${SCENARIOS[@]}" || {
    echo "시나리오는 ${SCENARIOS[*]} 중 하나다: $SC" >&2; echo >&2; usage >&2; exit 2; }
  contains "$ST" "${STRATEGIES[@]}" || {
    echo "전략은 ${STRATEGIES[*]} 중 하나다: $ST" >&2; echo >&2; usage >&2; exit 2; }
  add_run "$ST" "$SC" "$DUR"
  TITLE="단건"
else
  choose "시나리오를 고른다" "${SCENARIOS[@]}" all; SC="$CHOICE"
  choose "전략을 고른다" "${STRATEGIES[@]}" all; ST="$CHOICE"
  choose "본 측정 길이" "개발 확인용 30초" "최종 측정용 120초"; DUR=30
  [ "$CHOICE" = "최종 측정용 120초" ] && DUR=120

  scs=("$SC"); [ "$SC" = "all" ] && scs=(low high extreme)
  sts=("$ST"); [ "$ST" = "all" ] && sts=("${STRATEGIES[@]}")
  for sc in "${scs[@]}"; do for st in "${sts[@]}"; do add_run "$st" "$sc" "$DUR"; done; done
  TITLE="선택"
fi

# ── 예상 시간과 확인 ─────────────────────────────────────────────────────
total=0
for c in "${plan_cmds[@]}"; do
  IFS=: read -r a b d <<< "$c"
  if [ "$a" = "deadlock" ]; then total=$(( total + $(run_seconds "$d") ));
  else total=$(( total + $(run_seconds "$d") * REPEATS_PER_CALL )); fi
done

echo
echo "════ 측정 계획 ($TITLE) ════"
printf '%s\n' "${plan_lines[@]}"
echo "────────────────────────────"
echo "  실행 단위 ${#plan_cmds[@]}개 · 예상 $(fmt_minutes "$total")"
echo
confirm || exit 1

# ── 세션 태그를 여기서 한 번만 잡는다 ────────────────────────────────────
# **이것이 이 스크립트의 핵심이다.** run.sh는 부를 때마다 태그를 새로 만들므로,
# 전량 실행에서 밖에서 고정하지 않으면 전략마다 세션이 갈린다.
export MEASURE_SESSION="${MEASURE_SESSION:-$(date +%Y%m%d-%H%M)}"
echo "[measure] 측정 세션 = $MEASURE_SESSION (전 실행 단위가 이 태그를 공유한다)"

started=$(date +%s)
for c in "${plan_cmds[@]}"; do
  IFS=: read -r a b d <<< "$c"
  echo
  if [ "$a" = "deadlock" ]; then
    echo "════ 데드락 검증 — $b ════"
    HOLDFAST_STRATEGY="$b" docker compose up -d app1 app2 >/dev/null
    DURATION_SEC="$d" "$ROOT/load-test/scripts/run-deadlock.sh" "$b" high || true
  else
    echo "════ $b / $a ════"
    HOLDFAST_STRATEGY="$a" docker compose up -d app1 app2 >/dev/null
    DURATION_SEC="$d" "$ROOT/load-test/scripts/run.sh" "$b" "$a"
  fi
done

echo
echo "[measure] 완료. 걸린 시간 $(fmt_minutes $(( $(date +%s) - started )))"
echo "[measure] 요약:"
echo "  load-test/scripts/summarize.mjs --scenario high --session $MEASURE_SESSION"
