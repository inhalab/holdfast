/*
 * VPC — 퍼블릭 서브넷만. 이슈 #42.
 *
 * <h2>NAT Gateway 를 만들지 않는다</h2>
 *
 * **infra-decision 5절이 «깜빡하면 가장 크게 샌다»고 특히 경고한 항목**이고 3.3이
 * 만들지 않기로 정했다. 시간당 과금이라 데모를 내려도 남아 있으면 계속 나간다.
 *
 * **퍼블릭 서브넷에 두면 필요가 없다.** 태스크가 인터넷 게이트웨이로 직접 나가
 * ECR 에서 이미지를 받고, RDS·ElastiCache 에는 같은 VPC 안에서 프라이빗 IP 로 닿는다.
 *
 * **대가를 적어 둔다.** 태스크가 퍼블릭 IP 를 받는다. 그것을 막는 것은 보안그룹이고
 * (security.tf), ALB 를 거치지 않은 8080 직행은 거기서 끊긴다.
 *
 * > `./holdfast aws up` 이 plan 에 `aws_nat_gateway` 가 있으면 apply 를 거부한다.
 * > 판정을 문서에만 두지 않고 실행 경로에 묶은 것이다.
 */

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = local.name }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = { Name = local.name }
}

/*
 * 서브넷 둘. **ALB 가 최소 두 가용영역을 요구하므로 둘이 최소값이다** — 고가용성을
 * 노린 것이 아니라 ALB 의 제약이다.
 *
 * `/20` 씩 끊는다. 태스크 둘과 DB 하나에 과한 크기지만, 좁게 잡아 나중에 막히는
 * 것보다 낫고 주소는 공짜다.
 */
resource "aws_subnet" "public" {
  for_each = { for i, az in local.azs : az => i }

  vpc_id            = aws_vpc.main.id
  availability_zone = each.key
  cidr_block        = cidrsubnet(var.vpc_cidr, 4, each.value)

  # **퍼블릭 IP 를 자동으로 준다.** NAT 가 없으므로 이것이 없으면 태스크가
  # ECR 에 닿지 못해 기동 자체가 실패한다 — 증상이 «이미지를 못 받는다»로 나온다.
  map_public_ip_on_launch = true

  tags = { Name = "${local.name}-public-${each.key}" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = { Name = "${local.name}-public" }
}

resource "aws_route_table_association" "public" {
  for_each = aws_subnet.public

  subnet_id      = each.value.id
  route_table_id = aws_route_table.public.id
}
