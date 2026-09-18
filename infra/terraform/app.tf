/*
 * 앱 계층 — ALB + ECS Fargate. 이슈 #42.
 *
 * <h2>태스크 정의 하나를 desired_count 2 로 띄운다</h2>
 *
 * **그래서 `INSTANCE_ID` 를 넣지 않는다.** 두 태스크가 같은 환경변수를 받으므로
 * 넣으면 **배지가 둘 다 같아지고 화면이 «앱이 한 대»라고 말한다**(#151).
 * 값은 앱이 태스크 메타데이터에서 읽는다 — `InstanceIdentityInitializer` 가
 * `ECS_CONTAINER_METADATA_URI_V4` 를 보고 태스크 ID 뒤 8자를 쓴다.
 *
 * 「앱 2대」는 `concurrency-spec` 7.3 의 고정 변수이고 **분산락을 쓸 이유 자체가
 * 거기서 나온다.** 배지가 안 갈리면 배포 증거가 스스로를 부정한다.
 */

# ── 로그 ───────────────────────────────────────────────────────────────

/*
 * **보존을 1일로 둔다.** destroy 후에도 로그 그룹은 남아 보관료가 나간다 —
 * ECR·스냅샷과 같은 «남는 과금»이다. Terraform 이 그룹을 지우지만, 지우기 전에
 * 쌓이는 양도 줄여 둔다.
 */
resource "aws_cloudwatch_log_group" "app" {
  name              = "/ecs/${local.name}"
  retention_in_days = var.log_retention_days

  tags = { Name = local.name }
}

# ── IAM ────────────────────────────────────────────────────────────────

/*
 * **실행 역할과 태스크 역할은 다르다.**
 *
 * - 실행 역할: **ECS 에이전트**가 쓴다. 이미지를 당기고 로그를 보내고 **SSM 에서
 *   비밀을 꺼낸다**. 앱은 이 권한을 못 본다
 * - 태스크 역할: **앱 프로세스**가 쓴다. 이 앱은 AWS API 를 부르지 않으므로
 *   **만들지 않는다** — 없는 것이 가장 좁은 권한이다
 */
data "aws_iam_policy_document" "ecs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "execution" {
  name               = "${local.name}-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json

  tags = { Name = "${local.name}-execution" }
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

/*
 * 위 관리형 정책에 **SSM 읽기가 없다.** 비밀을 꺼내는 것은 실행 역할이므로
 * 여기에 붙인다 — 이 파라미터 하나로 좁힌다.
 */
data "aws_iam_policy_document" "read_secret" {
  statement {
    actions   = ["ssm:GetParameters"]
    resources = [aws_ssm_parameter.db_password.arn]
  }
}

resource "aws_iam_role_policy" "read_secret" {
  name   = "${local.name}-read-secret"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.read_secret.json
}

# ── ALB ────────────────────────────────────────────────────────────────

resource "aws_lb" "main" {
  name               = "${local.name}-alb"
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = [for s in aws_subnet.public : s.id]

  # 데모 스택이다. 실수로 지워지는 것보다 지워지지 않는 것이 비싸다.
  enable_deletion_protection = false

  tags = { Name = "${local.name}-alb" }
}

/*
 * <h2>헬스체크는 `/api/health` 다 — `/actuator/health` 가 아니다</h2>
 *
 * **`/actuator/health` 는 Redis 가 없으면 503 을 낸다**(#42 댓글, #155 머지 후 실측).
 * 지금 배포는 `redis` 전략이라 ElastiCache 가 있지만, **전략을 바꿔 띄우는 순간
 * 타겟 둘이 전부 unhealthy 가 되고 ALB 가 503 을 낸다** — 그때 증상은 «앱은 떴는데
 * 주소가 안 열린다»라 원인을 찾기 어렵다.
 *
 * `/api/health` 는 200 을 내고 `db`·`redis` 상태를 본문에 싣는다. **#156 이
 * actuator 를 노출에서 거부하는 방향과도 맞는다** — 헬스체크가 actuator 안에
 * 있으면 그 규칙이 자기 발을 밟는다.
 */
resource "aws_lb_target_group" "app" {
  name        = "${local.name}-tg"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = aws_vpc.main.id
  target_type = "ip"

  health_check {
    path                = "/api/health"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  # Flyway 마이그레이션과 JVM 기동에 시간이 걸린다. 짧으면 첫 배포가
  # "기동 중인 태스크를 죽이고 다시 띄우는" 고리에 빠진다.
  deregistration_delay = 10

  tags = { Name = "${local.name}-tg" }
}

/*
 * `:80` HTTP/1.1 하나다. **CloudFront 를 세우지 않기로 했고**(#42 갱신 — 시연이
 * 이 이슈 밖으로 나가면서 브라우저 6커넥션 제약이 애초에 안 걸린다),
 * **TLS 는 Cloudflare 가 끝낸다**(2.1 — 인증서를 하나 더 관리하지 않는다).
 *
 * 그래서 Cloudflare 의 SSL/TLS 모드가 `Flexible` 이어야 한다(#204). `Full` 이면
 * 오리진 `:443` 으로 붙으려다 502 가 난다.
 */
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }
}

# ── ECS ────────────────────────────────────────────────────────────────

resource "aws_ecs_cluster" "main" {
  name = local.name

  tags = { Name = local.name }
}

resource "aws_ecs_task_definition" "app" {
  family                   = local.name
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.execution.arn

  container_definitions = jsonencode([{
    name      = "app"
    image     = "${aws_ecr_repository.app.repository_url}:${var.image_tag}"
    essential = true

    portMappings = [{ containerPort = 8080, protocol = "tcp" }]

    /*
     * compose 와 같은 값을 준다. **다만 셋이 다르다.**
     *
     * - `INSTANCE_ID` 를 안 넣는다 — 위 머리말. 넣으면 배지가 둘 다 같아진다
     * - `HOLDFAST_DEMO_ENABLED=false` — AWS 에 /demo/** 를 켤 이유가 없다.
     *   시연은 집 PC 에서 완결된다(#172)
     * - `HOLDFAST_ADMIN_ENABLED=true` — 3.1 이 배포에서도 켜기로 정했다.
     *   자물쇠는 앱이 아니라 Cloudflare Access 가 건다
     */
    environment = [
      { name = "PORT", value = "8080" },
      { name = "DB_HOST", value = aws_db_instance.main.address },
      { name = "DB_PORT", value = "5432" },
      { name = "DB_NAME", value = "holdfast" },
      { name = "DB_USER", value = "holdfast" },
      { name = "JPA_DDL_AUTO", value = "validate" },
      { name = "REDIS_HOST", value = aws_elasticache_cluster.main.cache_nodes[0].address },
      { name = "REDIS_PORT", value = "6379" },
      { name = "HOLDFAST_STRATEGY", value = var.strategy },
      { name = "HOLDFAST_DEMO_ENABLED", value = "false" },
      { name = "HOLDFAST_ADMIN_ENABLED", value = "true" },
      # #156. 노출을 좁힌다 — 배포는 노출된 쪽이다.
      { name = "HOLDFAST_ACTUATOR_EXPOSURE", value = "health" },
      # 홀드 TTL 은 시연값(5분)을 쓴다. 측정용 10초는 설명하는 사이에 풀린다.
      { name = "HOLD_TTL_SECONDS", value = "300" },
    ]

    secrets = [
      { name = "DB_PASSWORD", valueFrom = aws_ssm_parameter.db_password.arn },
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.app.name
        "awslogs-region"        = var.region
        "awslogs-stream-prefix" = "app"
      }
    }
  }])

  tags = { Name = local.name }
}

resource "aws_ecs_service" "app" {
  name            = local.name
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.app.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets         = [for s in aws_subnet.public : s.id]
    security_groups = [aws_security_group.app.id]

    /*
     * **켜지 않으면 기동 자체가 실패한다.** NAT 가 없으므로(3.3) 태스크는
     * 퍼블릭 IP 로 인터넷 게이트웨이를 통해 나가고, 그 길로 ECR 에서 이미지를
     * 받고 CloudWatch 로 로그를 보낸다. 끄면 «이미지를 못 받는다»로 죽는다.
     *
     * 들어오는 쪽은 보안그룹이 ALB 만 받게 막는다(security.tf).
     */
    assign_public_ip = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.app.arn
    container_name   = "app"
    container_port   = 8080
  }

  # 첫 기동은 Flyway + JVM 이라 오래 걸린다. 이 시간 동안 헬스체크 실패를 무시한다.
  health_check_grace_period_seconds = 120

  # 리스너가 있어야 타겟 등록이 된다. 없으면 "target group not associated" 로 실패한다.
  depends_on = [aws_lb_listener.http]

  tags = { Name = local.name }
}
