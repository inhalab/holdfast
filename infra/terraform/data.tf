/*
 * 데이터 계층 — RDS + ElastiCache. 이슈 #42.
 *
 * **여기서부터 돈이 든다.** 앞 단계(VPC·SG·ECR)는 전부 무료였다. 단계별 apply 가
 * 이 경계를 위해 있다 — 앞단이 틀렸을 때 이것들을 세워 둔 채로 다시 돌지 않는다
 * (#42 판단 항목 A).
 *
 * <h2>로컬과 버전을 맞춘다</h2>
 *
 * PostgreSQL 18 · Redis 7. `docker-compose.yml` 과 같은 메이저다. 다르면 «로컬에서
 * 되는데 배포에서 안 된다»가 생기고, 그때 원인이 코드인지 버전인지 가릴 수 없다.
 *
 * <h2>Multi-AZ 를 켜지 않는다</h2>
 *
 * 값이 두 배가 되는데 **이 배포의 목적이 «클라우드에 배포했다»를 보이는 것**
 * 하나다(#172). 몇 시간 떠 있다 destroy 되는 DB 에 가용성을 사지 않는다.
 */

# ── 서브넷 그룹 ────────────────────────────────────────────────────────

/*
 * **퍼블릭 서브넷에 둔다.** NAT 를 안 만들었으므로 프라이빗 서브넷이 아예 없다
 * (network.tf). 노출은 `publicly_accessible = false` 와 보안그룹이 막는다 —
 * 서브넷이 퍼블릭이라는 것과 리소스가 인터넷에서 보인다는 것은 다르다.
 */
resource "aws_db_subnet_group" "main" {
  name       = local.name
  subnet_ids = [for s in aws_subnet.public : s.id]

  tags = { Name = local.name }
}

# ── DB 비밀번호 ────────────────────────────────────────────────────────

/*
 * **비밀번호를 사람이 정하지 않는다.** 로컬은 `holdfast/holdfast` 로 두지만
 * (compose — 어차피 로컬이다) 여기는 인터넷에 붙은 계정이다.
 *
 * RDS 가 거부하는 문자가 있어(`/ @ " 공백`) `override_special` 로 좁힌다.
 */
resource "random_password" "db" {
  length           = 32
  special          = true
  override_special = "!#$%&*()-_=+[]{}<>:?"
}

/*
 * **태스크 정의에 평문으로 넣지 않는다.** 태스크 정의는 콘솔에서 그대로 보이고
 * `describe-task-definition` 로도 읽힌다. SSM 에 두고 ECS 가 기동 시 꺼내 쓰게 한다
 * (app.tf 의 `secrets` 블록).
 *
 * **표준 파라미터는 무료다.** Secrets Manager 는 비밀 하나당 월 $0.40 이고, 며칠
 * 쓰는 데모에 그 값을 낼 이유가 없다.
 */
resource "aws_ssm_parameter" "db_password" {
  name  = "/${local.name}/db-password"
  type  = "SecureString"
  value = random_password.db.result

  tags = { Name = "${local.name}-db-password" }
}

# ── RDS ────────────────────────────────────────────────────────────────

resource "aws_db_instance" "main" {
  identifier = local.name

  engine         = "postgres"
  engine_version = "18"
  instance_class = "db.t3.micro"

  db_name  = "holdfast"
  username = "holdfast"
  password = random_password.db.result

  allocated_storage = 20
  storage_type      = "gp3"

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.data.id]

  # 서브넷이 퍼블릭이어도 이 값이 false 면 인터넷에서 안 보인다.
  publicly_accessible = false

  multi_az = false

  /*
   * **destroy 를 막는 것들을 전부 끈다.**
   *
   * `skip_final_snapshot = false` 면 destroy 때 스냅샷 이름을 요구하고, 안 주면
   * **실패한다 — 그리고 실패한 DB 가 남아 계속 과금된다.** `deletion_protection`
   * 도 같다. 데모용 DB 에 보호를 걸면 지키는 것은 없고 비용만 남는다.
   *
   * 백업도 끈다(`backup_retention_period = 0`). 스냅샷이 남으면 destroy 후에도
   * 보관료가 나간다 — ECR 과 같은 종류의 «남는 과금»이다.
   */
  skip_final_snapshot     = true
  deletion_protection     = false
  backup_retention_period = 0

  # 마이너 버전이 저절로 오르면 로컬과 갈릴 수 있다. 며칠 도는 DB 다.
  auto_minor_version_upgrade = false

  # 성능 인사이트는 이 크기에서 값이 없고 추가 과금 여지만 만든다.
  performance_insights_enabled = false

  /*
   * **Flyway 가 스키마를 만든다.** 앱이 기동하면서 `db/migration` 을 적용하므로
   * 여기서 만들 것은 빈 데이터베이스뿐이다(`JPA_DDL_AUTO=validate` — compose 와 같다).
   */

  tags = { Name = local.name }
}

# ── ElastiCache — 세우지 않는다 ─────────────────────────────────────────

/*
 * **한때 세웠고 회수했다**(#42 댓글 ①·②).
 *
 * 이슈 본문이 *"배포 전략이 pessimistic 고정이면 ElastiCache 를 아예 만들지
 * 않는다"*를 조건으로 적어 두었고, **그 조건이 성립한다** — 배포 전략이
 * pessimistic 이다(variables.tf).
 *
 * <h2>세우자고 했던 근거와, 그것이 왜 안 섰나</h2>
 *
 * 근거는 *"빼서 아끼는 것이 데모 한 번에 $0.06 인데, 그 값에 사는 것은 Fargate
 * 태스크 둘이 ElastiCache 로 분산락을 거는 그림"*이었다. 비용 계산은 맞다.
 *
 * **틀린 것은 «그 그림이 이 배포의 목적인가»였다.** #172 가 이 이슈의 목적을
 * «온전한 시스템을 클라우드에 배포했다»로 좁혔고 시연은 집 PC 에서 완결된다.
 * 분산락을 보이는 자리는 **로컬 시연 2절**이지 여기가 아니다. 여기에 redis 를
 * 세우는 것은 배포 증거가 아니라 **별도 데모를 하나 더 만드는 일**이었다.
 *
 * <h2>빼서 얻는 것 — 비용보다 이쪽이 크다</h2>
 *
 * - **시연과 구성이 같아진다.** 2절이 pessimistic 이므로 «같은 시스템»이 성립한다
 * - **기동 실패 지점이 하나 줄어든다.** 발표 당일에 그것이 값을 한다
 * - **destroy 대상이 하나 줄어든다.** 5절이 남는 과금을 경계한 자리이기도 하다
 *
 * <h2>되돌리려면</h2>
 *
 * `aws_elasticache_subnet_group` 과 `aws_elasticache_cluster`(engine redis 7.1,
 * cache.t3.micro, 노드 1, snapshot_retention 0)를 되살리고, app.tf 의 컨테이너에
 * REDIS_HOST·REDIS_PORT 를 넣고, security.tf 에 6379 규칙을 되살린 뒤
 * var.strategy 를 redis 로 바꾼다. **#155 덕분에 앱은 Redis 없이도 뜬다** —
 * 그래서 이 넷이 한 벌로 움직인다.
 *
 * > **헬스체크가 /api/health 인 것이 여기서 값을 한다.** /actuator/health 는
 * > Redis 가 없으면 503 을 낸다(#155 실측). ElastiCache 를 빼는 지금이 정확히
 * > 그 상황이고, app.tf 가 이미 /api/health 를 쓰고 있어 아무 일도 일어나지 않는다.
 */
