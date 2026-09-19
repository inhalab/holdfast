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

output "alb_dns_name" {
  description = <<-EOT
    Cloudflare CNAME 이 가리킬 곳. **destroy 후 다시 apply 하면 이 값이 바뀐다** —
    ALB 를 새로 만들면 이름도 새로 생긴다. 그래서 레코드를 매번 맞춰야 하고,
    #204 가 그것을 자동화하려는 이유다.
  EOT
  value       = try(aws_lb.main.dns_name, "")
}

output "app_url" {
  description = <<-EOT
    ALB 직행 주소. **브라우저로는 안 열린다** — 보안그룹이 Cloudflare 대역만 받는다.
    게다가 인증서가 `app.inhalab.cloud` 용이라 이 이름으로 붙으면 TLS 경고가 난다.
  EOT
  value       = try("https://${aws_lb.main.dns_name}", "")
}

/*
 * **최건에게 넘길 값이다**(#204 — Cloudflare 계정이 갈려 있다).
 *
 * ACM 이 도메인 소유를 확인하는 CNAME 이고, **한 번만 넣으면 된다** — 계정 +
 * 도메인 단위로 안정적이라 destroy·apply 를 반복해도 재검증이 없다(cert.tf).
 *
 * `./holdfast aws cert` 가 이 값을 사람이 읽기 좋게 찍는다.
 */
output "acm_validation_records" {
  description = "Cloudflare 에 넣을 검증 CNAME. 이름 → 값. 프록시는 끈다(회색 구름)."
  value = try({
    for o in aws_acm_certificate.main.domain_validation_options :
    o.resource_record_name => o.resource_record_value
  }, {})
}

output "domains" {
  description = "Cloudflare CNAME 이 가리킬 이름 둘. 둘 다 같은 ALB 다."
  value       = [var.domain_app, var.domain_admin]
}

output "db_endpoint" {
  description = "RDS 주소. psql 로 붙으려면 보안그룹을 임시로 열어야 한다."
  value       = try(aws_db_instance.main.address, "")
}
