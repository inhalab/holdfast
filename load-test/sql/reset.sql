-- reset.sql — **본 측정 시작 시 재초기화** (설계서 7.4-3).
--
-- seed.sql과 목적이 다르다. seed.sql은 아무것도 없는 DB에 카탈로그(프로그램·
-- 배치도·구역·좌석·회차)와 측정 상태를 함께 만든다. 이 스크립트는 카탈로그를
-- 그대로 두고 **측정 상태만** 출발선으로 되돌린다.
--
-- 사용 (scripts/seed.sh --reset 이 감싸서 호출한다):
--   psql -v session_id=1 -f reset.sql
--
-- ## TRUNCATE를 쓰지 않는 이유
--
-- seed.sql은 `TRUNCATE ... RESTART IDENTITY CASCADE`로 초기화한다. 부하가 없는
-- 실행 전에는 그것으로 충분하지만, **재초기화는 500 VU가 때리는 한복판에서
-- 일어난다.** TRUNCATE는 테이블마다 ACCESS EXCLUSIVE 락을 차례로 잡는데, 진행
-- 중인 앱 트랜잭션은 이미 다른 테이블에 AccessShareLock을 쥐고 있어 서로를
-- 기다리는 순환이 만들어진다. 실측에서 이렇게 터졌다.
--
--   ERROR:  deadlock detected
--   DETAIL: Process 1144 waits for AccessExclusiveLock on relation 16532;
--           blocked by process 54.
--           Process 54 waits for AccessShareLock on relation 16485;
--           blocked by process 1144.
--
-- 시드 트랜잭션이 통째로 롤백되면 재고가 매진 상태로 남고, 측정 구간은
-- 재초기화 이전과 똑같아진다(고경합 1회차 성공 0건). **재초기화가 조용히
-- 실패하는 것이 이 단계에서 가장 위험하다** — 숫자는 그럴듯하게 나오는데
-- 측정한 것이 다른 상태이기 때문이다.
--
-- 행 단위 DELETE/UPDATE는 RowExclusiveLock만 잡아 앱의 AccessShareLock과
-- 충돌하지 않는다. 행 락 경합은 남지만 범위가 좁고 lock_timeout으로 끊는다.

\set ON_ERROR_STOP on

BEGIN;

-- 재초기화가 앱을 오래 세우면 그 정지가 측정 구간의 응답시간에 그대로 남는다.
-- 기다리다 실패하는 편이 낫다 — 호출자가 재시도한다(scripts/seed.sh).
SET LOCAL lock_timeout = '5s';

-- 자식 테이블부터. FK 참조가 남아 있으면 부모 삭제가 실패한다.
DELETE FROM ticket_scan;
DELETE FROM ticket;
DELETE FROM outbox;
DELETE FROM idempotency_record;
DELETE FROM payment;
DELETE FROM reservation_seat;
DELETE FROM reservation;
DELETE FROM seat_hold;

-- 재고를 출발선으로. seed.sql이 만드는 초기 상태와 같은 값이다.
UPDATE seat_inventory
   SET status = 'AVAILABLE', hold_id = NULL, held_until = NULL, version = 0
 WHERE session_id = :session_id;

-- 1인 최대 매수 집계도 되돌린다. 이것을 빠뜨리면 워밍업이 쓴 보유 매수가
-- 남아 측정 구간이 좌석 경합 대신 할당량 거절을 재게 된다(7.3).
UPDATE user_session_quota
   SET held_count = 0
 WHERE session_id = :session_id;

COMMIT;

-- U-2 인덱스는 건드리지 않는다. 전략에 따라 켜고 끄는 값이며 실행 중에
-- 바뀌지 않는다(scripts/seed.sh가 실행 전에 한 번 정한다).
