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

아래는 **고경합 시나리오(좌석 10석 / VU 500)** 요약이다. 3회 실행의 중앙값이다.
저경합·극단 시나리오와 전체 지표는 [동시성 설계서](docs/concurrency-spec.md) 7절,
측정 근거와 해석은 [베이스라인](docs/results/m2-none-baseline.md)과
[비관적 락](docs/results/m2-pessimistic.md)에 있다.

| 전략 | 초과 확정 | 초과 홀드 | p95 | 처리량 | 정상 거절(409) | 제약 위반 |
|---|---|---|---|---|---|---|
| 락 없음 (baseline) | **4석** ✗ | 0 ※ | 40ms | 488 TPS | 99.95% | — |
| 비관적 락 | **0** ✓ | 0 | 32ms | 491 TPS | 99.97% | **0** |
| 낙관적 락 | **0** ✓ | 0 | 41ms | 487 TPS | 99.9% | **0** |
| DB 유니크 제약 | | | | | | |
| Redis 분산락 | | | | | | |

**베이스라인은 검수 기준을 실제로 위반한다.** 10석을 팔아 확정된 예약이 14~17건
나왔고, 한 좌석이 최대 4명에게 팔렸다. 2개월차 산출물은 동작하는 예약이 아니라
**이 실패 데이터**다. 락 전략 4종은 이 표에서 초과 확정을 0으로 만들면서 p95를
얼마나 지불하는지로 평가된다 — `none`의 p95 40ms는 락이 없어 아무것도 기다리지
않은 값이지 빠르다는 뜻이 아니다.

**초과 확정과 초과 홀드는 다른 값이다.** 검수 기준 "초과 승인 0건"에 직접
대응하는 것은 초과 확정이고, 초과 홀드는 그 앞 단계다. 한 열에 뭉치면 어느 쪽을
센 숫자인지 모호해진다([7.6.2](docs/concurrency-spec.md)).

**※ 초과 홀드 0은 겹친 홀드가 없었다는 뜻이 아니다.** 이 값은 활성 홀드만 세는데
시나리오가 홀드 직후 확정해 그 창이 밀리초 단위다. 상태를 무시하고 세면 10석 중
5석에 홀드가 겹쳤다([측정 결과 4.3](docs/results/m2-none-baseline.md)).

**낙관적 락도 초과 확정 0이다.** 재시도 102회·소진 34건으로 이 전략 고유
비용은 드러나지만(비관적 락은 락 포기 0), 검수 기준은 동일하게 통과한다.
전략마다 정합성을 지키는 방식이 다를 뿐 결과는 같다.

**성능 차이는 아직 확인되지 않았다.** 지속 경합 시나리오에서 홀드 p95는 두
전략이 0.5% 차이(240ms vs 241ms)이고 커넥션 점유는 0.19% 차이다. 반면 정합성
지표는 자릿수로 갈린다 — 좌석 1석에 대한 점유 중첩이 `none` 17,269건,
비관적 락 0건. **이 응용의 임계 구역이 서브밀리초라 락 대기가 응답시간을 지배할
구간이 없기 때문이며**, 그래서 M3의 결론은 "어느 전략이 빠른가"가 아니라
"성능은 대체로 비슷하고 차이는 정합성 보장 방식에 있다"가 될 전망이다
([조사 기록](docs/results/sustained-lock-wait-investigation.md)).

**두 전략의 p95를 직접 비교하지 마라.** `pessimistic`이 40ms → 32ms로 낮게
나왔지만 락을 걸어서 빨라진 것이 아니다. 이 시나리오의 p95는 락 비용이 아니라
커넥션 대기열 길이를 재고 있다 —
[p95 역전 조사](docs/results/p95-inversion-investigation.md).

**정상 거절은 실패가 아니다.** 좌석이 이미 팔려 409를 반환한 것은 시스템이
제대로 동작한 결과이므로 오류율과 분리해 집계한다.

**제약 위반 열은 최후 방어선의 작동 횟수다.** 앱 레벨 락을 쓰는 전략에서 이
값이 0이 아니면 그 락이 새고 있다는 뜻이다.

## 구조

```
api/         애플리케이션 (Thymeleaf 템플릿 포함)
infra/       Terraform, 배포
load-test/   k6 시나리오
docs/        설계 명세, ERD, 측정 결과
```

## 부하 테스트

측정 실행 환경과 집계 로직은 [`load-test/`](load-test/)에 있다. 자세한 내용은
[부하 테스트 README](load-test/README.md).

```bash
docker compose up -d                    # 앱 2대 + DB + Redis + nginx
load-test/scripts/run.sh smoke          # k6 실행 환경·집계 파이프라인 확인
DURATION_SEC=120 load-test/scripts/run.sh high pessimistic   # 본 측정
node load-test/scripts/summarize.mjs --scenario high   # 7.6 기록 양식 표
```

전략을 바꿔 측정할 때는 `HOLDFAST_STRATEGY`를 넘겨 앱 2대를 다시 띄운다.
시드 스크립트가 `none`일 때만 U-2 인덱스를 지우므로, 나머지 전략은 최후
방어선이 걸린 상태로 측정된다.

## 문서

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
