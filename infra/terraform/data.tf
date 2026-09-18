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

resource "aws_elasticache_subnet_group" "main" {
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

# ── ElastiCache ────────────────────────────────────────────────────────

/*
 * **뺄 수 있었지만 세운다**(#42 댓글 ①). 이슈 본문이 *"배포 전략이 pessimistic
 * 고정이면 아예 만들지 않는다"*고 적었는데, **빼서 아끼는 것이 시간당 $0.026,
 * 데모 한 번에 $0.06 이다.**
 *
 * 그 값에 사는 것은 **Fargate 태스크 둘이 ElastiCache 로 분산락을 거는 그림**이고,
 * 이 프로젝트가 클라우드에서 보일 수 있는 것 중 가장 강한 구성이다. `pessimistic`
 * 은 한 DB 안에서 끝나 «분산»이 화면에 안 나온다.
 *
 * 그래서 `var.strategy` 기본값이 `redis` 다.
 */
resource "aws_elasticache_cluster" "main" {
  cluster_id = local.name

  engine         = "redis"
  engine_version = "7.1"
  node_type      = "cache.t3.micro"

  # 노드 하나. 복제도 클러스터 모드도 쓰지 않는다 — 로컬 compose 의 redis 한 대와
  # 같은 모양이고, 분산락이 증명하는 것은 «앱이 여럿»이지 «캐시가 여럿»이 아니다.
  num_cache_nodes = 1

  parameter_group_name = "default.redis7"
  port                 = 6379

  subnet_group_name  = aws_elasticache_subnet_group.main.name
  security_group_ids = [aws_security_group.data.id]

  # 스냅샷을 남기지 않는다. 남으면 destroy 후에도 보관료가 나간다.
  snapshot_retention_limit = 0

  tags = { Name = local.name }
}
