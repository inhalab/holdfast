# ERD — 도메인 모델

**holdfast** — 좌석 단위 점유 제어 · 예약·발권 시스템

`docs/design-spec.md` 3.2절이 지정한 도메인 모델을 확정한 문서다. 엔티티 목록과
컬럼은 `docs/concurrency-spec.md` 2절(`seat_inventory`)·2.2절(`seat_hold`)·
1.1절(`user_session_quota`)·6절(멱등성)에 정의된 것을 그대로 반영한다.

테이블별 REQ 번호는 `design-spec.md` 3.1절 요구사항 추적표를 기준으로 한다.

---

## 1. ERD

```mermaid
erDiagram
    program ||--o{ event_session : "회차 편성"
    seat_layout ||--o{ event_session : "배치도 적용"
    seat_layout ||--o{ zone : "구역 구성"
    zone ||--o{ seat : "좌석 배치"

    event_session ||--o{ seat_inventory : "회차별 재고 사전 생성"
    seat ||--o{ seat_inventory : "좌석별 재고"
    event_session ||--o{ seat_hold : "회차"
    seat ||--o{ seat_hold : "좌석"
    event_session ||--o{ user_session_quota : "1인 매수 집계"
    event_session ||--o{ reservation : "회차"

    reservation ||--|{ reservation_seat : "예약 좌석"
    seat_inventory ||--o{ reservation_seat : "점유 대상"
    reservation ||--o{ payment : "결제 시도"
    reservation ||--o{ outbox : "알림 발송"
    reservation |o--o| idempotency_record : "요청 멱등키"

    reservation_seat ||--o| ticket : "좌석당 1장"
    ticket ||--o{ ticket_scan : "검표 이력"

    program {
        bigint id PK
        varchar name
        text description
        timestamptz created_at
    }

    event_session {
        bigint id PK
        bigint program_id FK
        bigint seat_layout_id FK
        timestamptz starts_at "회차 시작"
        timestamptz ends_at "회차 종료"
        timestamptz entry_opens_at "입장 가능 시작"
        timestamptz entry_closes_at "입장 가능 종료"
        timestamptz reserve_opens_at "예약 오픈 일시"
        int max_per_user "1인 최대 매수 상한"
        varchar status "SCHEDULED/OPEN/CLOSED"
    }

    seat_layout {
        bigint id PK
        varchar name
        timestamptz created_at
    }

    zone {
        bigint id PK
        bigint seat_layout_id FK
        varchar name "구역명"
        int sort_order
    }

    seat {
        bigint id PK
        bigint zone_id FK
        varchar seat_no "구역 내 좌석번호"
        int row_index
        int col_index
    }

    seat_inventory {
        bigint id PK
        bigint session_id FK "복합 유니크"
        bigint seat_id FK "복합 유니크"
        varchar status "AVAILABLE/HELD/SOLD"
        varchar hold_id "현재 홀드 식별자 nullable"
        timestamptz held_until "홀드 만료 시각 nullable"
        bigint version "낙관적 락용"
    }

    seat_hold {
        bigint id PK
        bigint session_id FK "복합 유니크 - none 제외"
        bigint seat_id FK "복합 유니크 - none 제외"
        varchar hold_id "홀드 그룹 식별자"
        bigint user_id "CS-6 집계용"
        timestamptz held_until "만료 시각"
        varchar status "HELD/CONFIRMED/RELEASED"
    }

    user_session_quota {
        bigint id PK
        bigint session_id FK
        bigint user_id "사용자 식별자"
        int held_count "보유 매수"
    }

    reservation {
        bigint id PK
        bigint session_id FK
        bigint user_id "사용자 식별자"
        varchar hold_id "seat_hold 그룹 참조"
        varchar status "HELD/PENDING_PAYMENT/CONFIRMED/CANCELLED/EXPIRED"
        bigint total_amount
        timestamptz created_at
        timestamptz confirmed_at
        timestamptz cancelled_at
    }

    reservation_seat {
        bigint id PK
        bigint reservation_id FK
        bigint seat_inventory_id FK
    }

    payment {
        bigint id PK
        bigint reservation_id FK
        varchar pg_tx_id "PG 거래 ID - 콜백 멱등키"
        varchar status "REQUESTED/PAID/FAILED/CANCELLED"
        bigint amount
        timestamptz created_at
        timestamptz paid_at
    }

    ticket {
        bigint id PK
        bigint reservation_seat_id FK "예약좌석당 1장"
        varchar qr_token "QR 토큰"
        varchar status "ISSUED/USED/VOID"
        timestamptz issued_at
        timestamptz used_at
    }

    ticket_scan {
        bigint id PK
        bigint ticket_id FK
        varchar result "ADMITTED/REJECTED_DUPLICATE/REJECTED_TIME/REJECTED_INVALID"
        varchar reject_reason "거절 사유 nullable"
        timestamptz scanned_at
    }

    outbox {
        bigint id PK
        bigint reservation_id FK
        varchar notification_type "알림 종류"
        varchar status "PENDING/SENT/FAILED"
        int retry_count
        text payload
        timestamptz next_retry_at
        timestamptz created_at
        timestamptz sent_at
    }

    idempotency_record {
        bigint id PK
        varchar idempotency_key "클라이언트 UUID"
        bigint reservation_id FK "생성된 예약 nullable"
        varchar request_hash "요청 해시"
        int response_status
        text response_body "응답 본문"
        timestamptz created_at
    }
```

---

## 2. 테이블별 REQ 매핑

`design-spec.md` 3.1절 기준이다.

| 테이블 | 역할 | REQ |
|---|---|---|
| `program` | 프로그램 마스터 | REQ-02 |
| `event_session` | 회차. 입장 가능시간·예약 오픈 일시·1인 매수 상한 보유 | REQ-02, REQ-06, REQ-08 |
| `seat_layout` | 좌석배치도 | REQ-09 |
| `zone` | 배치도 내 구역 | REQ-09 |
| `seat` | 구역 내 좌석 | REQ-09 |
| `seat_inventory` | 회차 × 좌석 재고 행. 좌석 단위 점유의 경합 대상 | REQ-01, REQ-02, REQ-09 |
| `seat_hold` | 홀드 이력과 유일성. 초과 홀드의 최후 방어선 | REQ-01, REQ-03, REQ-09 |
| `user_session_quota` | `(회차, 사용자)` 단위 보유 매수 집계 | REQ-03 |
| `reservation` | 예약 헤더와 상태 | REQ-01, REQ-03, REQ-04 |
| `reservation_seat` | 예약에 속한 좌석 N건 | REQ-01, REQ-09 |
| `payment` | Mock PG 결제 시도와 결과 | REQ-04 |
| `ticket` | QR 모바일 티켓 | REQ-07 |
| `ticket_scan` | 검표 이력. 입장 가능시간·중복 사용 판정 결과 | REQ-06 |
| `outbox` | 알림 발송 큐. 재시도·중복 발송 방지 | REQ-05 |
| `idempotency_record` | 예약 요청 멱등키와 응답 재생 | REQ-03, REQ-04 |

**REQ-10(응답시간 p95 3초)에 대응하는 테이블은 없다.** 특정 테이블이 아니라 전
구간의 성능 목표이므로 `concurrency-spec.md` 7절의 측정 설계에서 다룬다.

---

## 3. 유니크 제약

| # | 대상 | 컬럼 | 종류 | 목적 | 적용 |
|---|---|---|---|---|---|
| U-1 | `seat_inventory` | `(session_id, seat_id)` | 유니크 인덱스 | 재고 행 중복 생성 방지 | **5개 전략 전부** |
| U-2 | `seat_hold` | `(session_id, seat_id)` `WHERE status = 'HELD'` | 부분 유니크 인덱스 | 활성 홀드 중복 차단 — 최후 방어선 | **`none` 제외 4개** |
| U-3 | `zone` | `(seat_layout_id, name)` | 유니크 인덱스 | 배치도 내 구역명 중복 방지 | 전부 |
| U-4 | `seat` | `(zone_id, seat_no)` | 유니크 인덱스 | 구역 내 좌석번호 중복 방지 | 전부 |
| U-5 | `user_session_quota` | `(session_id, user_id)` | 유니크 인덱스 | 집계 행 중복 생성 방지 | 전부 |
| U-6 | `reservation` | `(hold_id)` | 유니크 인덱스 | 홀드 그룹당 예약 1건 | 전부 |
| U-7 | `reservation_seat` | `(reservation_id, seat_inventory_id)` | 유니크 인덱스 | 한 예약 안의 좌석 중복 방지 | 전부 |
| U-8 | `payment` | `(pg_tx_id)` | 유니크 인덱스 | 결제 콜백 멱등성 | 전부 |
| U-9 | `ticket` | `(reservation_seat_id)` | 유니크 인덱스 | 예약좌석당 티켓 1장 | 전부 |
| U-10 | `ticket` | `(qr_token)` | 유니크 인덱스 | QR 토큰 유일성 | 전부 |
| U-11 | `ticket_scan` | `(ticket_id)` `WHERE result = 'ADMITTED'` | 부분 유니크 인덱스 | 티켓 중복 사용 차단 | 전부 |
| U-12 | `outbox` | `(reservation_id, notification_type)` | 유니크 인덱스 | 중복 발송 방지 | 전부 |
| U-13 | `idempotency_record` | `(idempotency_key)` | 유니크 인덱스 | 예약 요청 멱등성 | 전부 |

U-1·U-2의 역할 구분은 `concurrency-spec.md` 2.1절에 있다. **U-1은 초과 예약을
막지 못한다.** 재고 행은 어차피 하나뿐이고, 초과 예약은 그 하나의 행을 두 요청이
각각 UPDATE하면서 발생한다. 행의 유일성과 점유의 유일성은 다른 문제다.

### 3.1 U-2를 `none`에서만 제외하는 이유

**이 결정을 임의로 뒤집지 않는다.** 근거는 `concurrency-spec.md` 2.1절이다.

일반적으로는 제약을 항상 거는 것이 옳다. 그러나 이 프로젝트에서 `holdfast.strategy=none`은
전략이 아니라 **"락 없이 돌렸을 때 초과 예약이 몇 건 나는가"라는 실패 증거를 만드는
베이스라인**이다. 제약을 걸어두면 초과 예약이 0건으로 나와 2개월차 산출물인 실패
데이터를 얻을 수 없고, 나머지 4개 전략이 무엇을 고쳤는지 말할 근거가 사라진다.

제거 방식은 **마이그레이션 분기가 아니라 시드 스크립트의 인덱스 생성/삭제**다.
`holdfast.strategy=none`이면 측정 시작 전에 `DROP INDEX`, 그 외에는
`CREATE UNIQUE INDEX`. 스키마 파일은 하나로 유지된다.

U-1은 재고 행의 유일성이지 점유의 유일성이 아니므로 5개 전략 전부에 항상 건다.

---

## 4. 확정 기록

착수 후 논쟁이 반복되지 않도록, 명세에 없어 이 문서에서 정한 사항을 남긴다.

**티켓은 예약좌석당 1장으로 발급한다.** REQ-06의 중복 사용 차단이 좌석 단위여야
`ticket_scan`의 `(ticket_id)` 유니크 하나로 처리되고, 예약당 1장이면 "N회까지 유효"
카운터가 필요해져 그 카운터가 검표 단말 다중 스캔에서 새로운 임계 구역이 되기
때문이다 — 측정 대상과 무관한 동시성 문제를 늘리지 않는다. `design-spec.md` 3.2의
트리는 관계만 표시한 스케치이므로 발권 단위의 근거로 쓰지 않는다.

**U-2와 U-11은 부분 유니크 인덱스다.** `seat_hold`는 `status`에 `RELEASED`를 두어
홀드의 **이력**을 남기므로(`concurrency-spec.md` 2.2), 전체 행에 유니크를 걸면 홀드가
한 번 해제된 좌석을 다시 홀드할 수 없게 되어 `design-spec.md` 4.1의 "취소 시 좌석
반환"이 성립하지 않는다. 유일성과 이력이 동시에 성립하려면 부분 인덱스여야 한다.
`ticket_scan`도 같은 이유로 실패 스캔 이력을 남기되 성공 입장(`ADMITTED`)만 티켓당
1건으로 제한한다.

**U-2의 조건은 `status = 'HELD'`다. `CONFIRMED`를 포함하지 않는다.** `CONFIRMED`가
인덱스에 남으면 "판매된 좌석의 재홀드 차단"까지 이 제약이 떠맡게 되는데, 그 책임은
`seat_inventory.status`의 조건부 UPDATE에 있다(`concurrency-spec.md` 3절의 확정
쿼리). **같은 사실을 두 곳에서 지키면 어긋난다.**

측정 해석에서도 이 편이 낫다. 제약 위반 카운터가 **"앱 락이 샜다"만 세도록** 유지해야
`concurrency-spec.md` 7.1의 "정상 거절과 오류를 분리한다"가 지켜진다. 조건에
`CONFIRMED`를 넣으면 이미 팔린 좌석에 대한 정상 거절까지 같은 카운터에 섞여, 7.6
기록 양식의 제약위반 열이 무엇을 뜻하는지 흐려진다.

**`event_session`이 `seat_layout`을 참조한다.** `seat_inventory`를 회차 × 좌석 행으로
사전 생성하려면 그 회차에 어떤 좌석이 속하는지 알아야 하고, 그 출처가 배치도다.

**사용자 테이블은 만들지 않는다.** 지정된 엔티티 목록에 없으므로 `user_id`는 외래키
없는 식별자 컬럼으로 둔다. 인증·회원 관리는 이 프로젝트의 요구사항 추적표에 없다.

**예약은 홀드 시점에 생성된다.** `design-spec.md` 3.3의 예약 상태 전이가
`선점 → 결제대기 → 확정 → 취소`로 `선점` 상태를 포함하므로, `reservation`은 확정
시점이 아니라 홀드 시점에 만들어지고 `hold_id`로 `seat_hold` 그룹과 연결된다.

**홀드가 만료돼도 `reservation` 행은 지우지 않는다.** `status`를 `EXPIRED`로 전이시키고
행을 남긴다(`design-spec.md` 3.3의 `선점 → 만료`). 만료 판정의 정본은 `held_until`과
DB `now()`의 비교이며(`concurrency-spec.md` 3절), `status` 컬럼은 그 판정의 결과를
반영한 것이지 판정 근거가 아니다. 한 번의 만료로 네 곳이 함께 바뀐다.

| 대상 | 전이 |
|---|---|
| `reservation.status` | `HELD` → `EXPIRED` |
| `seat_hold.status` | `HELD` → `RELEASED` |
| `seat_inventory` | `status`를 `HELD` → `AVAILABLE`, `hold_id`·`held_until`을 `NULL` |
| `user_session_quota.held_count` | 홀드했던 좌석 수만큼 감소 |

이 전이도 `concurrency-spec.md` 5.1의 전역 락 순서(사용자 할당량 행 → 좌석 행)를 따른다.
만료된 예약에는 결제가 붙지 않으므로 `payment` 행도 생기지 않는다.

**정리되지 않은 만료 홀드는 재홀드를 막는다.** U-2가 `status = 'HELD'`에만 걸리므로,
만료됐지만 아직 `RELEASED`로 전이되지 않은 행이 남아 있으면 같은 좌석에 대한 새 홀드
INSERT가 유니크 위반으로 거절된다. 따라서 **홀드 획득 경로에서 만료된 홀드를 발견하면
그 자리에서 `RELEASED`로 전이시킨 뒤 INSERT한다.** `concurrency-spec.md` 3절의 스케줄러는
여기서도 보조이며, 스케줄러가 죽어도 정합성은 깨지지 않는다. 다만 좌석이 다시 팔릴 수
있는지는 이 정리에 의존하므로, 정리를 스케줄러에만 맡기지 않고 홀드 경로에 둔다.

**결제는 예약당 N건이다.** Mock PG의 실패·타임아웃 재시도가 이력으로 남아야 하므로
`reservation : payment`를 1:N으로 두고, 멱등성은 `pg_tx_id` 유니크(U-8)가 담당한다.

---

## 5. 참고 — 상태 전이

`design-spec.md` 3.3절과 `concurrency-spec.md` 2절에서 확정된 것이다.

```
seat_inventory : AVAILABLE ──hold──> HELD ──confirm──> SOLD
                     ^                 │                 │
                     └────expire───────┘                 │
                     └────────────cancel─────────────────┘

reservation    : HELD → PENDING_PAYMENT → CONFIRMED → CANCELLED
                 HELD → EXPIRED

ticket         : ISSUED → USED
                 ISSUED → VOID
```

`outbox`는 `SELECT ... FOR UPDATE SKIP LOCKED`로 집는다(`concurrency-spec.md` 6절).
앱 2대가 같은 행을 잡지 않으면서 한쪽이 죽어도 다른 쪽이 이어받는다.

---

## 6. 범위 밖

`design-spec.md` 4.3절 "구현하지 않음"에 해당하므로 테이블을 만들지 않는다.

| 만들지 않는 테이블 | 사유 |
|---|---|
| 정산·환불 수수료 | 실제 PG 연동·정산 제외 |
| 관리자 권한, 감사 로그 | 국립 SFR-005 / SFR-007 제외 |
| 다국어 리소스 | 다국어 제외 |
| SMS·카카오 발송 이력 | Mock 대체. `outbox`로 갈음 |
| 검표 단말 마스터 | 물리 단말 연동 제외 |
| 통계 집계 테이블 | 통계 대시보드 고도화 제외 |

`design-spec.md` 4.2절의 "여유가 되면" 항목(대기열, 비회원 조회, 노쇼 처리)도 현재
ERD에 포함하지 않는다. 착수가 결정되면 그 시점에 이 문서를 갱신한다.
