# 로컬 개발 환경 (Spring Boot 4.1 / PostgreSQL 18 / Thymeleaf + htmx)

좌석 단위 점유 제어 예약·발권 시스템의 로컬 다중 인스턴스 환경.

## 기술 스택 (팀 확정)

| 영역 | 선택 |
|---|---|
| 백엔드 | Spring Boot 4.1.1 / Java 25 |
| DB | PostgreSQL 18 |
| 캐시·분산락 | Redis + Redisson 4.7 |
| 프론트엔드 | Thymeleaf + htmx (서버 렌더링) |
| 로컬 | Docker Compose (앱 2대 + nginx) |

## 구조

```
nginx (로드밸런서, localhost:8080)
  ├─ app1 (Spring Boot)  ┐
  └─ app2 (Spring Boot)  ┴─ 같은 postgres · redis 공유
        ├─ PostgreSQL 18 (localhost:5432)
        └─ Redis 7        (localhost:6379)
```

앱을 **2대** 띄우는 것이 핵심이다. 설계 명세 5.2 — "단일 인스턴스로만 부하
테스트하면 분산락의 의미가 없다"에 따라 처음부터 앱 2대 + 로드밸런서로 잡았다.
로컬 nginx 역할을 AWS에서는 ALB가 대신한다.

## 실행

```bash
docker compose up --build
```

첫 빌드는 gradle 의존성을 받느라 몇 분 걸린다.

## 동작 확인

PowerShell에서는 curl 대신 `curl.exe`를 쓰면 경고 없이 JSON만 나온다.

```bash
# 로드밸런싱 확인 — 여러 번 호출하면 instance가 app1/app2로 번갈아 나온다
curl.exe http://localhost:8080/api/status
# {"service":"holdfast","instance":"app1",...}

# DB·Redis 연결 확인
curl.exe http://localhost:8080/api/health
# {"instance":"app1","db":"up","redis":"up"}

# 브라우저: http://localhost:8080  (htmx 버튼으로 부분 갱신도 확인 가능)
```

## 종료

```bash
docker compose down       # 컨테이너 정지·삭제
docker compose down -v    # DB 데이터(볼륨)까지 삭제
```

주의: DB 종류를 바꾸거나 볼륨이 꼬이면 `docker compose down -v`로 볼륨을
지운 뒤 다시 올린다. (예: mysql.sock 관련 에러)

## 설정

모든 설정은 환경변수로 주입한다. AWS로 옮길 때 `DB_HOST`를 RDS로,
`REDIS_HOST`를 ElastiCache로 바꾸면 된다. 코드·이미지는 그대로 재사용.

## 주의

- `JPA_DDL_AUTO=validate`(기본값)로 전환 완료. 스키마는 Flyway 마이그레이션
  (`api/src/main/resources/db/migration/`)이 정본이고, JPA는 엔티티와 스키마가
  일치하는지만 검증한다. 스캐폴딩 단계의 `update`는 더 이상 쓰지 않는다.
- `(회차ID, 좌석ID)` 유니크 제약(U-1)은 `V1__init_schema.sql`이 이미 걸어
  두었다. 설계 명세 5.1 — 초과 예약을 0건으로 만드는 최후 방어선. `seat_hold`의
  활성 홀드 유니크(U-2)는 `none` 전략에서만 빠지며, 그 제거는 마이그레이션이
  아니라 `load-test/scripts/seed.sh`가 담당한다(`docs/erd.md` 3.1절).
- 분산락(Redisson) 해제는 **트랜잭션 커밋 이후**에. 설계 명세 5.1의 흔한 함정.

## 버전 참고

- Spring Boot 4.x는 3.x와 다르다: Jakarta EE 11, Jackson 3, JUnit 5 전용,
  Undertow 제거. 새 코드라 영향은 적지만, 외부 예제를 붙일 때 3.x 문법이면
  일부 안 맞을 수 있다.
- Redisson 4.7의 `redisson-spring-boot-starter`는 Boot 4.1 기준으로 빌드돼
  있어 **스타터만 넣으면 된다**(spring-data 모듈 exclude 불필요).
- Gradle wrapper는 9.1.0. Java 25 실행을 위해 8.x가 아닌 9.x가 필요하다.
- **Boot 4.x는 자동설정을 기능별 모듈로 쪼갰다.** 3.x에서 모든 통합의
  자동설정을 담고 있던 `spring-boot-autoconfigure` 단일체가
  `spring-boot-jdbc`·`spring-boot-hibernate`·`spring-boot-jpa`처럼 나뉘었다.
  그래서 **라이브러리 의존성만 넣으면 자동설정이 붙지 않는 경우가 있다.**

  진단 신호는 이것이다 — **로그에 그 기능의 초기화 메시지가 아예 없으면**
  (실패 메시지가 아니라 *아무것도* 없으면) `spring-boot-{기능}` 모듈이
  빠졌는지 확인한다. 자동설정 클래스 자체가 클래스패스에 없으면 조건 평가에도
  걸리지 않아 조용히 통째로 빠진다.

  Flyway가 이 경우였다. `flyway-core`와 `flyway-database-postgresql`만
  넣었더니 마이그레이션이 실행되지 않고 Flyway 로그도 한 줄 남지 않아,
  JPA `validate`가 빈 스키마에 대고 실패했다.
  `org.springframework.boot:spring-boot-flyway`를 추가해 해결했다.

## 락 전략 4종 (JPA 기준 — 최건 담당)

같은 예약 확정 코드에 전략만 갈아끼우며 측정한다. 이 프로젝트의 핵심 산출물.

- 비관적 락: `@Lock(LockModeType.PESSIMISTIC_WRITE)` → `SELECT ... FOR UPDATE`
- 낙관적 락: `seat_inventory.version` 비교를 포함한 명시적 조건부 UPDATE.
  JPA `@Version`(자동 낙관적 락)이 아니다 — `concurrency-spec.md` 4.3 참조
- DB 유니크 제약: `(회차ID, 좌석ID)` unique index
- Redis 분산락: Redisson `RLock`

## 다음 단계

1. ~~좌석재고·예약 엔티티 정의 → `JPA_DDL_AUTO=validate` + Flyway 전환~~ 완료
2. 락 전략 4종 구현
3. k6 부하 테스트로 베이스라인 실패 재현 → 전략별 비교 측정
