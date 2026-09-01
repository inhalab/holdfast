# 부하 테스트 (k6)

동시성 설계서 7장의 측정을 실행하는 환경이다.

## 담당 경계

- **실행 환경**(이 디렉토리 구조, compose, smoke 검증) — 박태준(인프라)
- **측정 시나리오**(`scenarios/*.js`, 경합도 3단계, 좌석 홀드·확정 흐름) — 최건(load-test 담당)

이 README와 `smoke.js`는 "환경이 도는지" 확인하는 골격이다. 실제 측정
시나리오는 최건이 설계서 7.2/7.4에 따라 작성·교체한다.

## 실행

k6는 앱과 **분리된 컨테이너**로 실행한다(설계서 7.3: 앱과 분리된 호스트).
평소에는 뜨지 않고 `load` 프로파일로만 실행된다.

```bash
# 1) 앱 먼저 기동 (앱 2대 + DB + Redis + nginx)
docker compose up -d

# 2) 부하 테스트 실행 (smoke 검증)
docker compose -f docker-compose.yml -f docker-compose.k6.yml \
  --profile load run --rm k6 run /scenarios/smoke.js

# 3) 결과 확인
cat load-test/results/smoke-summary.json
```

시나리오를 바꾸려면 `/scenarios/파일명.js`만 교체하면 된다.

## 앱과 분리 원칙

k6는 `nginx:80`(로드밸런서)을 향해 부하를 보낸다. 앱 컨테이너(app1/app2)를
직접 때리지 않는다. 그래야 로드밸런싱을 포함한 실제 경로를 측정한다.

설계서 7.3의 "k6 실행 위치는 앱과 분리된 호스트"를 로컬에서는 별도 컨테이너로
근사한다. AWS 측정 시에는 k6를 앱과 다른 인스턴스/호스트에서 돌린다.

## 측정 시 참고 (설계서 7장)

- 응답시간 정본은 k6 (p95 3초 이내가 검수 기준)
- 정상 거절(409)과 오류(5xx)를 분리해서 집계
- 전략당 3회 반복, 중앙값 채택
- 워밍업 30초는 집계에서 제외
- 원본 결과 JSON은 `results/`에 확정본만 커밋
