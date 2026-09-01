#!/usr/bin/env bash
# seed.sh — 측정 프로토콜 1단계 "시드 초기화" (설계서 7.4).
#
# 사용:
#   load-test/scripts/seed.sh <scenario> <strategy>
#   예) load-test/scripts/seed.sh high pessimistic
#
# 시나리오별 좌석 수·VU는 7.2가 못박은 값이며 여기서 바꾸지 않는다.
set -euo pipefail

SCENARIO="${1:-high}"
STRATEGY="${2:-unset}"
SESSION_ID="${SESSION_ID:-1}"
SEAT_ID_BASE="${SEAT_ID_BASE:-1}"
MAX_PER_USER="${MAX_PER_USER:-4}"

case "$SCENARIO" in
  low)     SEATS=1000; USERS=100 ;;   # 7.2 저경합
  high)    SEATS=10;   USERS=500 ;;   # 7.2 고경합
  extreme) SEATS=1;    USERS=200 ;;   # 7.2 극단
  *) echo "SCENARIO는 low|high|extreme 중 하나여야 한다: $SCENARIO" >&2; exit 2 ;;
esac

DB_SERVICE="${DB_SERVICE:-db}"
DB_USER="${DB_USER:-holdfast}"
DB_NAME="${DB_NAME:-holdfast}"
SQL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../sql" && pwd)"

psql_run() {
  docker compose exec -T "$DB_SERVICE" psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 "$@"
}

# 테이블이 아직 없으면 여기서 멈추고 이유를 분명히 알린다.
# 예약 API·엔티티는 아직 구현되지 않았고, 스키마가 생겨야 시드가 돈다.
if ! psql_run -tAc "SELECT to_regclass('public.seat_inventory') IS NOT NULL" | grep -q '^t$'; then
  cat >&2 <<'MSG'
[seed] seat_inventory 테이블이 없다. 시드를 건너뛴다.

  예약 API와 JPA 엔티티가 아직 구현되지 않아 스키마가 만들어지지 않은 상태다.
  엔티티가 생기고 스키마가 올라오면 이 스크립트가 그대로 돈다.
  지금 확인할 수 있는 것은 스모크 테스트뿐이다:

    load-test/scripts/run.sh smoke
MSG
  exit 3
fi

echo "[seed] 시나리오=$SCENARIO 좌석=$SEATS 사용자=$USERS 전략=$STRATEGY"
psql_run \
  -v "seats=$SEATS" -v "users=$USERS" \
  -v "session_id=$SESSION_ID" -v "seat_id_base=$SEAT_ID_BASE" \
  -v "max_per_user=$MAX_PER_USER" \
  -f - < "$SQL_DIR/seed.sql"

# U-2는 전략에 따라 켜고 끈다(erd.md 3.1). none에서만 뺀다.
if [ "$STRATEGY" = "none" ]; then
  echo "[seed] U-2 삭제 — none 베이스라인은 초과 홀드를 막지 않는다"
  psql_run -f - < "$SQL_DIR/u2-drop.sql"
else
  echo "[seed] U-2 생성 — 최후 방어선"
  psql_run -f - < "$SQL_DIR/u2-create.sql"
fi

echo "[seed] 완료"
