# 협업 규칙

## 기본 흐름

`main`은 직접 push가 막혀 있다. 모든 변경은 브랜치를 만들어 PR로 올린다.

```bash
git switch main && git pull          # 최신화
git switch -c infra/ecs-setup        # 브랜치 생성
# 작업, 커밋
git push -u origin HEAD              # 현재 브랜치명 그대로 푸시
gh pr create --fill                  # 커밋 메시지로 PR 생성
```

CI를 확인하고 머지한다.

```bash
gh pr checks --watch                 # 초록불 대기
gh pr merge --squash --delete-branch # 머지 + 브랜치 삭제
git switch main && git pull          # 로컬 최신화
```

**승인은 필요 없다.** CI만 통과하면 혼자 머지할 수 있다. 2인 프로젝트에서 승인을 강제하면 서로 대기하다 멈추기 때문이다. 다만 상대가 봐야 할 변경이면 리뷰어를 지정한다.

## 브랜치 이름

| 접두사 | 용도 | 예 |
|---|---|---|
| `feat/` | 기능 추가 | `feat/seat-hold` |
| `fix/` | 버그 수정 | `fix/expire-race` |
| `infra/` | 인프라, 배포, CI | `infra/ecs-setup` |
| `docs/` | 문서 | `docs/concurrency-design` |
| `chore/` | 설정, 잡무 | `chore/update-codeowners` |

## 커밋 메시지

```
<type>: <한 줄 요약>
```

type은 브랜치 접두사와 같은 어휘를 쓴다. 한국어로 쓰되 명령형보다 서술형이 읽기 편하다.

```
feat: 좌석 선점 API 추가
fix: 홀드 만료와 확정의 경합 처리
docs: 동시성 설계서 추가
```

머지는 squash로만 하므로 작업 중 커밋은 자유롭게 쪼개도 된다. main 히스토리에는 PR 하나가 커밋 하나로 남는다.

## PR 규칙

템플릿(`.github/pull_request_template.md`)의 세 항목을 채운다.

```markdown
## 무엇을
## 왜
## 확인 방법

관련 이슈: #
```

관련 이슈 번호를 적으면 이슈 페이지에서 역참조가 보인다. `Closes #12`라고 쓰면 머지 시 이슈가 자동으로 닫힌다.

## CODEOWNERS

`.github/CODEOWNERS`에 정의된 경로를 건드리면 담당자가 자동으로 리뷰어에 붙는다. 승인 강제는 아니고 알림 역할이다. 접근 권한이 아니라 리뷰 책임의 구분이므로 상대 영역도 필요하면 손댄다.

## 이슈와 보드

작업은 [로드맵 보드](https://github.com/orgs/inhalab/projects/1)의 **Priority 숫자 순서**대로 진행한다. 담당자별로 그룹이 나뉘어 있고 각자 1부터 시작한다. Priority `0`은 해당 마일스톤의 진행 순서를 담은 트래킹 이슈다.

새 작업이 생기면 이슈를 먼저 만들고 보드에 올린다.

```bash
gh issue create -R inhalab/holdfast \
  -t "제목" -l "area:api" -m "M1 설계 및 환경 구축" -a Asirante -b "본문"
```

라벨은 `area:api` / `area:infra` / `area:docs`, `type:feat` / `type:fix` / `type:chore`, 그리고 동시성 관련이면 `concurrency`를 붙인다.

## 문서를 코드와 함께 갱신한다

**M4 착수 전 문서 감사에서 사실 오류 10건·문서 간 충돌 7건·폐기된 전제 9건이
나왔다.** 하나하나는 사소했지만 한 가지 종류로 쌓였다 — **판단이 바뀔 때 코드는
고치고 문서는 두었다.** 아래 여섯은 그 감사에서 뽑은 규칙이다.

### 1. 구현이 문서와 갈리면 같은 PR에서 문서도 고친다

코드 주석에 "문서 N절은 X라고 하지만 여기서는 Y"라고 쓰는 것은 이탈을
**기록한** 것이지 **해소한** 것이 아니다. 그 절에 갱신 블록을 함께 단다.

`erd.md` 4.1의 `redis` 행이 그 형태다("이 표가 처음 적은 것은 …이었으나 구현은
그렇게 하지 않았다"). 같은 표의 `optimistic` 행은 그러지 못해, **구현과 정반대인
서술이 세 달 남아 있었다.**

### 2. 측정을 반영하는 PR은 표만이 아니라 그 표가 무효화한 전제까지 범위에 넣는다

**측정 반영 PR 체크리스트** — 이번에 얻은 숫자가 아래 셋 중 무엇을 뒤집었는지
확인하고, 뒤집었으면 같은 PR에서 처리한다.

- [ ] **사전 가설** — 이 숫자가 가설을 확증했나, 반증했나, 검증 불가로 만들었나?
      (`concurrency-spec.md` 7.5·7.7)
- [ ] **지표 표의 목표 열** — "0 기대" 같은 기대값이 이 전략에도 맞나? (7.1)
- [ ] **결론 절의 제목과 범위 서술** — "남은 세 전략", "네 전략까지" 같은 진행
      상황이 제목에 박혀 있지 않나? (7.7)
- [ ] **폐기 기록** — 회수한 전제를 지우지 않고
      `docs/results/discarded-measurements.md`로 옮겼나?

**표를 채우는 일은 이슈에 있고 전제를 회수하는 일은 어느 이슈에도 없다.** 그래서
체크리스트로 둔다.

### 3. 시제가 든 문장에는 마일스톤 표식을 붙인다

"아직 구현되지 않았다"는 쓸 수 있다. 다만 **언제 기준인지 함께 적는다** —
"(M2 기준)". 그 마일스톤이 지나면 무조건 재확인 대상이 된다.

표식이 없어서, `load-test/README.md`의 "예약 API는 아직 구현되지 않았다"가
60회를 측정한 뒤에도 그대로 남아 있었다.

### 4. 절 제목에 진행 상황을 넣지 않는다

"네 전략까지", "남은 세 전략"은 본문에 쓸 말이지 제목에 쓸 말이 아니다. 제목에
박히면 본문만 갱신했을 때 둘이 어긋난다.

### 5. 파일을 추가·개명하면 그 디렉토리 README의 트리를 같은 PR에서 고친다

`load-test/README.md`의 구성 트리에서 파일 다섯 개가 빠져 있었다. 트리는 처음 쓸
때 한 번 만들고 그 뒤로 아무도 손대지 않았기 때문이다.

### 6. 미래형 조건 액션은 해소 조건과 함께 적고, 조건이 충족된 PR에서 회수한다

"부하 테스트에서 데드락이 계속 관측되면 개별 락으로 전환한다"처럼 **조건부로 적어
둔 대비책**은 조건이 판정된 순간 닫는다. 닫지 않으면 이미 결론이 난 문제가
미해결로 보인다.

## 하지 말 것

**`.gitignore`의 `*.tfstate`, `.terraform/`, `.env`를 지우지 않는다.** 빠지면 AWS 리소스 구성과 자격증명이 통째로 공개된다.

**AWS는 정적 액세스 키 대신 OIDC를 쓴다.** IAM에 GitHub OIDC Provider를 등록하고 Role ARN만 Environment secret으로 넣는다. secret scanning의 push protection이 켜져 있어 키가 커밋되려 하면 push가 막히지만, 애초에 만들지 않는 것이 낫다.

**`terraform apply`를 자동화하지 않는다.** `plan`까지만 CI에서 돌리고 `apply`는 수동으로 한다. 학생 계정에서 사고가 나면 요금이 붙는다.

**main에서 직접 작업하지 않는다.** 브랜치를 만든 뒤 프롬프트가 바뀐 것을 확인하고 시작한다. main에 커밋한 뒤 push하면 규칙 위반으로 거부되고, 커밋을 옮기는 작업이 추가로 필요해진다.

## 자주 쓰는 명령

```bash
# 내가 만든 브랜치 확인
git branch --show-current

# 열려 있는 내 이슈
gh issue list -R inhalab/holdfast --assignee @me

# PR 상태
gh pr status

# 특정 PR의 CI 로그 (실패 시)
gh run view --log-failed
```

## 사고 복구

**main에 실수로 커밋했을 때** — 커밋을 새 브랜치로 옮기고 main을 되돌린다.

```bash
git switch -c feat/작업이름          # 현재 커밋을 그대로 가진 브랜치 생성
git switch main
git reset --hard origin/main         # main만 원격 상태로 복구
git switch feat/작업이름
```

**커밋을 날렸을 때** — `git reflog`에서 해시를 찾아 복구한다.

```bash
git reflog                           # q로 나감
git reset --hard <해시>
```

## Git Bash 사용 시 주의

여러 줄을 한 번에 붙여넣으면 중간에 잘리거나 `[200~` 같은 문자가 섞인다. **한 줄씩 실행하고 프롬프트가 `$`로 돌아온 것을 확인**한 뒤 다음 줄로 넘어간다.

긴 파일을 만들 때는 heredoc(`cat > file <<EOF`) 대신 에디터를 쓴다. 마크다운 문서는 특히 VS Code로 직접 편집한다. 다른 에디터를 거치면 `\#`, `\*\*`처럼 이스케이프되어 렌더링이 깨질 수 있다.
