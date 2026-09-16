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
    배포할 락 전략. **redis 로 띄운다** — Fargate 태스크 둘이 ElastiCache로 분산락을
    거는 그림이 이 프로젝트가 클라우드에서 보일 수 있는 가장 강한 구성이다(#42 댓글 ①).
    pessimistic 은 한 DB 안에서 끝나 «분산»이 화면에 안 나온다.
  EOT
  type        = string
  default     = "redis"

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
