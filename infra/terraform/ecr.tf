/*
 * 앱 이미지를 둘 곳. 이슈 #42.
 *
 * **destroy 후에도 남는 유일한 과금 대상이다** — 보관료 $0.10/GB·월. 그래서
 * `force_delete` 를 켜 둔다: 이미지가 들어 있어도 `terraform destroy` 가 저장소를
 * 지운다. 켜지 않으면 destroy 가 «저장소가 비어 있지 않다»로 실패하고, **실패한
 * 쪽이 남아 계속 과금된다.**
 *
 * 로컬 compose 는 `build: ./api` 로 매번 빌드하므로 저장소가 필요 없다. 이것은
 * Fargate 만을 위한 것이다.
 */

resource "aws_ecr_repository" "app" {
  name = local.name

  # 위 주석 — destroy 가 이미지 때문에 막히면 안 된다.
  force_delete = true

  image_scanning_configuration {
    scan_on_push = false
  }

  tags = { Name = local.name }
}

/*
 * **태그 하나만 남긴다.** 테스트를 여러 번 도는 동안 `latest` 가 계속 밀리는데,
 * 밀린 이미지는 아무도 안 쓰면서 보관료만 먹는다. 열 개까지만 두고 오래된 것부터
 * 지운다 — destroy 를 잊어도 이쪽은 스스로 줄어든다.
 */
resource "aws_ecr_lifecycle_policy" "app" {
  repository = aws_ecr_repository.app.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "최근 10개만 남긴다"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 10
      }
      action = { type = "expire" }
    }]
  })
}
