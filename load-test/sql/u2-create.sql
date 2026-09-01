-- U-2 생성 — none을 제외한 4개 전략에서 활성 홀드 중복을 막는 최후 방어선.
-- 조건이 status='HELD'인 이유는 erd.md 3.1·4절에 있다. CONFIRMED를 넣으면
-- "판매된 좌석의 재홀드 차단"까지 이 제약이 떠맡아 같은 사실을 두 곳에서 지키게 된다.
CREATE UNIQUE INDEX IF NOT EXISTS ux_seat_hold_active
  ON seat_hold (session_id, seat_id)
  WHERE status = 'HELD';
