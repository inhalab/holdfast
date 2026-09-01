-- U-2 삭제 — holdfast.strategy=none 전용.
-- 베이스라인에서 이 제약을 걸어두면 초과 예약이 0건으로 나와 실패 증거를
-- 얻을 수 없다(concurrency-spec 2.1). 실패 데이터가 2개월차 산출물이다.
DROP INDEX IF EXISTS ux_seat_hold_active;
