/*
 * 보안그룹 — 층마다 앞 층만 받는다. 이슈 #42.
 *
 *   인터넷 ──(Cloudflare 대역만)──▶ ALB ──▶ 앱(8080) ──▶ RDS(5432)
 *                                                    └─▶ Redis(6379)
 *
 * **태스크가 퍼블릭 서브넷에 있고 퍼블릭 IP 를 받는다**(network.tf — NAT 를 안 만든
 * 대가). 그래서 «앱은 ALB 뒤에 있다»가 라우팅으로 보장되지 않고 **이 보안그룹이
 * 유일한 방어**다. 8080 을 ALB 에서만 받는 규칙이 그 자리다.
 */

# ── ALB ────────────────────────────────────────────────────────────────

resource "aws_security_group" "alb" {
  name        = "${local.name}-alb"
  description = "ALB. Cloudflare 대역에서만 받는다."
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name}-alb" }
}

/*
 * **오리진을 평문으로 두지 않는다**(infra-decision 2.1). 인증서를 하나 더 관리하는
 * 대신 **Cloudflare 를 거치지 않은 요청을 아예 받지 않는다.**
 *
 * `admin.inhalab.cloud` 를 Cloudflare Access 로 가리는 것(3.1)이 의미를 가지려면
 * **ALB 주소로 직행하는 길이 막혀 있어야 한다.** 그러지 않으면 Access 를 걸어 두고
 * 옆문을 열어 둔 것이 된다.
 *
 * 대역은 main.tf 가 cloudflare.com/ips-v4 에서 받아 온다.
 */
resource "aws_vpc_security_group_ingress_rule" "alb_from_cloudflare" {
  for_each = toset(local.cloudflare_ipv4)

  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = each.value
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
  description       = "Cloudflare edge"
}

resource "aws_vpc_security_group_egress_rule" "alb_all" {
  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

# ── 앱 ─────────────────────────────────────────────────────────────────

resource "aws_security_group" "app" {
  name        = "${local.name}-app"
  description = "Fargate 태스크. ALB 에서만 받는다."
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name}-app" }
}

resource "aws_vpc_security_group_ingress_rule" "app_from_alb" {
  security_group_id            = aws_security_group.app.id
  referenced_security_group_id = aws_security_group.alb.id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
  description                  = "ALB only"
}

/*
 * **나가는 길은 열어 둔다.** NAT 가 없으므로 태스크는 인터넷 게이트웨이로 직접
 * 나가고, 그 길로 ECR 에서 이미지를 받고 CloudWatch 로 로그를 보낸다. 막으면
 * 기동 자체가 안 된다.
 */
resource "aws_vpc_security_group_egress_rule" "app_all" {
  security_group_id = aws_security_group.app.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

# ── 데이터 계층 ────────────────────────────────────────────────────────

resource "aws_security_group" "data" {
  name        = "${local.name}-data"
  description = "RDS·ElastiCache. 앱에서만 받는다."
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name}-data" }
}

resource "aws_vpc_security_group_ingress_rule" "db_from_app" {
  security_group_id            = aws_security_group.data.id
  referenced_security_group_id = aws_security_group.app.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
  description                  = "PostgreSQL from app"
}

resource "aws_vpc_security_group_ingress_rule" "redis_from_app" {
  security_group_id            = aws_security_group.data.id
  referenced_security_group_id = aws_security_group.app.id
  from_port                    = 6379
  to_port                      = 6379
  ip_protocol                  = "tcp"
  description                  = "Redis from app"
}
