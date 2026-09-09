# Cloudflare Tunnel — 준비 절차

이슈 #174. 판정과 근거는 [`docs/infra-decision.md`](../../docs/infra-decision.md) 2.1에
있고, **허용 목록의 정본도 거기다.** `config.yml`은 그 표의 구현이다.

## 왜 필요한가

**서버는 집 PC에 있고 발표자는 발표장에 있다.** PC를 가져가지 않으므로
`localhost`로 못 본다. 터널은 **시연 장치가 아니라 접근 수단**이다.

**청중은 붙지 않는다**(`demo-script.md` 0.1). 붙는 사람은 발표자 하나다.

## 한 번만 하는 것 — 계정 작업

`cloudflared`는 컨테이너로 도므로 **설치할 것은 없다.** 다만 아래는 Cloudflare
계정에서 사람이 해야 한다.

### 1. 도메인을 Cloudflare에 올린다

`inhalab.cloud`의 네임서버를 Cloudflare로 바꾼다. 무료 플랜이면 된다.

### 2. 터널을 만들고 자격증명을 받는다

```bash
docker run --rm -it -v "$PWD/infra/tunnel:/home/nonroot/.cloudflared" \
  cloudflare/cloudflared:2025.8.1 tunnel login
docker run --rm -it -v "$PWD/infra/tunnel:/home/nonroot/.cloudflared" \
  cloudflare/cloudflared:2025.8.1 tunnel create holdfast-demo
```

만들어진 `<UUID>.json`을 **`credentials.json`으로 이름을 바꾼다.** 그리고 그
UUID를 `TUNNEL_ID`로 쓴다 — 저장소 루트 `.env`에 넣으면 된다.

```
TUNNEL_ID=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
```

> **`credentials.json`과 `.env`는 `.gitignore`에 있다.** 이 파일이 있으면 누구나
> 이 터널로 트래픽을 받을 수 있다.

### 3. DNS 세 개를 터널로 보낸다

```bash
for h in demo-none demo-pess demo-admin; do
  docker run --rm -v "$PWD/infra/tunnel:/home/nonroot/.cloudflared" \
    cloudflare/cloudflared:2025.8.1 tunnel route dns holdfast-demo "$h.inhalab.cloud"
done
```

**3단계 서브도메인(`demo.none.…`)은 쓰지 않는다.** 무료 플랜의 Universal SSL이
1단계까지만 커버해 인증서 오류가 난다.

### 4. Cloudflare Access를 **세 호스트명 모두**에 건다

Zero Trust → Access → Applications → Self-hosted.

- 도메인: `demo-none.inhalab.cloud` · `demo-pess.inhalab.cloud` ·
  `demo-admin.inhalab.cloud` (또는 `demo-*.inhalab.cloud` 와일드카드 하나)
- 정책: **Emails** → 본인 이메일 둘
- **세션 수명을 1주로 늘린다** — 발표장에서 코드를 기다리며 3절이 멈추면 안 된다

> **왜 셋 다인가.** #174가 `/admin`에 Access를 걸기로 하면서 **호스트명 분리는
> 접근 제어가 아니다**라고 적었다 — 추측되고 인증서 투명성 로그에 실린다.
> **그 논거가 `demo-none`·`demo-pess`에도 그대로 걸린다.** 붙는 사람이 발표자
> 하나뿐이므로 셋 다 거는 데 잃는 것이 없다.
>
> 그러면 `config.yml`의 경로 목록은 **유일한 경계가 아니라 한 겹 더**가 된다.
> 2.1이 적은 *"ingress는 틀리면 열린다"*는 대가가 그만큼 줄어든다.

## 발표 당일

```bash
./holdfast demo            # 시연 스택 둘 (#179)
./holdfast tunnel up       # 터널 + 밖에서 확인
#   … 시연 …
./holdfast tunnel down
```

**`tunnel up`은 확인에 실패하면 터널을 닫고 멈춘다.** 열린 채로 멈추는 것이
최악이기 때문이다.

### 발표 전에 미리 인증해 둔다

Access 세션은 **브라우저마다** 생긴다. **발표장에서 쓸 그 브라우저로** 미리 한 번
열어 이메일 인증을 끝낸다. 안 하면 3절 도중에 인증 코드를 기다리게 된다.

## 스크립트가 확인하는 것과 안 하는 것

| | |
|---|---|
| **확인한다** | 밖에서 인증 없이 쳤을 때 **앱이 나오지 않는지.** 나오면 Access가 안 걸린 것이고 터널을 닫는다 |
| **확인 못 한다** | **경로 허용 목록.** Access를 지나야 앱에 닿는데 스크립트에 세션이 없다. **리허설에서 브라우저로 본다** |

두 번째를 리허설 점검 항목으로 둔다.

- `demo-pess`에서 `/admin/programs` → **404**
- `demo-none`에서 `/admin/programs` → **404**
- `demo-admin`에서 `/admin/programs` → 뜬다
- 어느 쪽이든 `/actuator/metrics` → **404**
