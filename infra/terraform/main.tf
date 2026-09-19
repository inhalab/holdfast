/*
 * provider 와 공통 값. 이슈 #42.
 *
 * **모든 리소스에 같은 태그를 단다.** README의 «발표 당일»이 destroy 후 확인을
 * 요구하는데, **서울 리전이 원래 비어 있지 않아 «비어 있나»로는 확인할 수 없다**
 * (#188에서 확인 — 다른 프로젝트의 VPC가 이미 있다). 태그가 «우리 것»을 가리는
 * 유일한 기준이고, `./holdfast aws down` 이 그것으로 확인한다.
 */

provider "aws" {
  region  = var.region
  profile = var.profile

  default_tags {
    tags = local.tags
  }
}

/*
 * **토큰을 변수로 받지 않는다.** 변수로 두면 `terraform.tfvars` 나 명령줄에 값이
 * 남고, 그것이 셸 기록·상태 파일로 새는 경로다. 프로바이더가 환경변수
 * `CLOUDFLARE_API_TOKEN` 을 스스로 읽으므로 그대로 둔다 — `./holdfast aws` 가
 * 루트 `.env` 의 `CF_API_TOKEN` 을 그 이름으로 넘긴다.
 */
provider "cloudflare" {}

locals {
  name = var.project

  tags = {
    Project   = var.project
    ManagedBy = "terraform"
    Issue     = "42"
  }
}

# 가용영역 둘. ALB 가 서브넷을 최소 둘 요구한다.
data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  azs = slice(data.aws_availability_zones.available.names, 0, 2)
}

/*
 * **Cloudflare 대역을 받아 온다.** ALB 를 여기로만 열어 오리진을 가린다
 * (infra-decision 2.1). **인증서 대신이 아니라 인증서와 함께다** — 대역을 좁히는
 * 것은 «누가 들어오는가»를 막고, ACM 은 «무엇이 지나가는가»를 가린다(cert.tf).
 *
 * **박아 넣지 않고 받아 오는 이유**는 이 목록이 가끔 바뀌기 때문이다. 박아 두면
 * 바뀐 줄 모르고, 그때 증상은 «어제 되던 주소가 502»다.
 *
 * plan 에 인터넷이 필요해지지만 어차피 AWS 를 부르므로 새 제약이 아니다.
 */
data "http" "cloudflare_ipv4" {
  url = "https://www.cloudflare.com/ips-v4"
}

locals {
  cloudflare_ipv4 = compact(split("\n", trimspace(data.http.cloudflare_ipv4.response_body)))
}
