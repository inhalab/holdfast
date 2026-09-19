/*
 * Cloudflare DNS — 사람을 기다리지 않게 한다. 이슈 #42 · #204.
 *
 * <h2>왜 자동화하나</h2>
 *
 * **ALB 의 DNS 이름은 `terraform destroy` → `apply` 마다 새로 생긴다.** 그래서
 * CNAME 을 그때마다 고쳐야 하는데, **Cloudflare 계정이 다른 사람 것이다**(#183:
 * 도메인은 최건이 샀고 최건 계정에 있다).
 *
 * 손으로 하면 **테스트 세 번 + 실행 한 번 = 네 번**이고, 그중 한 번이 가장 나쁜
 * 때다. 자동화하면 그 자리에 사람이 없어도 된다.
 *
 * <h2>토큰은 좁게 받았다</h2>
 *
 * `Zone:DNS:Edit` 하나다. 최악의 경우가 «레코드가 엉뚱한 데를 가리킨다»이고,
 * 그것은 계정 주인이 콘솔에서 되돌릴 수 있다 — #183 이 경계한 터널 자격증명
 * (있으면 누구나 그 터널로 트래픽을 받는다)과 무게가 다르다.
 *
 * **토큰은 저장소에 없다.** 루트 `.env` 에 두고 `.gitignore` 가 막는다.
 */

/*
 * **존을 이름으로 찾는다.** ID 를 변수로 받지 않는 이유는 관리할 값이 하나 줄고,
 * 그 값이 틀렸을 때 증상이 «레코드가 엉뚱한 존에 생긴다»로 조용하기 때문이다.
 * 토큰에 `Zone:Read` 가 붙어 있어 이름으로 조회된다.
 */
data "cloudflare_zone" "main" {
  filter = {
    name = var.zone_name
  }
}

/*
 * <h2>1. ACM 검증 레코드</h2>
 *
 * **프록시를 켜면 안 된다.** ACM 은 이 CNAME 을 그대로 따라가야 하는데, 프록시를
 * 켜면 Cloudflare 가 자기 주소로 답해 **검증이 영원히 끝나지 않는다.**
 *
 * 와일드카드 인증서라 **레코드가 하나**다(cert.tf).
 */
resource "cloudflare_dns_record" "acm_validation" {
  for_each = {
    for o in aws_acm_certificate.main.domain_validation_options :
    o.domain_name => o
  }

  zone_id = data.cloudflare_zone.main.zone_id
  name    = trimsuffix(each.value.resource_record_name, ".")
  type    = each.value.resource_record_type
  content = trimsuffix(each.value.resource_record_value, ".")
  ttl     = 60
  proxied = false

  comment = "ACM validation - managed by terraform (holdfast #42)"
}

/*
 * <h2>2. 서비스 레코드 — 프록시를 반드시 켠다</h2>
 *
 * **회색 구름이면 타임아웃난다.** ALB 보안그룹이 Cloudflare 대역만 받으므로
 * (security.tf), 프록시가 꺼져 있으면 브라우저가 ALB 로 직행하다 막힌다 —
 * **502 가 아니라 무응답**이라 원인을 찾기 어려운 증상이다.
 *
 * 그리고 프록시가 꺼지면 **`admin` 의 Cloudflare Access 도 함께 무력해진다**
 * (3.1). 트래픽이 엣지를 안 지나므로 자물쇠를 지날 일이 없다.
 *
 * **둘 다 같은 ALB 를 가리킨다.** 가르는 것은 Cloudflare 이고, `admin` 쪽에만
 * Access 가 걸린다 — infra-decision 2.1 의 도메인 표가 그렇게 적혀 있다.
 */
resource "cloudflare_dns_record" "service" {
  for_each = toset([var.domain_app, var.domain_admin])

  zone_id = data.cloudflare_zone.main.zone_id
  name    = each.value
  type    = "CNAME"
  content = aws_lb.main.dns_name
  ttl     = 1 # 프록시를 켜면 TTL 은 Cloudflare 가 정한다. 1 = automatic.
  proxied = true

  comment = "holdfast ALB - managed by terraform (#42)"
}
