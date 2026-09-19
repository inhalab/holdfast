/*
 * 버전 고정. 이슈 #42.
 *
 * **provider 버전을 느슨하게 두지 않는다.** 이 구성은 몇 달 뒤 발표 직전에 한 번
 * 더 돌아야 하는데, 그 사이 provider가 올라가 plan이 달라지면 그때 디버깅하게
 * 된다. 지금 도는 조합을 박아 둔다.
 *
 * `.terraform.lock.hcl`은 `.gitignore`에 있다 — 저장소 규칙이고 여기서 바꾸지
 * 않는다. 그래서 버전 고정이 이 파일 하나에 달려 있다.
 */

terraform {
  required_version = ">= 1.9"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
    http = {
      source  = "hashicorp/http"
      version = "~> 3.4"
    }
    /*
     * **DNS 를 사람이 옮기지 않게 한다**(#204). ALB 주소는 destroy·apply 마다
     * 바뀌는데, Cloudflare 계정이 다른 사람 것이라 매번 그 사람을 기다려야 했다 —
     * 테스트 세 번에 실행 한 번이면 네 번이고 그중 한 번이 가장 나쁜 때다.
     */
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 5.0"
    }
  }
}
