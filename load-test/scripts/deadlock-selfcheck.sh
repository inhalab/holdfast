#!/usr/bin/env bash
# deadlock-selfcheck.sh — **데드락 검사가 실제로 잡는지 확인한다.** 7.2.1.
#
# ## 왜 필요한가
#
# `run-deadlock.sh` 의 판정은 `pg_stat_database.deadlocks` 의 차이 하나다.
# **그 값이 0 이면 두 가지 중 하나다 — 데드락이 없었거나, 세는 장치가 안 도는
# 것이거나.** 그것을 가르지 못하면 그 0 은 판정이 아니다.
#
# CS-4 에서는 `verify-cancel-selfcheck.sql` 이 위반 셋을 심어 검사가 잡는지 본
# 뒤 롤백했다. **데드락 0 에는 그 절차가 없었다** — 같은 형태의 0 인데 한쪽만
# 검증돼 있었다(#221 리뷰).
#
# ## 무엇을 하나
#
# 두 세션이 좌석 행 둘을 **반대 순서로** 잡는다. Postgres 가
# `deadlock_timeout`(7.3 은 200ms) 뒤에 한쪽을 죽이고 카운터를 올린다.
# **카운터가 정확히 1 오르면 이 저장소의 데드락 판정이 서는 것이고, 안 오르면
# 7.2.1 의 「0 건」이 아무것도 말하지 않는 것이다.**
#
# ## 데이터를 남기지 않는다
#
# 두 세션 모두 `ROLLBACK` 으로 끝낸다. `version = version` 이라 값도 안 바뀐다.
# **부하와 겹쳐 돌리지 않는다** — 그쪽 데드락이 섞이면 증가분을 못 가른다.
#
# 사용: load-test/scripts/deadlock-selfcheck.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
export MSYS_NO_PATHCONV=1

DB_SERVICE="${DB_SERVICE:-db}"
DB_USER="${DB_USER:-holdfast}"
DB_NAME="${DB_NAME:-holdfast}"
SESSION_ID="${SESSION_ID:-1}"

psql_q() {
  docker compose exec -T "$DB_SERVICE" psql -U "$DB_USER" -d "$DB_NAME" -tAc "$1" 2>/dev/null | tr -d '[:space:]'
}
psql_f() {
  docker compose exec -T "$DB_SERVICE" psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=0 2>&1
}

SEAT_A="$(psql_q "SELECT min(id) FROM seat_inventory WHERE session_id = $SESSION_ID")"
SEAT_B="$(psql_q "SELECT max(id) FROM seat_inventory WHERE session_id = $SESSION_ID")"

if [ -z "${SEAT_A:-}" ] || [ -z "${SEAT_B:-}" ] || [ "$SEAT_A" = "$SEAT_B" ]; then
  echo "❌ 좌석 둘을 못 빌렸다 (A=$SEAT_A B=$SEAT_B). 시드를 먼저 돌린다:" >&2
  echo "   load-test/scripts/seed.sh cancel pessimistic" >&2
  exit 2
fi

BEFORE="$(psql_q "SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()")"
echo "[selfcheck] 좌석 A=$SEAT_A · B=$SEAT_B · 시작 시점 누적 데드락 $BEFORE"
echo "[selfcheck] 두 세션이 반대 순서로 잡는다 — Postgres 가 한쪽을 죽여야 한다."

# 세션 1: A → B
psql_f <<SQL > /tmp/dlsc1.log 2>&1 &
BEGIN;
UPDATE seat_inventory SET version = version WHERE id = $SEAT_A;
SELECT pg_sleep(1);
UPDATE seat_inventory SET version = version WHERE id = $SEAT_B;
ROLLBACK;
SQL
PID1=$!

# 세션 2: B → A  (반대 순서가 이 검사의 전부다)
psql_f <<SQL > /tmp/dlsc2.log 2>&1 &
BEGIN;
UPDATE seat_inventory SET version = version WHERE id = $SEAT_B;
SELECT pg_sleep(1);
UPDATE seat_inventory SET version = version WHERE id = $SEAT_A;
ROLLBACK;
SQL
PID2=$!

wait "$PID1" || true
wait "$PID2" || true

AFTER="$(psql_q "SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()")"
DELTA=$(( AFTER - BEFORE ))

echo
echo "[selfcheck] 끝 시점 누적 데드락 $AFTER (증가 $DELTA)"
if grep -qi "deadlock detected" /tmp/dlsc1.log /tmp/dlsc2.log 2>/dev/null; then
  echo "[selfcheck] 죽은 쪽 메시지:"
  grep -i -m1 -A2 "deadlock detected" /tmp/dlsc1.log /tmp/dlsc2.log 2>/dev/null | sed 's/^/    /'
fi

echo
if [ "$DELTA" -ge 1 ]; then
  cat <<'MSG'
✅ 검사가 잡는다 — 일부러 만든 데드락이 카운터에 올랐다.

  **그래서 7.2.1 의 「0 건」이 판정이 된다.** 세는 장치가 도는 것을 확인했으므로
  0 은 "데드락이 없었다"이고, "못 본다"가 아니다.
MSG
  exit 0
else
  cat <<'MSG'
❌ 검사가 못 잡는다 — 데드락을 일부러 만들었는데 카운터가 안 올랐다.

  **7.2.1 의 「데드락 0 건」이 아무것도 말하지 않는다.** 그 판정에 기대고 있는
  것을 전부 다시 봐야 한다 — 5.1 의 좌석 ID 정렬, 전역 락 순서, 락 순서를
  건드린 변경들.

  확인할 것: deadlock_timeout 이 너무 길어 두 세션이 먼저 끝나지는 않았는지,
  pg_stat_database 가 이 DB 를 보고 있는지, 두 세션이 실제로 겹쳤는지
  (/tmp/dlsc1.log · /tmp/dlsc2.log).
MSG
  exit 1
fi
