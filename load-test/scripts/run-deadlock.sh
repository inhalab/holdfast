#!/usr/bin/env bash
# run-deadlock.sh — 데드락 회피 규칙 검증 (설계서 7.2.1).
#
# **판정 기준은 처리량이 아니라 데드락 발생 0건이다.**
#
# 앱은 데드락을 잡아 409 LOCK_TIMEOUT으로 변환하므로(api-spec 3.3) k6가 받는
# 응답만으로는 데드락이었는지 단순 락 대기였는지 알 수 없다. 초과 예약과 같은
# 이유로(7.1) 판정 출처를 DB에 둔다.
#
# 사용:
#   load-test/scripts/run-deadlock.sh <strategy> [scenario]
#   예) load-test/scripts/run-deadlock.sh pessimistic high
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
SCENARIO="${2:-high}"          # 좌석 풀. 순서가 엇갈리려면 3석보다 넉넉해야 한다
RUN="${RUN:-1}"
WARMUP_SEC="${WARMUP_SEC:-30}"
DURATION_SEC="${DURATION_SEC:-30}"
DB_SERVICE="${DB_SERVICE:-db}"
DB_USER="${DB_USER:-holdfast}"
DB_NAME="${DB_NAME:-holdfast}"

deadlock_count() {
  docker compose exec -T "$DB_SERVICE" psql -U "$DB_USER" -d "$DB_NAME" -tAc \
    "SELECT deadlocks FROM pg_stat_database WHERE datname = current_database();" 2>/dev/null | tr -d '[:space:]'
}

echo "════ 데드락 회피 검증 — $STRATEGY / 좌석풀 $SCENARIO ════"
echo "  요청당 3석, 좌석 ID를 뒤섞어 전송한다."
echo "  클라이언트가 정렬해 보내면 서버가 정렬을 빠뜨려도 데드락이 나지 않아"
echo "  서버의 정렬 로직(5.1)이 실제로 도는지 확인할 수 없다."
echo

# 7.4-1 시드 초기화
"$ROOT/load-test/scripts/seed.sh" "$SCENARIO" "$STRATEGY"

BEFORE="$(deadlock_count || true)"
if [ -z "$BEFORE" ]; then
  echo "[deadlock] pg_stat_database를 읽지 못했다. DB가 떠 있는지 확인한다." >&2
  exit 3
fi
echo "[deadlock] 실행 전 누적 데드락: $BEFORE"

docker compose -f docker-compose.yml -f docker-compose.k6.yml \
  --profile load run --rm -e "MEASURE_SESSION=$MEASURE_SESSION" \
  -e "SCENARIO=$SCENARIO" -e "STRATEGY=$STRATEGY" -e "RUN=$RUN" \
  -e "WARMUP_SEC=$WARMUP_SEC" -e "DURATION_SEC=$DURATION_SEC" \
  k6 run /scenarios/deadlock.js \
  || echo "[deadlock] k6가 임계값 위반으로 실패했다 — 결과는 남아 있다"

AFTER="$(deadlock_count)"
DIFF=$(( AFTER - BEFORE ))
echo
echo "[deadlock] 실행 후 누적 데드락: $AFTER"
echo "[deadlock] 이번 실행에서 발생: $DIFF"

# Postgres 로그로 교차 확인. deadlock_timeout이 200ms라 났다면 빠르게 검출된다(7.3).
echo
echo "[deadlock] Postgres 로그의 deadlock 항목:"
docker compose logs "$DB_SERVICE" 2>/dev/null | grep -i "deadlock detected" | tail -5 \
  || echo "  (없음)"

echo
if [ "$DIFF" -eq 0 ]; then
  echo "✅ 통과 — 데드락 0건. 5.1의 좌석 ID 정렬·전역 락 순서가 지켜졌다."
  exit 0
else
  cat <<MSG
❌ 실패 — 데드락 ${DIFF}건.

  5.1의 규칙이 지켜지지 않았다. 확인할 것:
   1) 애플리케이션이 좌석 ID를 오름차순 정렬한 뒤 획득하는가
   2) 전역 락 순서(사용자 할당량 행 → 좌석 행)를 지키는가
   3) 만료 홀드 정리 UPDATE도 같은 순서 안에 있는가 (erd.md 4.1)

  Postgres는 FOR UPDATE에서 정렬 전 스캔 순서로 락을 잡을 수 있어 ORDER BY만으로
  완전히 안전하지 않다. 계속 관측되면 5.1이 예고한 대로 정렬된 ID를 한 건씩
  개별 락으로 전환하는 것을 검토한다.
MSG
  exit 1
fi
