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

docker compose exec -T "$DB_SERVICE" psql -U "$DB_USER" -d "$DB_NAME" \
  -v ON_ERROR_STOP=1 -v "session_id=$SESSION_ID" -f - < "$SQL_DIR/verify.sql"
