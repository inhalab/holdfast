# 요구사항 추적표

**holdfast** — 좌석 단위 점유 제어 예약·발권 시스템

`docs/design-spec.md` 3.1절의 REQ-01~10 초안을 확정본으로 옮기고, 착수 후 설계
과정에서 확정된 내용을 반영해 보강했다. REQ-11부터는 설계 중 새로 확정됐지만
원본 3.1절에 없던 항목이다.

**이 표에 없는 기능은 만들지 않는다.** `design-spec.md` 3.1절이 이미 명시한
원칙이며, REQ-11·12·13·14를 추가한 이후에도 그대로 유지한다.

**그 원칙은 반대 방향으로도 읽는다.** 만든 기능에 대응하는 REQ가 없으면 표가
거짓이 된다 — REQ-13이 그렇게 뒤늦게 들어왔다(3절).

## 표 읽는 법

| 열 | 의미 |
|---|---|
| 대응 테이블 | `docs/erd.md` 2절 기준. 이 요구사항이 만족되는지 DB 상태로 확인할 수 있는 테이블 |
| 대응 엔드포인트 | `docs/api-spec.md` 1절 기준. 클라이언트가 이 요구사항을 만족시키기 위해 호출하는 API |
| 검증 방법 | `docs/concurrency-spec.md` 8절 기준. **실제로 존재하는 검증만 적는다** — 아래 2절 참조 |
| 검수 기준 | 정량 목표가 있는 요구사항만 채운다. 없으면 `—` |
| 상태 | **충족** / **부분** / **구현·미검증** / **미구현**. M3 종료 시점 기준이며, M4에서 갱신된 항목은 그 근거를 함께 적는다 |

---

## 1. 요구사항 추적표

| REQ | 요구사항 | 출처 | 대응 테이블 | 대응 엔드포인트 | 검증 방법 | 검수 기준 | 상태 |
|---|---|---|---|---|---|---|---|
| REQ-01 | 동시 예약 요청 시 정원·좌석 초과 확정 방지 | 국립 SFR-001 | `seat_inventory`, `seat_hold`, `reservation`, `reservation_seat` | `POST /api/holds`, `POST /api/reservations` | 단위 경합 테스트 5종(`*SeatHoldStrategyConcurrencyTest`) + 부하 측정 60회 + DB 검증 V-1 | **초과 예약 0건** | **충족** — `none` 제외 4개 전략 V-1 0(각 9회 전부). `none`은 고경합 4석으로 실패 증거를 냈다 |
| REQ-02 | 실시간 잔여 좌석 검증 및 표시 | 국립 SFR-001, SFR-006 | `program`, `event_session`, `seat_inventory` | `GET /api/sessions/{id}/seats`, `GET /api/sessions/{id}/seats/status` | `SeatMapPageControllerTest`(렌더) + 단위 흐름 테스트 `MinimumScopeFlowTest`(좌석맵 렌더 + AVAILABLE→HELD→SOLD 전이) | — | **부분** — 조회 API·`ETag`/304·htmx fragment가 구현됐고 상태 전이가 화면에 반영되는 것까지 확인했다. **폴링 부하는 측정에 넣지 않았다**(`api-spec.md` 8.1). 접수종료 노출 정책은 `design-spec` 5.6에 정하고 `catalog/SaleState`로 구현했다(#108) |
| REQ-03 | 중복 예약 방지 / 1인 최대 매수 제한 | 국립 SFR-001 | `seat_hold`, `user_session_quota`, `reservation`, `idempotency_record` | `POST /api/holds`, `POST /api/reservations` (둘 다 `Idempotency-Key` 필수) | 부하 측정 + DB 검증 V-3 | — | **충족(부하 측정 기준)** — 60회 전부 V-3 0. 전용 단위 경합 테스트는 없다(REQ-11 참조) |
| REQ-04 | 예약·결제 상태 정합성 검증 | 국립 SFR-002 | `reservation`, `payment`, `idempotency_record` | `POST /api/reservations`, `GET /api/reservations/{id}`, `POST /api/reservations/{id}/cancel` | DB 검증 V-4(재고-예약 불일치)가 예약 축만 덮는다 + 단위 흐름 테스트 `MinimumScopeFlowTest`(승인·거절·재시도 경로, **취소가 결제 이력을 바꾸지 않는 것과 재취소 멱등** — #106) | — | **충족** — 승인 시 예약 `CONFIRMED`, 거절 시 `HELD` 유지, 재시도가 새 `payment` 행을 만드는 것까지 확인했다. 콜백·`TIMEOUT`은 여유 항목이라 미구현 |
| REQ-05 | 알림 발송 재시도 및 중복 발송 방지 | 국립 SFR-003 | `outbox` | 없음 — Outbox는 서버 내부 워커이며 외부 API가 아니다 (`api-spec.md` 7절) | 단위 경합 테스트 `OutboxConcurrencyTest` — 워커 8개 동시 실행 / 재시도 / 상한 소진 / 확정 트랜잭션과의 원자성 + 단위 흐름 테스트 `MinimumScopeFlowTest`(확정 트랜잭션이 `outbox` 행을 넣는지) | **중복 발송 0건** | **충족** — 알림 200건이 정확히 한 번씩 발송된다. 확정 시 INSERT는 확정과 같은 트랜잭션이며 롤백으로 확인한다(이슈 #78, `concurrency-spec.md` 6.1) |
| REQ-06 | 회차별 입장 가능시간 검증 및 검표 처리 | 국립 SFR-004 | `event_session`, `ticket_scan` | `POST /api/tickets/scan`, `GET /scan`(검표 화면) | 단위 흐름 테스트 `MinimumScopeFlowTest`(입장 창 밖 스캔 `REJECTED_TIME`, 중복 스캔 `REJECTED_DUPLICATE`) | **중복 사용 0건** | **충족** — U-11이 티켓당 `ADMITTED` 1건으로 제한하고 거절은 이력으로 남는다 |
| REQ-07 | QR 모바일 티켓 발급 | 궁능 SFR-03 | `ticket` | `GET /api/reservations/{id}/tickets`, `GET /reservations/{id}`(예약 확인 화면) | 단위 흐름 테스트 `MinimumScopeFlowTest`(발급 → 조회 → 스캔) | — | **충족** — 결제 승인과 같은 트랜잭션에서 발급되고, 화면과 API로 조회되며, 스캔에 쓰인다 |
| REQ-08 | 예약 오픈 일시 설정 | 궁능 SFR-05 | `event_session` | `GET /api/sessions/{id}/seats` (`reserveOpensAt` 필드), `POST /api/holds` (`RESERVATION_NOT_OPEN` 거절) | 단위 흐름 테스트 `MinimumScopeFlowTest`(오픈 전 홀드가 409 `RESERVATION_NOT_OPEN`) | — | **충족** — 거절이 상태를 남기지 않는 것까지 확인했다 |
| REQ-09 | 좌석 지정 선택 및 좌석 단위 점유 | 자체 확장 | `seat_layout`, `zone`, `seat`, `seat_inventory`, `seat_hold`, `reservation_seat` | `GET /api/sessions/{id}/seats`, `GET .../seats/status`, `POST /api/holds`, `DELETE /api/holds/{holdId}` | 단위 경합 테스트 5종 + 부하 측정 + DB 검증 V-2·V-4 | — | **충족** — 60회 전부 V-2 0 · V-4 0 |
| REQ-10 | 응답시간 p95 3초 이내 | 국립 PER-002 | 없음 — 특정 테이블이 아니라 전 구간의 성능 목표 (`erd.md` 2절) | 전체 엔드포인트 공통 | **부하 측정 (k6, 정본)** + **화면별 측정** (#105, `load-test/scenarios/pages.js`) — API의 p95와 화면의 응답시간은 **재는 대상이 다르므로** 둘 다 있어야 한다 | **p95 3초 이내** | **충족** — API는 다섯 전략 **18~41ms**(상한의 1.4% 이하), **화면은 13개 전부 3초 이내이고 가장 느린 것이 45.9ms**(1.5%)다. 화면 값은 무부하와 부하 중 두 조건에서 쟀다. 단 k6가 앱과 같은 호스트에서 돈다는 제약과, 화면 쪽은 브라우저 렌더링을 재지 않는다는 제약을 함께 읽는다(`concurrency-spec.md` 7.3 · `results/per-002-page-timing.md` 4.4) |
| REQ-11 | 1인 최대 매수 검사는 좌석 단위 락과 독립적으로 직렬화되어야 한다 (좌석 락으로는 막히지 않는다) | 자체 확장 — `concurrency-spec.md` 1.1절 (CS-6) | `user_session_quota` | `POST /api/holds` (`QUOTA_EXCEEDED` 거절) | 부하 측정 + DB 검증 V-3 | **상한 초과 승인 0건** | **충족(부하 측정 기준)** — 60회 전부 V-3 0. **CS-6 전용 단위 경합 테스트는 없다** — 전략 테스트는 `user_session_quota` 행을 시드만 하고 상한을 겨루지 않는다 |
| REQ-12 | 만료 홀드 정리와 재홀드 획득이 동시에 발생해도 제약 위반 카운터가 오염되지 않아야 한다 | 자체 확장 — `erd.md` 4.1절 | `seat_hold`, `seat_inventory`, `reservation`, `user_session_quota` | `POST /api/holds` (재홀드 시 정리 절차 수행) | `optimistic`의 단위 경합 테스트 2종(`takesOverExpiredHold`, `concurrentTakeoverOfExpiredHoldLetsOnlyOneWin`) + 부하 측정(제약 위반 카운터) | `unique` 제외 전략에서 **제약 위반 0건** (`concurrency-spec.md` 7.6) | **부분** — 부하 측정에서는 충족(`unique` 제외 3개 전략 제약 위반 0). **만료·재홀드 동시 경쟁의 단위 재현은 `optimistic`에만 있다** |
| REQ-13 | 관리자가 SQL 없이 프로그램·회차·좌석배치를 만들고 고칠 수 있어야 하며, **그 조작이 판매 중인 데이터를 깨뜨리지 않아야 한다**. 또한 **회차별 판매 현황을 SQL 없이 볼 수 있어야 한다** | 국립 SFR-005 | `program`, `event_session`, `seat_layout`, `zone`, `seat`, `seat_inventory`, `user_session_quota` | `/admin/programs`, `/admin/programs/{id}`, `/admin/programs/{id}/sessions`, `/admin/sessions/{id}`, `/admin/sessions/{id}/delete`, `/admin/layouts`, `/admin/layouts/{id}`, `/admin/reservations` — 전부 서버렌더 화면이며 JSON API가 아니다 | `AdminCatalogFlowTest`(30) + `AdminSeatLayoutFlowTest`(16) + `AdminSessionStatsTest`(7) | `erd.md` 2.2의 시각 규칙 **T-1~T-4**와 상태·데이터 규칙 **S-1~S-6**을 어기는 입력이 저장되지 않는다. 현황 통계는 **잔여석을 회차 목록과 같은 정의로 센다**(#104) | **부분** — 등록·수정·삭제와 그 제약은 충족(#101·#102·#123), 회차별 현황 통계도 충족(#104). **권한 관리·수정 이력이 없다** — 인증이 없어 이 경로를 누구나 연다(`rfp-scope.md` 3장 SFR-005) |
| REQ-14 | **비회원·마이페이지 예약 조회.** 예약번호와 예약자 번호로 자기 예약을 찾을 수 있어야 하며, **그 조회가 기존 소유권 검사를 우회하지 않아야 한다** | 국립 SFR-006, PER-002 (2.3 화면 목록의 "비회원 예약 조회 및 마이페이지 조회 화면") | `reservation`, `reservation_seat`, `ticket` | `/reservations/lookup`(비회원 조회), `/my/reservations`(마이페이지), `/reservations/{id}`(상세 — 기존) | `ReservationLookupFlowTest`(14) | **정량 기준을 세울 수 없다 — 아래 3절** | **부분** — 두 화면이 `ReservationService#get`의 소유권 검사를 **우회 없이 그대로 탄다**(테스트 `rejectsWhenOwnerDiffers`). **다만 예약자 번호는 추측할 수 있어 남의 예약을 막지 못한다** — 막으려면 인증이 필요하고 인증은 배제했다(`rfp-scope.md` 4.3) |

---

## 2. 검증 방법 열을 실제로 있는 것으로 고쳤다

**이 표는 한때 REQ-02·04·05·06·07·08의 검증 방법을 "통합 테스트"로 적었다.
그런 테스트는 존재하지 않는다.**

`concurrency-spec.md` 8절이 "단위 경합 테스트 / 통합 테스트(Testcontainers) /
부하 측정" 세 계층을 나눠 적었고 이 표가 그것을 그대로 옮겼는데, 실제로 만든 것은
**Testcontainers로 진짜 Postgres·Redis를 띄우는 단위 경합 테스트** 하나다. 둘을
나눠 적으면 없는 계층이 있는 것처럼 읽히고, **아직 아무도 검증하지 않은
요구사항이 검증된 것처럼 보인다.** REQ-05·06·07은 그 시점에 코드가 아예 없는데도
"통합 테스트"라고 적혀 있었다. (REQ-05는 이후 이슈 #78로 구현·검증됐다.)

현재 존재하는 검증 수단은 여섯이다.

| 수단 | 대상 | 어디에 |
|---|---|---|
| 단위 경합 테스트 5종 | 전략별 N스레드 동시 홀드·확정 | `api/src/test/.../*SeatHoldStrategyConcurrencyTest` |
| 화면 렌더 테스트 | 좌석맵 페이지 | `SeatMapPageControllerTest` |
| **단위 흐름 테스트** | **최소 완결선** — 선점 → 결제 → QR → 검표, 실패 경로 넷 | `api/src/test/.../MinimumScopeFlowTest` |
| 단위 경합 테스트 (알림) | 워커 동시 실행·재시도·상한·확정과의 원자성 | `api/src/test/.../OutboxConcurrencyTest` |
| 부하 측정 | 홀드·확정 경로, 앱 2대 | `load-test/scenarios/reservation.js`, 60회 |
| DB 검증 쿼리 | V-1~V-6 | `load-test/sql/verify.sql` |

**검증되지 않은 것은 그렇게 적는다.** "미구현"과 "구현했지만 검증 수단이 없음"은
다르며, 뒤엣것(REQ-08)이 더 위험하다 — 동작한다고 믿고 넘어가기 쉽다.

**M4에서 메울 것은 두 가지였다.** 첫째, 기능 자체가 없던 REQ-06·07(그리고
REQ-04의 결제 축) — **구현하고 `MinimumScopeFlowTest`로 검증했다.** 둘째, 기능은
있는데 검증이 없던 REQ-08 — **같은 테스트가 덮었다.**

**남은 것은 부하 측정에만 기대고 있는 REQ-03·11이다.** V-3이 60회 내내 0인 것은 강한 증거지만, 상한을
실제로 겨루는 단위 테스트가 있어야 회귀를 잡는다.

---

## 3. REQ-11·12·13·14를 추가한 이유

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

**REQ-13 (관리자 운영)이 뒤늦게 들어온 이유.** 앞의 둘과 반대다 — 11·12는
검증을 먼저 정하고 REQ를 세웠는데, **13은 기능과 검증이 먼저 생기고 REQ가 없었다.**

`#93`·`#101`·`#102`·`#123`이 관리자 화면을 만드는 동안 테스트 46건과
`erd.md` 2.2의 규칙 열 개(T-1~T-4, S-1~S-6)가 함께 쌓였다. **검증은 있는데
요구사항이 없는 상태**였고, 그것은 이 문서의 첫 원칙("이 표에 없는 기능은 만들지
않는다")을 뒤에서 무너뜨린다.

**REQ-13이 SFR-005의 어느 축인지가 요점이다.** 원문 SFR-005는 "관리자 운영 편의 및
업무처리 기능 강화"이고 검수 기준이 둘이다 — "표본 업무 시나리오대로 수행"과
"권한 없는 정보 미조회". **앞의 것만 REQ-13이 맡는다.** 뒤의 것은 인증이 있어야
하고 인증은 넣지 않기로 했으므로(`rfp-scope.md` 4.3), 상태를 **부분**으로 둔다.

**"그 조작이 판매 중인 데이터를 깨뜨리지 않아야 한다"가 이 REQ의 무게중심이다.**
화면을 만드는 것만이면 검증할 것이 렌더뿐이지만, 실제로 값을 치른 것은 그쪽이
아니다 — 시각이 뒤집힌 회차가 저장되고(T-1~T-4), 예약이 있는 회차가 되돌려지고
(S-2), 판매된 회차의 예약 오픈이 미래로 밀리는(S-3) 것을 막는 데 있다.

**REQ-14 (예약 조회)는 검수 기준을 세울 수 없다.** 이 표에서 검수 기준 열이
"세울 수 없다"로 채워진 첫 항목이라 근거를 남긴다.

이슈 #103은 **"예약번호만으로는 남의 예약이 열리지 않는다"**를 기준으로 삼으려
했다. **그러려면 예약번호 말고 하나가 더 있어야 하는데 그 컬럼이 없다.**
`reservation`에는 `session_id`·`user_id`·`hold_id`·`status`·시각뿐이고,
`erd.md` 4절이 **"사용자 테이블은 만들지 않는다"**를 정해 두었다. 이름·연락처를
넣으려면 마이그레이션을 더하고 **홀드·확정 경로가 그 값을 받아야 한다** — 그것이
측정 경로다.

**남은 것은 예약자 번호(`user_id`)이고 그것은 추측할 수 있다.** 그래서 이 화면은
남의 예약을 막지 못한다. **화면에만 확인 코드를 걸어도 마찬가지다** —
`GET /api/reservations/{id}`가 그대로 열려 있어 우회된다. `scope-m4.md` 6절이
같은 판단을 이미 했다.

**그러므로 REQ-14가 보증하는 것은 접근 제어가 아니라 "우회 경로를 만들지
않았다"이다.** 두 화면이 `ReservationService#get`의 검사를 그대로 타며, 그것을
`ReservationLookupFlowTest`가 지킨다. **정량 기준은 인증이 생길 때 세운다** —
그 전까지 세우면 2절이 경고한 "구현했지만 검증 수단이 없음"을 검증이 있는 것처럼
적는 셈이 된다.

**CS-4(취소 시 좌석 반환)는 새 REQ로 추가하지 않았다.** `concurrency-spec.md` 1절의
임계 구역 표에는 있지만, CS-6·erd 4.1과 달리 별도의 확정된 해결 절차 문서가 없다.
이중 반환 방지는 REQ-04(예약·결제 상태 정합성 검증)의 취소 흐름 안에서 이미 다뤄지는
것으로 본다. `POST /api/reservations/{id}/cancel`이 멱등하게 설계된 것(재취소 시 200과
기존 결과 반환, `api-spec.md` 6.1절)이 그 처리다.

> **이 조건을 #106에서 판정했다 — 여전히 REQ-04 안에서 다뤄진다.** 위 문단은
> "별도 REQ가 필요하다고 판단되면 이후 갱신한다"로 열려 있었고, 취소 경로를 다시
> 여는 PR이 그 조건을 판정할 자리다(`workflow.md` R6).
>
> **판정 근거는 취소 흐름에 상태가 늘지 않았다는 것이다.** #106은 환불 상태를 더하는
> 이슈였으나 **두지 않기로 판정됐다**(`erd.md` 4절). 취소 트랜잭션이 건드리는 것은
> 그대로 넷이다 — `seat_inventory`·`seat_hold`·`reservation`·`user_session_quota`.
> **이중 반환을 막는 것도 그대로 하나다** — 예약이 이미 `CANCELLED`면 그 앞에서
> 돌아가므로 좌석 반환이 두 번 일어나지 않는다. 그 성질을 `MinimumScopeFlowTest`가
> 재취소 테스트로 고정했다.
>
> **다시 열어야 할 조건은 취소 경로에 쓰기가 늘어날 때다.** 그때는 "이미 취소됐는가"
> 하나로 전부를 막을 수 없게 되고, CS-4가 자기 절차를 갖게 된다.

---

## 4. 범위 밖

`design-spec.md` 4.3절 "구현하지 않음"에 해당하는 항목은 애초에 REQ로 만들지
않았다. 실제 PG 연동·정산, 관리자 권한 세분화·감사 로그, 다국어·웹접근성 인증,
실제 SMS·카카오 발송, 물리 검표 단말 연동, 통계 대시보드 고도화가 이에 해당한다.
사유는 `design-spec.md` 4.3절 표에 있다.

`design-spec.md` 4.2절 "여유가 되면" 항목(대기열, 예약↔결제 정합성 대조 배치,
비회원 예약 조회, 노쇼 처리)도 착수가 결정되기 전까지는 REQ로 만들지 않는다.
착수가 결정되면 이 표를 갱신한다.

**비회원 예약 조회·마이페이지는 REQ-14로 들어왔다(#103).** "착수가 결정되면 이
표를 갱신한다"는 조건이 판정됐으므로 여기서 회수한다(`workflow.md` 6번). 남은
"여유가 되면" 항목은 대기열·예약↔결제 정합성 대조 배치·노쇼 처리 셋이고, 그것들은
조건이 그대로다.
