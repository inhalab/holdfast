#!/usr/bin/env bash
# seed.sh — 측정 프로토콜 1단계 "시드 초기화" (설계서 7.4).
#
# 사용:
#   load-test/scripts/seed.sh <scenario> <strategy>            # 실행 전 초기화
#   load-test/scripts/seed.sh <scenario> <strategy> --reset    # 본 측정 시작 시 재초기화
#   예) load-test/scripts/seed.sh high pessimistic
#
# 두 모드는 목적이 다르다. 기본 모드는 빈 DB에 카탈로그까지 만들고(sql/seed.sql),
# --reset은 카탈로그를 그대로 둔 채 측정 상태만 되돌린다(sql/reset.sql).
# 재초기화는 부하 한복판에서 일어나므로 TRUNCATE를 쓰지 않는다 — 이유는
# sql/reset.sql 머리말에 있다.
#
# 시나리오별 좌석 수·VU는 7.2가 못박은 값이며 여기서 바꾸지 않는다.
set -euo pipefail

SCENARIO="${1:-high}"
STRATEGY="${2:-unset}"
MODE="${3:-full}"
SESSION_ID="${SESSION_ID:-1}"
SEAT_ID_BASE="${SEAT_ID_BASE:-1}"
MAX_PER_USER="${MAX_PER_USER:-4}"

# 좌석 수와 VU는 7.2가 못박은 값이다. 사용자 풀(USERS)은 7.3 고정 변수 표가
# 시나리오별로 따로 정한 값이며 **VU 수와 다르다.**
#
# 사용자 풀이 VU 수와 같으면 저경합에서 1인 최대 매수(4매)가 좌석보다 먼저
# 바닥난다. 100명 × 4매 = 400석이라 1000석 중 600석이 손도 닿지 않은 채
# 남은 요청이 전부 QUOTA_EXCEEDED가 되고, 7.2가 저경합에서 보려는 "좌석 경합"
# 대신 "할당량 거절"을 재게 된다. scenarios/reservation.js가 사용자를 VU마다
# 다르게 두는 것도 같은 이유이며, 이 표는 그 의도를 시드까지 확장한 것이다.
case "$SCENARIO" in
  # 1000석 ÷ 4매 = 250명이 있어야 재고를 다 소화한다. 여유를 둬 400명.
  low)     SEATS=1000; VUS=100; USERS=400 ;;   # 7.2 저경합
  # 좌석이 10석뿐이라 할당량은 애초에 걸리지 않는다. 동시에 도는 VU가 같은
  # 할당량 행을 다투지 않도록(CS-6 직렬화가 좌석 경합에 섞이지 않도록) VU 수에 맞춘다.
  high)    SEATS=10;   VUS=500; USERS=500 ;;   # 7.2 고경합
  extreme) SEATS=1;    VUS=200; USERS=200 ;;   # 7.2 극단
  # 7.2.2 지속 경합. 좌석 수는 행 락 점유율로 역산한 값이며 SUSTAINED_SEATS로
  # 보정한다. **scenarios/lib/config.js의 sustained 항목과 같은 값이어야 한다** —
  # 시드가 만든 좌석보다 큰 번호를 k6가 고르면 SEAT_NOT_IN_SESSION이 섞인다.
  # 보정은 pessimistic 한 전략에서만 하고 그 값을 고정한다(7.2.2).
  sustained) SEATS="${SUSTAINED_SEATS:-3}"; VUS=500; USERS=500 ;;
  *) echo "SCENARIO는 low|high|extreme|sustained 중 하나여야 한다: $SCENARIO" >&2; exit 2 ;;
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

# --- 본 측정 시작 시 재초기화 (7.4-3) ---------------------------------------
#
# 부하 중이라 행 락 경합으로 실패할 수 있다. lock_timeout 5초로 끊고 재시도한다.
# **조용히 실패하면 안 된다** — 재초기화가 안 된 채 측정이 이어지면 매진 상태를
# 재고 있으면서 숫자는 그럴듯하게 나온다. 다 실패하면 0이 아닌 코드로 끝내
# 호출자가 그 회차를 버릴 수 있게 한다.
if [ "$MODE" = "--reset" ]; then
  ATTEMPTS="${RESET_ATTEMPTS:-5}"
  for attempt in $(seq 1 "$ATTEMPTS"); do
    if psql_run -v "session_id=$SESSION_ID" -f - < "$SQL_DIR/reset.sql" >/dev/null 2>&1; then
      echo "[seed] 재초기화 완료 (${attempt}회 시도)"
      exit 0
    fi
    echo "[seed] 재초기화 ${attempt}회차 실패 — 부하 중 행 락 경합. 재시도한다." >&2
  done
  echo "[seed] !! 재초기화가 ${ATTEMPTS}회 모두 실패했다. 이 회차의 숫자는 쓰지 않는다." >&2
  exit 4
fi

echo "[seed] 시나리오=$SCENARIO 좌석=$SEATS VU=$VUS 사용자풀=$USERS 전략=$STRATEGY"
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
