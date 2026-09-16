/*
 * 출력. 이슈 #42.
 *
 * **`./holdfast aws ...` 가 읽는 값들이다.** 사람이 콘솔에서 찾아 적는 대신
 * 스크립트가 `terraform output -raw` 로 가져간다 — 주소를 손으로 옮기다 틀리는
 * 일이 시연 당일에 가장 비싸다.
 *
 * **단계별 apply 중에는 일부가 비어 있다.** `net` 단계에서는 ALB 가 아직 없으므로
 * `app_url` 이 빈 문자열이다. 그 상태가 정상이고, 스크립트가 비어 있으면 «아직
 * 안 섰다»로 읽는다.
 */

output "region" {
  description = "리소스가 선 리전. aws CLI 를 부를 때 쓴다."
  value       = var.region
}

output "ecr_repository_url" {
  description = "이미지를 밀 곳. ./holdfast aws push 가 읽는다."
  value       = aws_ecr_repository.app.repository_url
}

output "vpc_id" {
  description = "destroy 후 확인에 쓴다 — 태그로 찾은 것과 같은지 본다."
  value       = aws_vpc.main.id
}
