# 요구사항 추적표

**holdfast** — 좌석 단위 점유 제어 예약·발권 시스템

`docs/design-spec.md` 3.1절의 REQ-01~10 초안을 확정본으로 옮기고, 착수 후 설계
과정에서 확정된 내용을 반영해 보강했다. REQ-11부터는 설계 중 새로 확정됐지만
원본 3.1절에 없던 항목이다.

**이 표에 없는 기능은 만들지 않는다.** `design-spec.md` 3.1절이 이미 명시한
원칙이며, REQ-11·12를 추가한 이후에도 그대로 유지한다.

## 표 읽는 법

| 열 | 의미 |
|---|---|
| 대응 테이블 | `docs/erd.md` 2절 기준. 이 요구사항이 만족되는지 DB 상태로 확인할 수 있는 테이블 |
| 대응 엔드포인트 | `docs/api-spec.md` 1절 기준. 클라이언트가 이 요구사항을 만족시키기 위해 호출하는 API |
| 검증 방법 | `docs/concurrency-spec.md` 8절 기준. **실제로 존재하는 검증만 적는다** — 아래 2절 참조 |
| 검수 기준 | 정량 목표가 있는 요구사항만 채운다. 없으면 `—` |
| 상태 | M3 종료 시점. **충족** / **부분** / **구현·미검증** / **미구현** |

---

## 1. 요구사항 추적표

| REQ | 요구사항 | 출처 | 대응 테이블 | 대응 엔드포인트 | 검증 방법 | 검수 기준 | 상태 |
|---|---|---|---|---|---|---|---|
| REQ-01 | 동시 예약 요청 시 정원·좌석 초과 확정 방지 | 국립 SFR-001 | `seat_inventory`, `seat_hold`, `reservation`, `reservation_seat` | `POST /api/holds`, `POST /api/reservations` | 단위 경합 테스트 5종(`*SeatHoldStrategyConcurrencyTest`) + 부하 측정 60회 + DB 검증 V-1 | **초과 예약 0건** | **충족** — `none` 제외 4개 전략 V-1 0(각 9회 전부). `none`은 고경합 4석으로 실패 증거를 냈다 |
| REQ-02 | 실시간 잔여 좌석 검증 및 표시 | 국립 SFR-001, SFR-006 | `program`, `event_session`, `seat_inventory` | `GET /api/sessions/{id}/seats`, `GET /api/sessions/{id}/seats/status` | `SeatMapPageControllerTest`(렌더) | — | **부분** — 조회 API·`ETag`/304·htmx fragment는 구현됐고 렌더 테스트가 있다. **폴링 부하는 측정에 넣지 않았다**(`api-spec.md` 8.1) |
| REQ-03 | 중복 예약 방지 / 1인 최대 매수 제한 | 국립 SFR-001 | `seat_hold`, `user_session_quota`, `reservation`, `idempotency_record` | `POST /api/holds`, `POST /api/reservations` (둘 다 `Idempotency-Key` 필수) | 부하 측정 + DB 검증 V-3 | — | **충족(부하 측정 기준)** — 60회 전부 V-3 0. 전용 단위 경합 테스트는 없다(REQ-11 참조) |
| REQ-04 | 예약·결제 상태 정합성 검증 | 국립 SFR-002 | `reservation`, `payment`, `idempotency_record` | `POST /api/reservations`, `GET /api/reservations/{id}`, `POST /api/reservations/{id}/cancel` | DB 검증 V-4(재고-예약 불일치)가 예약 축만 덮는다 | — | **부분** — 예약 축은 60회 전부 V-4 0. **결제 축은 미검증**이다. Mock PG가 M4 항목이라 `payment` 행이 생기지 않는다(`design-spec.md` 6절) |
| REQ-05 | 알림 발송 재시도 및 중복 발송 방지 | 국립 SFR-003 | `outbox` | 없음 — Outbox는 서버 내부 워커이며 외부 API가 아니다 (`api-spec.md` 7절) | 없음 | — | **미구현** — M4 항목. 스키마와 U-12만 있다 |
| REQ-06 | 회차별 입장 가능시간 검증 및 검표 처리 | 국립 SFR-004 | `event_session`, `ticket_scan` | 없음 — 검표 엔드포인트는 별도 계약 (`api-spec.md` 7절, 4개월차 작업) | 없음 | — | **미구현** — M4 항목. 스키마와 U-11만 있다 |
| REQ-07 | QR 모바일 티켓 발급 | 궁능 SFR-03 | `ticket` | 없음 — 발급 엔드포인트는 별도 계약 (`api-spec.md` 7절, 4개월차 작업) | 없음 | — | **미구현** — M4 항목. 스키마와 U-9·U-10만 있다 |
| REQ-08 | 예약 오픈 일시 설정 | 궁능 SFR-05 | `event_session` | `GET /api/sessions/{id}/seats` (`reserveOpensAt` 필드), `POST /api/holds` (`RESERVATION_NOT_OPEN` 거절) | 없음 | — | **구현·미검증** — `SeatHoldService`가 `RESERVATION_NOT_OPEN`을 던지고 `classify.js`가 정상 거절로 분류하지만, 오픈 전 시각을 만들어 확인하는 테스트가 없다 |
| REQ-09 | 좌석 지정 선택 및 좌석 단위 점유 | 자체 확장 | `seat_layout`, `zone`, `seat`, `seat_inventory`, `seat_hold`, `reservation_seat` | `GET /api/sessions/{id}/seats`, `GET .../seats/status`, `POST /api/holds`, `DELETE /api/holds/{holdId}` | 단위 경합 테스트 5종 + 부하 측정 + DB 검증 V-2·V-4 | — | **충족** — 60회 전부 V-2 0 · V-4 0 |
| REQ-10 | 응답시간 p95 3초 이내 | 국립 PER-002 | 없음 — 특정 테이블이 아니라 전 구간의 성능 목표 (`erd.md` 2절) | 전체 엔드포인트 공통 | **부하 측정 (k6, 정본)** | **p95 3초 이내** | **충족** — 다섯 전략 **18~41ms**(상한의 1.4% 이하). 단 k6가 앱과 같은 호스트에서 돈다는 제약을 함께 읽는다(`concurrency-spec.md` 7.3) |
| REQ-11 | 1인 최대 매수 검사는 좌석 단위 락과 독립적으로 직렬화되어야 한다 (좌석 락으로는 막히지 않는다) | 자체 확장 — `concurrency-spec.md` 1.1절 (CS-6) | `user_session_quota` | `POST /api/holds` (`QUOTA_EXCEEDED` 거절) | 부하 측정 + DB 검증 V-3 | **상한 초과 승인 0건** | **충족(부하 측정 기준)** — 60회 전부 V-3 0. **CS-6 전용 단위 경합 테스트는 없다** — 전략 테스트는 `user_session_quota` 행을 시드만 하고 상한을 겨루지 않는다 |
| REQ-12 | 만료 홀드 정리와 재홀드 획득이 동시에 발생해도 제약 위반 카운터가 오염되지 않아야 한다 | 자체 확장 — `erd.md` 4.1절 | `seat_hold`, `seat_inventory`, `reservation`, `user_session_quota` | `POST /api/holds` (재홀드 시 정리 절차 수행) | `optimistic`의 단위 경합 테스트 2종(`takesOverExpiredHold`, `concurrentTakeoverOfExpiredHoldLetsOnlyOneWin`) + 부하 측정(제약 위반 카운터) | `unique` 제외 전략에서 **제약 위반 0건** (`concurrency-spec.md` 7.6) | **부분** — 부하 측정에서는 충족(`unique` 제외 3개 전략 제약 위반 0). **만료·재홀드 동시 경쟁의 단위 재현은 `optimistic`에만 있다** |

---

## 2. 검증 방법 열을 실제로 있는 것으로 고쳤다

**이 표는 한때 REQ-02·04·05·06·07·08의 검증 방법을 "통합 테스트"로 적었다.
그런 테스트는 존재하지 않는다.**

`concurrency-spec.md` 8절이 "단위 경합 테스트 / 통합 테스트(Testcontainers) /
부하 측정" 세 계층을 나눠 적었고 이 표가 그것을 그대로 옮겼는데, 실제로 만든 것은
**Testcontainers로 진짜 Postgres·Redis를 띄우는 단위 경합 테스트** 하나다. 둘을
나눠 적으면 없는 계층이 있는 것처럼 읽히고, **아직 아무도 검증하지 않은
요구사항이 검증된 것처럼 보인다.** REQ-05·06·07은 코드가 아예 없는데도 "통합
테스트"라고 적혀 있었다.

현재 존재하는 검증 수단은 넷이다.

| 수단 | 대상 | 어디에 |
|---|---|---|
| 단위 경합 테스트 5종 | 전략별 N스레드 동시 홀드·확정 | `api/src/test/.../*SeatHoldStrategyConcurrencyTest` |
| 화면 렌더 테스트 | 좌석맵 페이지 | `SeatMapPageControllerTest` |
| 부하 측정 | 홀드·확정 경로, 앱 2대 | `load-test/scenarios/reservation.js`, 60회 |
| DB 검증 쿼리 | V-1~V-6 | `load-test/sql/verify.sql` |

**검증되지 않은 것은 그렇게 적는다.** "미구현"과 "구현했지만 검증 수단이 없음"은
다르며, 뒤엣것(REQ-08)이 더 위험하다 — 동작한다고 믿고 넘어가기 쉽다.

**M4에서 메울 것은 두 가지다.** 첫째, 기능 자체가 없는 REQ-05·06·07(그리고
REQ-04의 결제 축). 둘째, **기능은 있는데 검증이 없는 REQ-08과, 부하 측정에만
기대고 있는 REQ-03·11이다.** V-3이 60회 내내 0인 것은 강한 증거지만, 상한을
실제로 겨루는 단위 테스트가 있어야 회귀를 잡는다.

---

## 3. REQ-11·12를 추가한 이유

두 항목 모두 원본 3.1절이 나열한 10개 요구사항 중 어디에도 정확히 들어맞지 않는다.
**존재하는 REQ 아래에 끼워 넣으면 이 항목들이 요구하는 별도의 검증(단위 경합
테스트)이 표에서 보이지 않게 된다.**

**REQ-11 (CS-6)이 REQ-03과 다른 이유.** REQ-03의 "1인 최대 매수 제한"은 기능
요구사항(그런 제한이 존재해야 한다)이고, REQ-11은 그 제한을 **어떻게 깨지지 않게
지키느냐**에 대한 동시성 요구사항이다. `concurrency-spec.md` 1.1절이 명시하듯, 같은
사용자의 요청 두 개가 서로 다른 좌석을 대상으로 동시에 들어오면 좌석 단위 락은 이
경로를 전혀 막지 못한다. REQ-03만 표에 있으면 "제한이 있다"는 확인되지만 "그 제한이
동시 요청에서도 지켜지는가"는 별도로 검증되지 않은 채 넘어갈 수 있다.

**REQ-12 (erd.md 4.1)가 새 REQ인 이유.** 이 요구사항은 사용자 대면 기능이 아니라
**측정 무결성 요구사항**이다. 만료된 홀드를 정리하는 절차와 새 홀드를 잡는 절차가
분리돼 있으면, 두 요청이 같은 만료 행을 동시에 발견했을 때 제약 위반이 발생하고 그
카운터가 "앱 락이 샜다"는 신호와 섞인다(`concurrency-spec.md` 7.1·7.6). 이 요구사항이
깨지면 락 전략 비교표 자체의 신뢰도가 무너지므로, 눈에 띄지 않는 하위 항목으로 묻어둘
수 없다.

**CS-4(취소 시 좌석 반환)는 새 REQ로 추가하지 않았다.** `concurrency-spec.md` 1절의
임계 구역 표에는 있지만, CS-6·erd 4.1과 달리 별도의 확정된 해결 절차 문서가 없다.
이중 반환 방지는 REQ-04(예약·결제 상태 정합성 검증)의 취소 흐름 안에서 이미 다뤄지는
것으로 본다. `POST /api/reservations/{id}/cancel`이 멱등하게 설계된 것(재취소 시 200과
기존 결과 반환, `api-spec.md` 6.1절)이 그 처리다. 별도 REQ가 필요하다고 판단되면 이후
갱신한다.

---

## 4. 범위 밖

`design-spec.md` 4.3절 "구현하지 않음"에 해당하는 항목은 애초에 REQ로 만들지
않았다. 실제 PG 연동·정산, 관리자 권한 세분화·감사 로그, 다국어·웹접근성 인증,
실제 SMS·카카오 발송, 물리 검표 단말 연동, 통계 대시보드 고도화가 이에 해당한다.
사유는 `design-spec.md` 4.3절 표에 있다.

`design-spec.md` 4.2절 "여유가 되면" 항목(대기열, 예약↔결제 정합성 대조 배치,
비회원 예약 조회, 노쇼 처리)도 착수가 결정되기 전까지는 REQ로 만들지 않는다.
착수가 결정되면 이 표를 갱신한다.
