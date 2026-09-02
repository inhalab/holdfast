# 부하 테스트 (k6)

동시성 설계서(`docs/concurrency-spec.md`) 7장의 측정을 실행하는 환경이다.

**M3까지 60회를 이 하네스로 쟀다** — 5전략 × 경합도 3단계 × 3회 + 세션마다
`none` 대조군 3회(`concurrency-spec.md` 7.6). 데드락 회피 검증(7.2.1)과 지속
경합 시나리오(7.2.2)도 여기서 돈다.

**이 하네스의 핵심은 부하 생성이 아니라 집계다.** 정상 거절과 오류의 분리가
흐려지면 "실패율 40%" 같은 무의미한 숫자가 나오고, 그 뒤의 모든 비교가 무너진다.
`lib/classify.js`가 그 분리를 담당한다.

## 구성

```
scenarios/
  reservation.js       본 측정 시나리오 (7.6의 60회가 이것)
  smoke.js             /api/status·/api/health를 reservation.js와 같은 집계
                       경로에 통과시킨다. 실행 환경·집계 파이프라인 확인용
  deadlock.js          데드락 회피 검증 (7.2.1). 3석을 뒤섞어 보낸다
  sustained.js         지속 경합 시나리오 (7.2.2). 좌석 1석에 계속 겨루게 해
                       4.5.1을 시험한다 (7.5.1)
  lib/
    classify.js        **응답 → 집계 버킷 분류. 측정 해석의 핵심**
    metrics.js         7.1 지표 중 k6가 정본인 것들의 커스텀 메트릭
    config.js          7.2 경합도 3단계, 7.4 워밍업·반복
    api.js             openapi.yaml 계약에 맞춘 호출 래퍼
    summary.js         결과 요약 렌더러 (원격 import 없음)
sql/
  seed.sql             7.4-1 시드 초기화
  reset.sql            **본 측정 시작 시 재초기화 (7.4.1).** 행 단위 DML이며
                       대상 좌석을 먼저 FOR UPDATE로 잠근다 — 이유는 아래
  u2-create.sql        U-2 생성 (none 제외 4개 전략)
  u2-drop.sql          U-2 삭제 (none 전용)
  verify.sql           **초과 예약 검증 (V-1~V-5) — 출처가 k6가 아니라 DB다**
  verify-sustained.sql V-6 점유 구간 중첩 (지속 경합 전용)
scripts/
  run.sh               측정 프로토콜 실행기 (시드 → 워밍업 → 재초기화 → 측정
                       → 메트릭 캡처 → 검증, 3회 반복)
  run-deadlock.sh      데드락 회피 검증 실행기 (판정: 데드락 0건)
  run-sustained.sh     지속 경합 시나리오 실행기
  seed.sh              시드 초기화
  verify.sh            DB 검증 쿼리 실행
  metrics-snapshot.mjs **회차 직후 Actuator 스냅샷 (7.4.2).** 커넥션 풀 +
                       앱 커스텀 메트릭(재시도·소진·제약 위반)
  summarize.mjs        7.6 기록 양식 표 출력 (3회 중앙값)
results/               실행 결과 JSON·검증 출력 (gitignore 대상)
```

**`reset.sql`이 `seed.sql`과 별개인 이유는 7.4.1에 있다.** 본 측정 시작 시점에는
앱 트랜잭션이 이미 돌고 있으므로 `TRUNCATE`가 데드락을 내고, 삭제와 재고 초기화
사이의 창으로 진행 중이던 홀드가 살아남아 좌석이 다시 팔린다. 측정 두 벌을 그렇게
버렸다(`docs/results/discarded-measurements.md` 2·3번).

**`metrics-snapshot.mjs`를 회차마다 자동으로 부르는 이유도 사고에서 나왔다.**
전략을 바꿔 앱을 다시 띄우면 Actuator 카운터가 초기화되는데, 손으로 뜨다가 세 번
놓쳤다. 전략 전환 전에 마지막 스냅샷이 확보돼야 한다(7.4.2).

## 실행

```bash
# 1) 앱 기동 (앱 2대 + DB + Redis + nginx)
docker compose up -d

# 2) 스모크 — 실행 환경·집계 파이프라인 확인
load-test/scripts/run.sh smoke

# 3) 본 측정 — 전략 1개 × 3회 반복
load-test/scripts/run.sh high pessimistic

# 4) 7.6 기록 양식 표로 요약
node load-test/scripts/summarize.mjs --scenario high

# 5) 데드락 회피 검증 (성능 측정이 아니다. 판정은 데드락 0건)
load-test/scripts/run-deadlock.sh pessimistic high

# 6) 지속 경합 (성능 측정이 아니다. 판정은 제약 위반과 V-6)
load-test/scripts/run-sustained.sh redis
```

### 개발 확인용과 최종 측정용을 구분한다 (7.4)

| 용도 | `DURATION_SEC` | 쓰는 때 |
|---|---|---|
| 개발 확인용 | **30초** (기본값) | 시나리오·집계를 고치며 반복 실행할 때 |
| 최종 측정용 | **120초** | 보고서에 실을 숫자를 뽑을 때 |

```bash
load-test/scripts/run.sh high pessimistic                    # 30초 (개발 확인용)
DURATION_SEC=120 load-test/scripts/run.sh high pessimistic   # 120초 (최종 측정용)
```

**120초는 JIT가 안정화된 뒤에도 충분한 표본을 쌓기 위한 길이다.** p95·p99는 꼬리
분포라 표본이 적으면 실행마다 크게 흔들린다.

**기본값이 30초인 이유는 전체 실행 시간이다.** 5전략 × 3회 × 3시나리오 = 45회에
회차마다 워밍업 30초가 붙어, 120초로 전부 돌리면 두 시간을 넘는다. 30초면 45분이다.
(실제로는 세션마다 `none` 대조군을 함께 돌려 60회가 됐다 — 7.4.2.)

**개발 확인용 실행의 숫자는 7.6 기록 양식에 싣지 않는다.** `run.sh`가 실행 시작 때
알려주고, `summarize.mjs`도 개발 확인용 실행이 섞이면 경고한다.

### Windows / Git Bash 주의

Git Bash는 `/scenarios/smoke.js` 같은 인자를 Windows 경로(`C:/Program Files/Git/...`)로
바꿔버려 k6가 스크립트를 못 찾는다. `scripts/run.sh`는 `MSYS_NO_PATHCONV=1`을 걸어
두었으므로 이 스크립트를 쓰면 문제가 없다. docker compose를 직접 칠 때만 주의한다.

```bash
MSYS_NO_PATHCONV=1 docker compose -f docker-compose.yml -f docker-compose.k6.yml \
  --profile load run --rm k6 run /scenarios/smoke.js
```

## 경합도 3단계 (7.2)

`SCENARIO` 환경변수로 전환한다. 좌석 수와 VU는 설계서가 못박은 값이라 바꾸지 않는다.

| `SCENARIO` | 좌석 | VU | 관찰 목적 |
|---|---|---|---|
| `low` | 1000 | 100 | 오버헤드 비교 |
| `high` | 10 | 500 | 전략 차이 |
| `extreme` | 1 | 200 | 정합성 한계 |

## 데드락 회피 검증 (7.2.1)

경합도 3단계는 **요청당 1석을 유지한다.** 여러 좌석을 한 번에 잡으면 거절 사유가
좌석별로 섞여 경합도 해석이 흐려지기 때문이다.

그런데 그러면 **`erd.md` 4.1의 좌석 ID 오름차순 획득과 `concurrency-spec` 5.1의
전역 락 순서가 부하 테스트에서 한 번도 실행되지 않는다.** 1석만 잡으면 정렬할
것이 없고, 여러 좌석을 잡는 코드 경로가 통째로 미검증으로 남는다. 데드락은 바로
그 경로에서만 난다.

그래서 별도 시나리오로 3석을 잡는다.

```bash
load-test/scripts/run-deadlock.sh pessimistic high
```

| 항목 | 값 |
|---|---|
| 요청당 좌석 수 | 3석 |
| 좌석 ID 전송 순서 | **무작위(뒤섞어 보낸다)** |
| 판정 기준 | **데드락 발생 0건** — 처리량·p95가 아니다 |
| 결과 기록 | 7.6 기록 양식에 넣지 않는다 |

**좌석 ID를 뒤섞어 보내는 것이 핵심이다.** 5.1이 정렬을 요구하는 주체는
애플리케이션이다. 클라이언트가 이미 정렬해 보내면 서버가 정렬을 빠뜨려도 데드락이
나지 않아, 서버의 정렬 로직이 실제로 도는지 확인할 수 없다. `reservation.js`는
정렬해 보내고(잘 동작하는 클라이언트), `deadlock.js`에서만 뒤섞는다.

**판정은 응답이 아니라 DB에서 읽는다.** 앱은 데드락을 잡아 409 `LOCK_TIMEOUT`으로
변환하므로(`api-spec` 3.3) k6가 받는 응답만으로는 데드락이었는지 단순 락 대기였는지
구분할 수 없다. 초과 예약과 같은 이유다.

```sql
SELECT deadlocks FROM pg_stat_database WHERE datname = current_database();
```

`run-deadlock.sh`가 실행 전후로 이 값을 찍어 차이를 내고, Postgres 로그의
`deadlock detected` 항목으로 교차 확인한 뒤 통과/실패를 판정한다.

## 측정 프로토콜 (7.4)

`scripts/run.sh`가 순서를 강제한다.

1. **시드 초기화** — `seed.sh`. 매 회차마다 같은 상태에서 시작한다(7.3)
2. **워밍업 30초 — 집계에서 제외** — 커스텀 메트릭에 기록하지 않는 방식으로 제외한다
3. **본 측정 시작 시 시드 재초기화** — `reset.sql`. 워밍업이 좌석을 소진하므로
   본 측정이 매진 상태에서 시작하지 않게 한다(7.4.1)
4. **본 측정**
5. **회차 직후 메트릭·검증 캡처** — `metrics-snapshot.mjs`와 `verify.sh` 출력을
   `results/`에 파일로 남긴다(7.4.2)
6. **전략당 3회 반복, 중앙값** — `summarize.mjs`가 계산한다
7. 원본 결과 JSON은 `results/`에 남는다

### 워밍업을 어떻게 제외하나

내장 `http_req_duration`은 워밍업까지 포함하므로 **쓰지 않는다.** 대신 워밍업이
끝난 뒤의 요청만 `measured_req_duration` 커스텀 Trend에 담고, p95·p99를 거기서
읽는다. 경계 판정은 벽시계가 아니라 k6의 테스트 경과 시간(`exec.instance.
currentTestRunDuration`)을 쓴다 — VU마다 시작 시각이 다르면 경계가 흔들린다.

VU 램프업은 워밍업 **안에서** 끝낸다. 본 측정은 목표 VU가 고정된 구간에서만 돈다.

## 집계 — 무엇을 어느 열에 넣는가

`lib/classify.js`가 이 표 하나를 코드로 옮긴 것이다. 근거는 `docs/api-spec.md`
3.1·3.2다.

| 버킷 | 코드 | 7.6 기록 양식 |
|---|---|---|
| 정상 거절 | `SEAT_ALREADY_SOLD` `SEAT_HELD_BY_OTHER` `HOLD_EXPIRED` `QUOTA_EXCEEDED` `RESERVATION_NOT_OPEN` | **409율** |
| 락 포기 | `LOCK_TIMEOUT` `RETRY_EXHAUSTED` | **락 포기율** |
| 서버 오류 | 5xx, 타임아웃·연결 실패 | **오류율** |
| 상태 거절 | `HOLD_RELEASED` `HOLD_ALREADY_CONFIRMED` `RESERVATION_ALREADY_CANCELLED` `RESERVATION_NOT_CANCELLABLE` | 어느 열에도 안 들어감 |
| 클라이언트 오류 | `IDEMPOTENCY_KEY_*` `VALIDATION_FAILED` `*_NOT_FOUND` | 어느 열에도 안 들어감 |
| 미분류 | 그 밖의 모든 것 | **0이어야 한다** |

**락 포기는 409로 오지만 409율에 넣지 않는다**(7.6.1). 좌석이 팔려서 거절된 것이
아니라 남아 있었을 수도 있는데 포기한 것이라, 정상 거절도 오류도 아니다.

**모르는 코드는 어느 버킷에도 넣지 않는다.** 계약에 새 오류 코드가 생겼는데
`classify.js`를 갱신하지 않으면 그 코드가 조용히 정상 거절이나 오류에 섞여
비교표를 오염시킨다. 미분류가 0이 아니면 그 실행의 숫자는 쓰지 않는다 —
`bucket_unclassified_total` 임계값이 `count==0`이라 k6도 실패로 표시한다.

## k6가 재지 않는 지표

7.1이 지표마다 출처를 못박아 두었다. k6가 아닌 것은 k6에서 지어내지 않고,
요약 표에 `?`로 남긴다. **0으로 채우지 않는다** — "쟀는데 0"과 "안 쟀음"이
구분되지 않으면 비교표를 믿을 수 없다.

| 지표 | 출처 | 실행 방법 |
|---|---|---|
| 초과 예약 건수 | DB 검증 쿼리 | `scripts/verify.sh` |
| 낙관적 재시도 횟수 | 앱 커스텀 메트릭 | `scripts/metrics-snapshot.mjs` (회차마다 자동) |
| 제약 위반 횟수 | 앱 커스텀 메트릭 | `scripts/metrics-snapshot.mjs` (회차마다 자동) |
| 커넥션 풀 대기 | `hikaricp.connections.pending` `.acquire` | `scripts/metrics-snapshot.mjs` |

**초과 예약을 k6가 셀 수 없는 이유**는 k6가 자기가 받은 응답만 알기 때문이다.
서버가 두 요청 모두에 201을 돌려주면 k6는 성공 2건으로 셀 뿐, 그것이 같은
좌석이었는지 모른다. 초과 예약은 응답이 아니라 DB 상태로만 드러난다.

**재시도 횟수와 락 포기율을 환산하지 않는다.** 재시도는 앱이 세는 누적 *횟수*,
락 포기율은 k6가 세는 요청 *비율*이라 단위가 다르다. 한 요청이 3회 재시도 후
포기하면 재시도에 3, 락 포기율에 요청 1건이 더해진다(7.6.1).

## 결과 파일

**원본 JSON은 `load-test/results/`에 남기고 커밋하지 않는다.** 루트 `.gitignore`와
`load-test/.gitignore` 양쪽에 규칙이 있다 — 루트만 봐도, `load-test/`만 떼어 봐도
규칙이 유효하도록 둘 다 둔다.

**채택한 확정 측정본만 `docs/results/`로 옮겨 커밋한다.** 위 프로토콜 7번이 말하는
"원본 결과 JSON"의 처리가 이것이다. 자동 생성물 전부를 커밋하지는 않는다.

```
load-test/results/*.json    실행할 때마다 쌓이는 원본. gitignore 대상
docs/results/*.json         채택한 확정 측정본. 커밋한다
```

옮기는 것은 **최종 측정용(120초) 실행만**이다. 개발 확인용 30초 실행은 표본이
부족해 7.6 기록 양식을 채우는 데 쓰지 않는다.

## 담당 경계

- **실행 환경**(디렉토리 구조, compose) — 박태준(인프라)
- **측정 시나리오·집계**(`scenarios/`, `sql/`, `scripts/`) — 최건(load-test 담당)
