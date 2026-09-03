# holdfast

좌석 단위 점유 제어로 초과 예약 0건을 목표로 하는 실시간 예약·발권 시스템

예약 오픈 직후 동일 좌석에 요청이 몰릴 때 초과 확정이 발생하지 않도록 하는 것이
이 프로젝트의 목표다. 락 전략 4종을 구현하고, 락이 없는 베이스라인과 함께
동일한 부하 조건에서 초과 예약 건수와 응답시간을 비교 측정한다.

## 검수 기준

| 항목 | 목표 | 출처 |
|---|---|---|
| 초과 예약 | 0건 | 국립중앙과학관 통합예약시스템 개선 사업 RFP, SFR-001 |
| p95 응답시간 | 3초 이내 | 동 RFP, PER-002 |

## 기술 스택

| 영역 | 선택 |
|---|---|
| 백엔드 | Spring Boot 4.1 / Java 25 |
| DB | PostgreSQL 18 |
| 캐시·분산락 | Redis (또는 Valkey) + Redisson 4.7 |
| 프론트엔드 | Thymeleaf + htmx (서버 렌더링) |
| 부하 테스트 | k6 |
| 인프라 | AWS ECS + ALB (앱 2대), Terraform |
| 로컬 | Docker Compose (앱 2대 + nginx) |

선택 근거와 미결정 항목은 [동시성 설계서](docs/concurrency-spec.md) 0절에 있다.

## 측정 결과

**고경합 시나리오(좌석 10석 / VU 500)**, 3회 실행의 중앙값. 전략당 9회 +
세션마다 `none` 대조군 3회, 총 60회를 측정했다.

| 전략 | 초과 확정 | 초과 홀드 | 처리량 | 정상 거절(409) | 재시도 | 제약 위반 |
|---|---|---|---|---|---|---|
| 락 없음 (baseline) | **4석** ✗ | 0 ※1 | 488 TPS | 99.95% | — | — |
| 비관적 락 | **0** ✓ | 0 | 491 TPS | 99.97% | — | 0 |
| 낙관적 락 | **0** ✓ | 0 | 487 TPS | 99.90% | **102** | 0 |
| DB 유니크 제약 | **0** ✓ | 0 | 488 TPS | 99.97% | — | **11** ※2 |
| Redis 분산락 | **0** ✓ | 0 | 493 TPS | 99.97% | — | 0 ※3 |

**락이 없으면 경합이 있을 때 깨지고, 네 전략은 방식이 달라도 모두 초과 확정 0이다.
앱 락이 바꾼 것은 경합이 드러나는 방식이지 정합성이 아니며, 정합성을 실제로
지키는 것은 DB 유니크 제약과 조건부 UPDATE다. 성능은 전략을 구분하지 못한다.**

종합은 [M3 결론](docs/results/m3-conclusion.md), 경합도별 전체 지표는
[동시성 설계서](docs/concurrency-spec.md) 7.6에 있다.

**p95를 이 표에 싣지 않는다. 전략을 구분하는 값이 아니기 때문이다.** 다섯 전략이
모두 **18~41ms 대역**에 들어가는데, 같은 `none` 대조군을 다섯 세션에서 재도
**18~41ms로 똑같이 흔들린다** — 세션 간 변동이 전략 간 차이보다 크다. 어느
전략이 몇 ms였는지를 행으로 늘어놓으면 그 자체가 순위표로 읽히고, **실제로는
그 세션이 빨랐던 것**이다. 절댓값과 경합도별 상세는
[7.6](docs/concurrency-spec.md)에, 판정 근거는
[M3 결론](docs/results/m3-conclusion.md) 2.5절에 있다.

**가장 분명한 사례가 `redis`다.** 18ms로 다섯 중 가장 낮아 "분산락이 제일
빠르다"로 읽힐 뻔했는데 **같은 세션 대조군도 17.98ms였다.** 대조군이 없었다면
`pessimistic` 32ms와 나란히 놓고 "1.8배 빠르다"고 적었을 것이다.

임계 구역이 서브밀리초라 락 대기가 응답시간을 지배할 구간이 없기 때문이며,
그것은 결함이 아니라 홀드·확정을 분리한 설계의 결과다
([p95 역전 조사](docs/results/p95-inversion-investigation.md),
[지속 경합 조사](docs/results/sustained-lock-wait-investigation.md)).

**정상 거절은 실패가 아니다.** 좌석이 이미 팔려 409를 반환한 것은 시스템이
제대로 동작한 결과이므로 오류율과 분리해 집계한다.

**재시도·제약 위반 열이 전략의 차이를 보여준다.** 같은 사건(동시에 같은 좌석을
노린 요청)이 `optimistic`에서는 재시도로, `unique`에서는 제약 위반으로 나타나고,
앱 락을 쓰는 나머지 둘에서는 락이 흡수해 0으로 보인다. `—`는 그 전략에 그 기전이
없다는 뜻이지 0이 아니다.

※1 **초과 홀드 0이 "겹친 홀드가 없었다"는 뜻은 아니다.** 활성 홀드만 세는데
시나리오가 홀드 직후 확정해 그 창이 밀리초 단위다. 지속 경합 시나리오에서는 좌석
1석에 점유 중첩 17,269건이 나왔다.

※2 **이 전략에서 제약 위반은 정상 동작이다.** 같은 좌석을 동시에 노린 요청이
그만큼 있었다는 뜻이고, **앱 락을 쓰는 전략에서는 락이 흡수해 0으로 보이던
숫자**다. 나머지 전략에서 0이 아니면 반대로 앱 락이 샜다는 신호다.

※3 **"새지 않는다"가 아니라 "새는 것을 관측하지 못했다"이다.** 리스를 1ms로 낮춘
단위 테스트에서는 제약 위반 9건이 나왔고 그때도 초과 홀드는 0이었다 — 락은 샜고
DB 제약이 받아냈다. 분산락은 성능 최적화이지 정합성 보장이 아니다.

## 구조

```
api/         애플리케이션 (Thymeleaf 템플릿 포함)
infra/       Terraform, 배포
load-test/   k6 시나리오
docs/        설계 명세, ERD, 측정 결과
```

## 데모 실행

화면에서 `좌석 선택 → 선점 → 결제 → QR 발급 → 검표`를 끝까지 돌려 보는 방법이다.

```bash
HOLDFAST_STRATEGY=pessimistic HOLD_TTL_SECONDS=300 docker compose up -d --build
docker compose exec -T db psql -U holdfast -d holdfast < infra/demo-seed.sql
```

- 좌석맵 <http://localhost:8080/sessions/1> — 선점하고 결제하면 예약 확인 화면으로 넘어간다
- 검표 <http://localhost:8080/scan> — 예약 확인 화면의 QR 토큰을 붙여넣는다

**부하 측정용 시드(`load-test/sql/seed.sql`)로는 이 흐름이 돌지 않는다.** 그쪽은
검표를 하지 않으므로 입장 가능 시간이 내일로 잡혀 있고, 기본값인 `none` 전략은
만료된 홀드를 회수하지 않는다(그것이 베이스라인의 정의다). 두 시드의 차이는
[`infra/demo-seed.sql`](infra/demo-seed.sql) 머리말에 적어 두었다.

## 부하 테스트

측정 실행 환경과 집계 로직은 [`load-test/`](load-test/)에 있다. 자세한 내용은
[부하 테스트 README](load-test/README.md).

```bash
docker compose up -d                    # 앱 2대 + DB + Redis + nginx
load-test/scripts/run.sh smoke          # k6 실행 환경·집계 파이프라인 확인
DURATION_SEC=120 load-test/scripts/run.sh high pessimistic   # 본 측정
load-test/scripts/summarize.mjs --scenario high        # 7.6 기록 양식 표
```

전략을 바꿔 측정할 때는 `HOLDFAST_STRATEGY`를 넘겨 앱 2대를 다시 띄운다.
시드 스크립트가 `none`일 때만 U-2 인덱스를 지우므로, 나머지 전략은 최후
방어선이 걸린 상태로 측정된다.

## 문서

- [M3 결론 — 다섯 전략 측정 종합](docs/results/m3-conclusion.md)
- [폐기 기록 — 버린 측정과 회수한 전제](docs/results/discarded-measurements.md)
- [설계 명세 및 구현 범위](docs/design-spec.md)
- [요구사항 추적표](docs/requirements.md)
- [동시성 설계서](docs/concurrency-spec.md)
- [ERD — 도메인 모델](docs/erd.md)
- [상태 전이 다이어그램](docs/state-transitions.md)
- [예약 API 계약 — 요약과 설계 근거](docs/api-spec.md)
- [OpenAPI 3.1 정의](docs/openapi.yaml)
- [역할 분담](docs/roles.md)
- [협업 규칙](docs/workflow.md)
- [배포 환경 결정](docs/infra-decision.md)

## 라이선스

MIT
