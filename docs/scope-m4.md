# M4 최소 완결선

**무엇이 되면 '시스템'이고 무엇까지가 '연구'인가.** 이슈 #66이 M3 종료 시점에
정한 기준을 문서로 옮긴다. 동시성 연구가 선행이고 시스템 완성이 후행인 구조에서
확장 단계의 범위가 무한정 늘어나는 것을 막는 것이 목적이다.

---

## 1. 최소 완결선

> **좌석 선택 → 선점 → 결제(Mock) → QR 발급 → 검표까지 한 흐름이 화면에서 돈다.**

이 한 줄이 M4의 완료 판정이다. 여기 없는 것은 여유 항목이다.

### 흐름의 마디

```
좌석맵 화면        GET  /sessions/{id}?userId=
  ↓ 좌석을 고른다
선점               POST /api/holds              → holdId, heldUntil
  ↓
결제(Mock)         POST /api/payments {holdId}  → 승인이면 그 안에서 확정 + 발권
  ↓
티켓 확인          GET  /api/reservations/{id}/tickets
예약 확인 화면     GET  /reservations/{id}?userId=
  ↓ QR을 보여준다
검표 화면          GET  /scan
검표               POST /api/tickets/scan {qrToken} → ADMITTED
```

## 2. 확정은 결제 안에 있다

**이슈 #66은 이 흐름을 "예매 → 결제 → QR 발급 → 검표"로 적었고, 그것을 풀어 쓸
때 "선점 → 확정 → 결제"로 읽기 쉽다. 구현은 그렇지 않다.**

`PaymentService#pay`가 승인 직후 `ReservationService#confirm`을 부르고 발권까지
같은 트랜잭션에서 끝낸다(#79·#80). **확정은 결제의 한 단계이지 그 앞의 별도
단계가 아니다.**

```
✗ POST /api/holds → POST /api/reservations → POST /api/payments
                                              └─ 409 HOLD_ALREADY_CONFIRMED

✓ POST /api/holds → POST /api/payments
                     └─ 승인이면 confirm + 발권까지
```

**이 순서를 잘못 읽으면 흐름을 잘못 짠다.** 실제로 `MinimumScopeFlowTest`를 처음
쓸 때 문서 순서대로 확정을 먼저 불렀고, 네 테스트가 전부 409로 깨졌다.

### 확정 경로가 둘인 것은 의도된 설계다

| 경로 | 무엇인가 | 누가 쓰나 |
|---|---|---|
| `POST /api/reservations` | **결제 없는 확정** | M3 측정 시나리오(`load-test/scenarios/reservation.js`). 결제 지연이 섞이면 락 전략 비교가 오염된다(concurrency-spec 7.3) |
| `POST /api/payments` | **결제를 거친 확정** | 최소 완결선. 발권이 여기 붙어 있다 |

**둘 중 하나를 쓰지 둘 다 쓰지 않는다.** 결제 없이 확정하면 좌석은 팔리지만
**티켓이 없다** — 결제 없이 티켓을 주지 않는다는 뜻이고, 최소 완결선이 결제를
지나는 이유다.

`ReservationService#confirm`이 두 경로의 공통 지점이라, 알림 Outbox의 INSERT도
거기 하나만 있다(concurrency-spec 6.1).

## 3. 최소 완결에 포함되는 것

`design-spec.md` 4.1 "반드시 구현" 목록에서 위 흐름에 해당하는 것들이다.

| 항목 | REQ | 상태 |
|---|---|---|
| 좌석 맵 조회, 타인 선점 상태 반영 | REQ-02 · 09 | 완료 |
| 좌석 선점(hold)과 TTL 자동 만료 | REQ-09 · 12 | 완료 |
| 예약 확정 — 동시성 제어 적용 지점 | REQ-01 | 완료 (M3) |
| 1인 최대 매수 제한, 중복 예약 방지 | REQ-03 · 11 | 완료 |
| Mock PG (승인 / 거절) | REQ-04 | 완료 |
| QR 티켓 발급 | REQ-07 | 완료 |
| 검표: QR 검증 + 입장 가능시간 + 중복 사용 차단 | REQ-06 | 완료 |
| 취소 시 좌석 반환 | REQ-04 | 완료 |
| 알림 Mock: Outbox + 재시도 + 중복 발송 방지 | REQ-05 | 완료 |
| 예약 오픈 시각 설정 | REQ-08 | 완료 |
| 부하 테스트 하네스 | — | 완료 (M2·M3) |

검증은 `MinimumScopeFlowTest`가 흐름 전체를, 나머지는 각 도메인 테스트가 맡는다.
어느 REQ가 무엇으로 검증됐는지는 `requirements.md`의 검증 방법·상태 열에 있다.

## 4. 최소 완결선 밖 — 여유 항목

`design-spec.md` 4.1에 있으나 이 흐름에 들어가지 않는 것들이다.

- **최소 관리자 화면** — 회차 등록, 좌석배치 등록. 예약 현황 조회만 구현했다(#93)
- **Mock PG의 `TIMEOUT`·지연 주입** — 승인·거절만 구현했다(#79). 이것을
  구현하려면 결제를 트랜잭션 밖으로 빼야 하므로 **구조 변경이 따라온다.**
  아래 5절 참조
- **AWS 배포** — `infra-decision.md` 4절이 "잘라도 되는 것"으로 분류했다

4.2 "여유가 되면"(대기열, 정합성 대조 배치, 비회원 조회, 노쇼)과 4.3
"구현하지 않음"은 애초에 대상이 아니다.

## 5. 결제가 확정과 같은 트랜잭션에 있다 — 실제 PG로는 못 간다

`POST /api/payments`는 **하나의 `@Transactional` 안에서** PG 호출 → 확정 → 발권을
모두 한다(`PaymentService#pay`).

```
@Transactional pay(holdId)
├─ payment 행 준비
├─ gateway.decide()          ← 외부 호출이 들어갈 자리. 지금은 Mock이 즉답
├─ reservationService.confirm()   ← 좌석 SOLD, 홀드 CONFIRMED, 알림 INSERT
├─ ticketService.issueTickets()
└─ payment 행 저장
```

**Mock PG가 동기로 즉답하므로 지금은 문제가 드러나지 않는다.** 그 안이 전부
서브밀리초라 다른 확정 경로와 비용이 같다.

### 실제 PG였다면 이 구조는 성립하지 않는다

외부 호출이 수백 ms 걸리거나 응답이 오지 않으면 **그동안 DB 커넥션을 쥔다.**
`pessimistic` 전략이면 좌석 행 락까지 쥔 채로 기다린다 — `concurrency-spec.md`
4.2가 "대기 중 커넥션을 쥐는 것이 이 전략의 성능 특성을 지배한다"고 지목한 바로
그 상황이, **전략과 무관하게** 만들어진다.

**그때는 결제를 트랜잭션 밖에서 하고 콜백으로 확정하는 것이 맞다.**

```
POST /api/payments   → payment REQUESTED 커밋하고 응답
                       (트랜잭션 종료. 커넥션도 락도 놓는다)
     ↓ 외부 PG 호출 — 여기서 얼마가 걸리든 아무것도 쥐지 않는다
콜백 수신            → 별도 트랜잭션에서 confirm + 발권
                       U-8(pg_tx_id 유니크)이 콜백 멱등성을 보장한다
```

`state-transitions.md` 5절의 **`TIMEOUT` 상태**와 5.2의
**`callback-delay-ms` 파라미터가 원래 이것을 위한 설계다.** `TIMEOUT`은 "승인
여부를 모르는 상태"이고, 그 구간이 존재하는 것 자체가 결제를 비동기로 두었다는
뜻이다.

### 그래서 최소 완결에서 `TIMEOUT`을 뺐다

동기 Mock에는 그 상태가 쓰일 자리가 없다. 승인·거절만 구현하고(#79)
`TIMEOUT`·`FAILED`·지연 주입은 여유 항목으로 남긴 것이 같은 이유다 — **상태만
선언해 두면 "주입했는데 아무 일도 안 일어나는" 상태가 되어, 나중에 그 시나리오를
돌릴 때 값이 먹은 줄 알고 잘못 읽는다**(`MockPaymentGateway` 주석).

### M3 결론과의 관계

`concurrency-spec.md` 7.7.1은 임계 구역이 서브밀리초인 근거로 **"결제는 홀드·확정
분리로 임계 구역 밖에 있다"**를 들었다. 그 문장은 **절반만 지금도 맞다.**

| | 지금 |
|---|---|
| 결제 **화면 체류 시간** | 여전히 임계 구역 밖. 홀드만 잡고 있고 트랜잭션도 락도 없다 |
| 결제 **호출 자체** | **임계 구역 안**. 동기 Mock이라 짧아서 티가 안 날 뿐이다 |

**M3 측정값은 영향받지 않는다.** 측정 시나리오는 `POST /api/holds`와
`POST /api/reservations`만 부르고 `/api/payments`를 지나지 않는다 — M3가 잰 것은
결제 없는 확정 경로다. **결제를 포함한 경로는 아직 부하로 재지 않았다.**

실제 PG로 옮기면서 결제를 트랜잭션 밖으로 빼면 7.7.1의 문장이 다시 온전히 맞게
된다. 반대로 지금 구조 그대로 실제 PG를 붙이면 **그 문장이 틀리고, 임계 구역이
외부 시스템의 응답 시간만큼 길어진다.**

## 6. 데모는 사용자를 바꿔 가며 보여준다

**인증은 구현하지 않는다**(`design-spec.md` 4.3). 사용자 식별은 `X-User-Id`
헤더가 대신하고(`api-spec.md` 7절), 화면은 `?userId=`로 그 값을 정한다.

```
http://localhost:8080/sessions/1?userId=1     창 1
http://localhost:8080/sessions/1?userId=2     창 2 — 같은 좌석을 눌러 본다
http://localhost:8080/reservations/5?userId=2 그 사용자의 예약만 보인다
```

**고정하지 않은 이유는 시연이다.** 이 프로젝트의 서사는 "같은 좌석을 여러 사람이
동시에 노린다"인데, 화면이 한 사용자로 고정돼 있으면 **그 장면을 화면으로 만들
수 없다.** 창을 둘 띄워 같은 좌석을 누르는 것이 가장 직접적인 시연이다.

**보안이 약해지는 것은 아니다.** API 수준에서는 이미 누구나 `X-User-Id`를 바꿔
보낼 수 있다. 화면만 고정해 두는 것은 아무것도 막지 못하면서 시연만 불가능하게
한다.

**시드 범위 밖의 사용자는 좌석을 잡지 못한다.** `SeatHoldService`는
`user_session_quota` 행이 미리 있어야 홀드를 받고(concurrency-spec 1.1),
`load-test/sql/seed.sql`이 `generate_series(1, :users)`로 그 행을 만든다. 화면은
뜨지만 홀드가 실패하므로 시연 전에 시드 범위를 확인한다. 기본값 1은 어떤 시드
설정에서도 안전하다.
