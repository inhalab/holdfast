/*
 * 입력값. 이슈 #42.
 *
 * **기본값이 곧 판정이다.** 아래 값들은 대부분 문서에서 정해져 온 것이고, 바꾸면
 * 그 판정을 어기는 것이다 — 어디서 왔는지 각 변수에 적었다.
 */

variable "project" {
  description = "모든 리소스에 붙는 이름·태그의 뿌리. 태그가 destroy 확인의 유일한 기준이다(README «발표 당일»)."
  type        = string
  default     = "holdfast"
}

variable "region" {
  description = "서울. #188에서 고정했다 — 리전이 섞이면 destroy 후 남은 것을 놓친다."
  type        = string
  default     = "ap-northeast-2"
}

variable "profile" {
  description = <<-EOT
    AWS 자격증명 프로필. **기본 프로필에 기대지 않는다** — 이 기계의 기본은 다른
    프로젝트의 사용자이고, 그 상태로 apply 하면 엉뚱한 계정에 리소스가 선다(#188).
  EOT
  type        = string
  default     = "holdfast"
}

variable "vpc_cidr" {
  description = <<-EOT
    **10.0.0.0/16을 쓰지 않는다.** 서울 리전에 다른 프로젝트의 VPC가 그 대역으로
    이미 있다(#188에서 확인). 같은 대역이면 destroy 후 남은 VPC를 보고 «안
    지워졌나»를 의심하게 된다. 42는 이 이슈 번호다.
  EOT
  type        = string
  default     = "10.42.0.0/16"
}

variable "strategy" {
  description = <<-EOT
    배포할 락 전략. **pessimistic 이다** — 시연(2절)이 그것으로 돌고, 이 배포의
    목적은 «온전한 시스템을 클라우드에 배포했다»를 보이는 것이므로(#172) **구성이
    같은 쪽이 그 문장에 맞다.**

    **한때 redis 였고 회수했다**(#42 댓글 ①·②). 그때 근거는 "태스크 둘이
    ElastiCache로 분산락을 거는 그림이 강하다"였는데, 그것은 배포 증거가 아니라
    **별도 데모를 하나 더 만드는 쪽**이다. 분산락을 보이는 자리는 로컬 시연이다.

    바꾸려면 ElastiCache 를 함께 세워야 한다 — data.tf 가 그것을 적어 두었다.
  EOT
  type        = string
  default     = "pessimistic"

  validation {
    # holdfast 스크립트·StrategyArgumentTest 와 같은 다섯이다.
    condition     = contains(["none", "pessimistic", "optimistic", "unique", "redis"], var.strategy)
    error_message = "전략은 none|pessimistic|optimistic|unique|redis 중 하나여야 한다."
  }
}

variable "image_tag" {
  description = "ECR 이미지 태그. ./holdfast aws push 가 찍는 값과 같아야 한다."
  type        = string
  default     = "latest"
}

variable "task_cpu" {
  description = "1 vCPU. infra-decision 3절의 비용 계산이 이 값 기준이다."
  type        = number
  default     = 1024
}

variable "task_memory" {
  description = "2 GB. 위와 같다."
  type        = number
  default     = 2048
}

variable "desired_count" {
  description = <<-EOT
    **앱 2대는 이 프로젝트의 전제다**(concurrency-spec 7.3의 고정 변수). 분산락을
    쓸 이유 자체가 거기서 나오고, 1이면 화면이 스스로를 부정한다.
  EOT
  type        = number
  default     = 2
}

variable "log_retention_days" {
  description = "1일. 방치 비용을 끊는다 — destroy 후에도 로그는 남아 과금된다."
  type        = number
  default     = 1
}

variable "test_cidr" {
  description = <<-EOT
    **첫 배포를 스스로 확인하기 위한 탈출구다.** 비워 두면 아무 일도 안 한다.

    ALB 보안그룹이 Cloudflare 대역만 받으므로(security.tf), **DNS 가 붙기 전에는
    우리가 배포를 확인할 길이 없다** — Cloudflare 계정은 다른 사람 것이고(#204)
    그쪽이 준비되기를 기다리는 동안 «떴는지조차 모르는» 상태가 된다.

    여기에 내 공인 IP(`x.x.x.x/32`)를 주면 그 주소에서만 ALB 가 열린다.
    **확인이 끝나면 비우고 apply 한다** — 남겨 두면 Cloudflare 를 거치지 않는
    옆문이 되고, admin 에 Access 를 건 것이 무의미해진다(3.1).

    내 IP: `curl ifconfig.me`
  EOT
  type        = string
  default     = ""

  validation {
    condition     = var.test_cidr == "" || can(cidrnetmask(var.test_cidr))
    error_message = "빈 문자열이거나 CIDR 표기여야 한다 (예: 1.2.3.4/32)."
  }
}

variable "domain_app" {
  description = <<-EOT
    사용자 화면 주소. **공개**다. ACM 인증서의 주 도메인이고 Cloudflare 가 이
    이름으로 오리진에 붙는다.
  EOT
  type        = string
  default     = "app.inhalab.cloud"
}

variable "domain_admin" {
  description = <<-EOT
    관리자 주소. **Cloudflare Access 가 가린다**(infra-decision 3.1) — 앱은 그
    자물쇠를 모른다. 같은 ALB 를 가리키고 가르는 것은 Cloudflare 다.

    ACM 인증서에 SAN 으로 함께 넣는다. 인증서가 둘이면 리스너도 둘이거나
    SNI 설정이 늘어서, 하나에 담는 편이 단순하다.
  EOT
  type        = string
  default     = "admin.inhalab.cloud"
}

variable "domain_wildcard" {
  description = <<-EOT
    ACM 인증서가 덮는 범위. **와일드카드 하나로 받는다** — 이름을 따로 넣으면
    검증 CNAME 이 이름 수만큼 생기고, 그것을 다른 사람이 손으로 넣어야 한다(#204).

    1단계까지만 덮는다. 3단계 서브도메인은 이미 안 쓰기로 했다(2.1).
  EOT
  type        = string
  default     = "*.inhalab.cloud"
}

variable "zone_name" {
  description = <<-EOT
    Cloudflare 존 이름. **ID 가 아니라 이름으로 받는다** — 관리할 값이 하나 줄고,
    ID 가 틀렸을 때 증상이 «레코드가 엉뚱한 존에 생긴다»로 조용하다.

    토큰(`CF_API_TOKEN`)은 저장소에 없다. 루트 `.env` 에 두고 .gitignore 가 막는다.
  EOT
  type        = string
  default     = "inhalab.cloud"
}
