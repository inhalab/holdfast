#!/usr/bin/env bash
# verify.sh — 초과 예약 검증(설계서 7.1: 출처는 k6가 아니라 DB 검증 쿼리).
#
# 사용: load-test/scripts/verify.sh [session_id]
set -euo pipefail

SESSION_ID="${1:-${SESSION_ID:-1}}"
DB_SERVICE="${DB_SERVICE:-db}"
DB_USER="${DB_USER:-holdfast}"
DB_NAME="${DB_NAME:-holdfast}"
SQL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../sql" && pwd)"

if ! docker compose exec -T "$DB_SERVICE" psql -U "$DB_USER" -d "$DB_NAME" -tAc \
     "SELECT to_regclass('public.seat_inventory') IS NOT NULL" | grep -q '^t$'; then
  echo "[verify] seat_inventory 테이블이 없다 — 스키마가 아직 없어 검증을 건너뛴다." >&2
  exit 3
fi

# **셀 것이 없으면 실패다**(#222).
#
# 이 스크립트가 찍는 모든 줄은 «위반 건수»이고, **데이터가 없으면 전부 0이
# 나온다.** 시드를 안 돌렸거나, SESSION_ID 를 잘못 줬거나, 재초기화가 실패한
# 경우가 **전부 「통과」로 보인다** — 존재하지 않는 회차 9999 를 줘도 초록이었다.
#
# `run-quota.sh` 와 `run-cancel.sh` 가 이미 같은 판단을 한다: *"그때의 0은
# 안 깨진 것이 아니라 밟지 못한 것이다."* 여기서도 같다.
#
# **판정 기준은 좌석 재고다.** 예약이 0인 것은 정상일 수 있지만(아무도 안
# 샀다), **재고가 0이면 그 회차는 검증 대상 자체가 없다.**
seats="$(docker compose exec -T "$DB_SERVICE" psql -U "$DB_USER" -d "$DB_NAME" -tAc \
  "SELECT COUNT(*) FROM seat_inventory WHERE session_id = $SESSION_ID" | tr -d '[:space:]')"

if [ "${seats:-0}" -eq 0 ]; then
  echo "[verify] !! 회차 $SESSION_ID 에 좌석 재고가 0건이다 — **검증이 성립하지 않는다.**" >&2
  echo "[verify]    아래 검증 쿼리는 전부 0을 찍을 텐데, 그 0은 «안 깨졌다»가" >&2
  echo "[verify]    아니라 «셀 것이 없었다»이다(#222)." >&2
  echo "[verify]    볼 곳: 시드를 돌렸나 · SESSION_ID 가 맞나 · 재초기화가 끝났나" >&2
  exit 4
fi

echo "[verify] 회차 $SESSION_ID · 좌석 재고 ${seats}건을 대상으로 본다."

docker compose exec -T "$DB_SERVICE" psql -U "$DB_USER" -d "$DB_NAME" \
  -v ON_ERROR_STOP=1 -v "session_id=$SESSION_ID" -f - < "$SQL_DIR/verify.sql"
