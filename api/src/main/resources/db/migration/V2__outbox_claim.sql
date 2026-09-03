-- 알림 Outbox 워커의 클레임(집기) 지원. REQ-05, 이슈 #78.
--
-- V1은 outbox를 "행을 쌓아 두는 큐"로만 잡았다. 워커를 붙이면서 **누가 언제
-- 집었는가**를 기록할 자리가 필요해졌다. 앱 2대에서 워커가 동시에 도는데
-- 클레임 시각이 없으면 죽은 워커가 잡고 있던 행을 되찾을 방법이 없다
-- (concurrency-spec.md 6절 — "한쪽이 죽어도 다른 쪽이 이어받는다").

-- claimed_at: 워커가 이 행을 SENDING으로 바꾼 시각.
--
-- **status의 값 집합이 하나 늘었다** — V1 주석의 PENDING/SENT/FAILED에
-- SENDING이 추가된다. 클레임과 발송을 같은 트랜잭션에 두지 않기 위해서다.
-- 발송은 외부 호출이므로(지금은 Mock이지만 계약상 그렇다) 그 동안 DB 행 락을
-- 쥐고 있으면 비관적 락이 커넥션을 쥔 채 대기하는 것과 같은 문제가 된다
-- (concurrency-spec.md 4.2). 클레임 → 발송 → 결과 기록을 세 단계로 나누고,
-- 중간 상태를 SENDING으로 표시한다.
--
-- claimed_at이 있으면 **클레임 만료**를 판정할 수 있다. 워커가 SENDING으로
-- 바꾼 뒤 죽으면 그 행은 영원히 SENDING으로 남는데, claimed_at이 충분히
-- 오래되면 다른 워커가 되찾는다. seat_inventory의 held_until과 같은 구조다 —
-- 만료된 점유를 다음 요청이 인수한다(concurrency-spec.md 4.3).
ALTER TABLE outbox ADD COLUMN claimed_at timestamptz;

-- 클레임 쿼리 전용 부분 인덱스.
--
-- 워커는 "집을 수 있는 행"만 찾는다 — PENDING이면서 재시도 시각이 지났거나,
-- SENDING인데 클레임이 만료된 행이다. SENT와 FAILED는 영원히 대상이 아니므로
-- 인덱스에서 뺀다. 발송이 끝난 행이 쌓일수록 이 차이가 커진다.
--
-- U-2(ux_seat_hold_active)와 같은 이유로 부분 인덱스다 — 조건을 인덱스에 넣어
-- 대상 집합 자체를 좁힌다(erd.md 3절).
CREATE INDEX idx_outbox_claimable
    ON outbox (next_retry_at NULLS FIRST, id)
    WHERE status IN ('PENDING', 'SENDING');
