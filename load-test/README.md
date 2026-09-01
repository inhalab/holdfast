# 부하 테스트 (k6)

동시성 설계서(`docs/concurrency-spec.md`) 7장의 측정을 실행하는 환경이다.

**예약 API는 아직 구현되지 않았다.** 지금 목표는 API가 생겼을 때 바로 돌릴 수 있는
구조를 만들고, 특히 **집계 로직을 미리 확정하는 것**이다. 급하게 만들면 정상 거절과
오류의 분리가 가장 먼저 흐려진다.

지금 실제로 돌아가는 것은 스모크 테스트뿐이고, 그것으로 k6 실행 환경과 집계
파이프라인이 도는지 확인한다.

## 구성

```
scenarios/
  smoke.js          지금 돌릴 수 있는 유일한 시나리오. /api/status·/api/health를
                    reservation.js와 같은 집계 경로에 통과시킨다
  reservation.js    본 측정 시나리오. 계약(openapi.yaml)에 맞춰 미리 써 둔 것
  lib/
    classify.js     **응답 → 집계 버킷 분류. 측정 해석의 핵심**
    metrics.js      7.1 지표 중 k6가 정본인 것들의 커스텀 메트릭
    config.js       7.2 경합도 3단계, 7.4 워밍업·반복
    api.js          openapi.yaml 계약에 맞춘 호출 래퍼
    summary.js      결과 요약 렌더러 (원격 import 없음)
sql/
  seed.sql          7.4-1 시드 초기화
  u2-create.sql     U-2 생성 (none 제외 4개 전략)
  u2-drop.sql       U-2 삭제 (none 전용)
  verify.sql        **초과 예약 검증 — 출처가 k6가 아니라 DB다**
scripts/
  run.sh            측정 프로토콜 실행기 (시드 → 워밍업·측정 → 검증, 3회 반복)
  seed.sh           시드 초기화
  verify.sh         DB 검증 쿼리 실행
  summarize.mjs     7.6 기록 양식 표 출력 (3회 중앙값)
results/            실행 결과 JSON (gitignore 대상)
```

## 실행

```bash
# 1) 앱 기동 (앱 2대 + DB + Redis + nginx)
docker compose up -d

# 2) 스모크 — 지금 돌릴 수 있는 것
load-test/scripts/run.sh smoke

# 3) 본 측정 (예약 API 구현 후) — 전략 1개 × 3회 반복
load-test/scripts/run.sh high pessimistic

# 4) 7.6 기록 양식 표로 요약
node load-test/scripts/summarize.mjs --scenario high
```

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

## 측정 프로토콜 (7.4)

`scripts/run.sh`가 순서를 강제한다.

1. **시드 초기화** — `seed.sh`. 매 회차마다 같은 상태에서 시작한다(7.3)
2. **워밍업 30초 — 집계에서 제외** — 커스텀 메트릭에 기록하지 않는 방식으로 제외한다
3. **본 측정**
4. **전략당 3회 반복, 중앙값** — `summarize.mjs`가 계산한다
5. 원본 결과 JSON은 `results/`에 남는다

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
| 낙관적 재시도 횟수 | 앱 커스텀 메트릭 | Actuator (앱 구현 후) |
| 제약 위반 횟수 | 앱 커스텀 메트릭 | Actuator (앱 구현 후) |
| 커넥션 풀 대기 | `hikaricp.connections.pending` `.acquire` | Actuator |

**초과 예약을 k6가 셀 수 없는 이유**는 k6가 자기가 받은 응답만 알기 때문이다.
서버가 두 요청 모두에 201을 돌려주면 k6는 성공 2건으로 셀 뿐, 그것이 같은
좌석이었는지 모른다. 초과 예약은 응답이 아니라 DB 상태로만 드러난다.

**재시도 횟수와 락 포기율을 환산하지 않는다.** 재시도는 앱이 세는 누적 *횟수*,
락 포기율은 k6가 세는 요청 *비율*이라 단위가 다르다. 한 요청이 3회 재시도 후
포기하면 재시도에 3, 락 포기율에 요청 1건이 더해진다(7.6.1).

## 결과 파일

원본 JSON은 `results/`에 남고 gitignore 대상이다. 7.4-5가 말하는 "원본 결과 JSON을
`docs/results/`에 커밋"은 **확정 측정본**을 뜻하므로, 채택한 실행만 `docs/results/`로
옮겨 커밋한다. 자동 생성물 전부를 커밋하지는 않는다.

## 담당 경계

- **실행 환경**(디렉토리 구조, compose) — 박태준(인프라)
- **측정 시나리오·집계**(`scenarios/`, `sql/`, `scripts/`) — 최건(load-test 담당)
