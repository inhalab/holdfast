/*
 * ACM 인증서 — 오리진 구간도 암호화한다. 이슈 #42.
 *
 * <h2>왜 붙이나 — Flexible 이 덮지 못하는 구간이 있다</h2>
 *
 * **한때 인증서를 안 붙이기로 했다**(infra-decision 2.1). Cloudflare 무료 플랜이
 * 엣지에서 TLS 를 주므로 *"인증서를 하나 더 관리하지 않고 오리진을 가리는 실용적인
 * 답"*으로 보안그룹 제한만 걸었다.
 *
 * **그 판단은 구간을 하나로 봤다. 실제로는 둘이다.**
 *
 * <pre>
 *   브라우저 ──(1)──▶ Cloudflare ──(2)──▶ ALB
 * </pre>
 *
 * Cloudflare 의 `Flexible` 모드가 암호화하는 것은 **(1)뿐이고 (2)는 평문**이다.
 * 보안그룹이 (2)에 «누가 들어오는가»를 제한하지만 **«무엇이 지나가는가»는 그대로
 * 노출된다** — 둘은 다른 문제다.
 *
 * **ACM 공인 인증서는 무료다.** 관리 비용이 «한 번 검증하고 자동 갱신»이므로
 * 2.1 이 피하려던 «인증서를 하나 더 관리한다»의 무게가 처음 생각보다 가볍다.
 *
 * <h2>검증 CNAME 은 한 번만 넣으면 된다</h2>
 *
 * DNS 검증 레코드는 **계정 + 도메인 단위로 안정적**이다(AWS 문서). 같은 계정에서
 * 같은 도메인 인증서를 다시 발급해도 **그 CNAME 이 남아 있으면 재검증이 없다.**
 *
 * **와일드카드라 그 레코드가 하나다.** 이름을 둘 넣었으면 둘이었다.
 *
 * **그래서 destroy·apply 를 반복해도 사람 손이 다시 필요하지 않다.** 이 구성에서
 * 중요한 성질이다 — 인증서가 매번 새 CNAME 을 요구했다면 apply 마다 다른 사람을
 * 기다려야 했다(#204: Cloudflare 계정이 갈려 있다).
 *
 * <h2>단계가 갈리는 이유</h2>
 *
 * `aws_acm_certificate` 는 즉시 만들어지고 **검증 레코드를 출력한다.**
 * `aws_acm_certificate_validation` 은 그 레코드가 DNS 에 뜰 때까지 **기다린다.**
 *
 * 그래서 인증서는 `net` 단계(무료)에 두고 검증은 `app` 단계에 둔다 — 검증
 * CNAME 을 넣기 전에 apply 하면 기다리다 죽는 자리를 뒤로 민 것이다.
 */

resource "aws_acm_certificate" "main" {
  /*
   * **와일드카드 하나로 받는다.** 이름 둘(`app`·`admin`)을 따로 넣으면 검증
   * CNAME 도 둘이 되고, 그 둘을 다른 사람이 손으로 넣어야 한다(#204).
   *
   * `*.inhalab.cloud` 는 **검증 레코드가 하나**이고, 나중에 호스트를 더 붙여도
   * 인증서를 다시 발급할 필요가 없다. 사람 손이 한 번으로 끝난다.
   *
   * **1단계까지만 덮는다** — `a.b.inhalab.cloud` 는 안 된다. 3단계 서브도메인을
   * 쓰지 않기로 이미 정했으므로(infra-decision 2.1, Universal SSL 과 같은 제약)
   * 새로 생기는 제약이 아니다.
   *
   * **개인키는 ACM 밖으로 나오지 않는다.** 와일드카드가 넓다는 부담이 «키가 새면
   * 전부»인데, 내보낼 수 없는 키라 그 경로가 없다.
   */
  domain_name       = var.domain_wildcard
  validation_method = "DNS"

  /*
   * **갱신할 때 서비스가 안 끊기게 한다.** 인증서를 바꾸는 apply 에서 리스너가
   * 참조하는 동안 옛 인증서가 먼저 사라지면 그 순간 ALB 가 TLS 를 못 준다.
   */
  lifecycle {
    create_before_destroy = true
  }

  tags = { Name = local.name }
}

/*
 * **검증이 끝날 때까지 기다린다.** 리스너가 이 리소스의 ARN 을 쓰므로, 검증 전에는
 * `:443` 리스너가 만들어지지 않는다 — 인증서 없이 뜬 리스너가 TLS 를 못 주는 상태를
 * 막는다.
 *
 * **레코드는 이제 Terraform 이 넣는다**(#204) — dns.tf 의 `acm_validation` 이다.
 * 한때 «사람이 넣는다»였고, 좁게 받은 토큰 하나로 그 자리가 사라졌다.
 *
 * **그런데 그 사실이 코드에 안 적혀 있었다.** `validation_record_fqdns` 를 인증서
 * 쪽에서 읽으므로 Cloudflare 레코드와 의존 관계가 생기지 않는다.
 *
 * **전체 apply 는 이것 없이도 잘 돈다** — 레코드 만들기가 1초고 이 리소스는 10분을
 * 폴링하니, 순서가 보장돼 있지 않아도 시간 차가 커서 사실상 늘 통과한다. 1차
 * 테스트가 30초 만에 발급된 것이 그 증거다. 그러므로 이 `depends_on` 은 순서를
 * 고치려는 것이 아니다. 실익은 둘이다.
 *
 * - **`-target` 에서는 확정적으로 깨진다.** 의존이 없으면 레코드가 그래프에서
 *   잘려 아예 만들어지지 않는다. edge 단계에서 실제로 그렇게 죽었다
 * - **레코드 생성이 실패했을 때 진짜 원인으로 즉시 죽는다.** 토큰이 틀렸다면
 *   지금까지는 10분을 헛기다린 뒤 «인증서가 PENDING 이다»라는 엉뚱한 오류가
 *   났다 — 원인에서 가장 먼 자리에서 나는 오류다
 *
 * **값은 ACM 에서 읽고 순서만 못 박는다.** Cloudflare 쪽에서 값을 읽어도 되지만,
 * 그러면 «ACM 이 요구한 것»과 «우리가 넣은 것»이 같은지 보는 검사가 사라진다.
 */
resource "aws_acm_certificate_validation" "main" {
  certificate_arn = aws_acm_certificate.main.arn

  validation_record_fqdns = [
    for o in aws_acm_certificate.main.domain_validation_options : o.resource_record_name
  ]

  depends_on = [cloudflare_dns_record.acm_validation]

  timeouts {
    # 10분 안에 안 되면 레코드가 잘못 들어간 것이다. 그때는 멈추는 편이 낫다.
    create = "10m"
  }
}
