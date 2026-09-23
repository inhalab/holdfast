# -*- coding: utf-8 -*-
"""5주차 주간 발표 — **박태준 몫만** 만든다.

**합치는 자리는 PowerPoint 가 아니라 이 스크립트다**(#217). 최건 몫은 같은
`engine.py` 를 써서 자기 장을 PR 로 올리고, 머지된 뒤 발표자가 한 번 돌려
`.pptx` 하나를 만든다. 표지·목차·마무리는 공동 장이라 여기 없다.

**어두운 띠와 꼬리 한 줄을 안 쓴다.** 3·4주차가 장마다 «내용 + 아래 요약 상자»
한 틀이었는데, 그 상자가 세로의 40%를 먹으면서 정작 **내용을 좁은 위쪽에
욱여넣게** 했다. 띠가 하던 말은 **제목으로 올린다** — 제목이 결론이고 아래가
근거면 한눈에 읽힌다. 요약을 따로 둘 이유가 없다.
"""
import sys, shutil
from pathlib import Path
from engine import (Deck, W, H, M, CW, PAD, INK, NAVY, MUTED, ICE, PANEL,
                    LINE, TRACK, GREEN, RED, WHITE, RULE)

sys.stdout.reconfigure(encoding="utf-8")
HERE = Path(__file__).parent
ROOT = HERE.parent.parent
OUT = ROOT / "docs" / "submissions" / "5주차_발표_박태준.pptx"
NOTES = ROOT / "docs" / "submissions" / "5주차-노트.md"
PREV = HERE / "preview-w5"
FIG = HERE / "build"   # docs/figures 의 SVG 에서 뽑은 PNG. 빌드 산출물이라 커밋 안 한다

BOT = 748   # 맨 아래 한 줄이 앉는 자리. 띠를 안 쓰므로 여기까지 내용이 쓴다


def tail(s, t, y=BOT):
    """맨 아래 한 줄. **상자를 씌우지 않는다** — 얇은 선 하나면 충분하다."""
    s.rect(M, y - 18, CW, 1.2, LINE, r=0)
    s.text(M, y, CW, 28, t, 18.75, MUTED)
    return s


d = Deck()

# ══ 1. 이번 주 ════════════════════════════════════════════════════════
s = d.add()
s.header("01", "클라우드 배포를 끝냈다",
         "닷새 — 전 구간을 한 번에 띄워 확인하고, 그 과정에서 드러난 검증 문제를 고쳤다")

WORK = [("9/20", "배포 도식", "#214",
         ["아키텍처 그림을 저장소에",
          "넣었다. 그동안 원본이 작업용",
          "폴더에만 있어서, 다시 뽑으면",
          "고친 내용이 사라졌다."]),
        ("9/22", "배포 안전장치", "#212",
         ["AWS 전체 스택을 올리기 전에",
          "확인을 받게 했다. 만들 리소스와",
          "시간당 비용을 보여 주고",
          "직접 입력해야 진행된다."]),
        ("9/23", "전 구간 확인", "#42",
         ["브라우저 → Cloudflare → ALB",
          "→ 앱 2대 → DB 를 한 번에 띄워",
          "끝까지 통과시켰다.",
          "확인 후 바로 내렸다."]),
        ("9/23", "검증 고치기", "#222",
         ["검사할 대상이 없을 때도",
          "«통과»로 끝나던 스크립트 넷을",
          "고쳤다. 이제 확인 건수가",
          "0이면 실패로 찍는다."])]
for i, (day, name, iss, body) in enumerate(WORK):
    x, w = M + i * (307.8 + 18), 307.8
    s.rect(x, 200, w, 340, PANEL)
    s.rect(x + 26, 226, 96, 34, NAVY, r=6)
    s.text(x + 26, 226, 96, 34, day, 18, WHITE, True, "c", "m")
    s.text(x + 26, 286, w - 52, 34, name, 24, INK, True)
    s.text(x + 26, 340, w - 52, 136, body, 17.25, MUTED, lh=1.6)
    s.text(x + 26, 494, w - 52, 26, iss, 17.25, NAVY, True)

s.text(M, 580, CW, 32, "이번 주 결과", 21, MUTED, True)
RES = [("app.inhalab.cloud 가 200", "좌석 화면이 실제로 뜬다"),
       ("리소스 66개를 한 번에", "VPC · ALB · Fargate 2대 · RDS 까지 서고, 확인 뒤 전부 내렸다"),
       ("닫은 이슈 5개", "#42 · #212 · #214 · #217 · #222")]
for i, (big, sub) in enumerate(RES):
    x, w = M + i * (412.6 + 24), 412.6
    s.rect(x, 620, w, 84, PANEL)
    s.text(x + 24, 636, w - 48, 30, big, 21, NAVY, True)
    s.text(x + 24, 670, w - 48, 24, sub, 17.25, MUTED)

# ══ 2. 아키텍처 — 전면 그림 ═══════════════════════════════════════════
d.add().image(FIG / "deploy-architecture.png")

# ══ 3. 전 구간 ════════════════════════════════════════════════════════
s = d.add()
s.header("02", "엣지와 오리진이 같은 순간에 섰다",
         "지금까지는 둘을 따로 봤다 — 그래서 app 의 «성공 신호»가 503 이었다")

STAT = [("200", "app.inhalab.cloud", "이번이 처음이다"),
        ("12 : 12", "태스크 배지 24회", "앱이 정말 2대다"),
        ("0", "내린 뒤 과금", "전 리전을 직접 조회했다")]
for i, (big, lab, sub) in enumerate(STAT):
    x, w = M + i * (412.6 + 24), 412.6
    s.rect(x, 200, w, 150, PANEL)
    s.text(x, 216, w, 62, big, 52, NAVY, True, "c", "m")
    s.text(x, 284, w, 26, lab, 19.5, INK, True, "c")
    s.text(x, 314, w, 24, sub, 17.25, MUTED, False, "c")

COL = (101.5, 300), (425.0, 320), (770.0, 566)
ROWS = [("admin.inhalab.cloud", "302 → Cloudflare Access",
         "자물쇠가 앱이 아니라 엣지에 있다 — 앱은 그것을 모른다"),
        ("http:// 로 들어오면", "301 → https",
         "평문으로 지나간 트래픽은 한 번도 없다"),
        ("ALB 로 직행하면", "무응답 (코드 000)",
         "보안그룹이 막는다 — 502 가 아니라 «아무 답도 없다»"),
        ("내린 뒤", "66 destroyed",
         "과금되는 리소스를 AWS 에 직접 세어 확인했다")]
for i, row in enumerate(ROWS):
    y = 396 + i * 66
    s.rect(M, y, CW, 56, PANEL)
    for (cx, cw), t, c, b in zip(COL, row, (NAVY, INK, MUTED), (True, False, False)):
        s.text(cx, y, cw, 56, t, 18.75, c, b, anchor="m")

tail(s, "redis 는 down 인데 타겟은 멀쩡하다 — 헬스체크를 /actuator 가 아니라 /api/health 로 고정한 판단이 실물로 확인됐다.")

# ══ 4. 검증이 조용히 아무 일도 안 한다 ════════════════════════════════
s = d.add()
s.header("03", "«통과»는 두 가지를 뜻한다",
         "«봤는데 위반이 없다»와 «볼 것이 없었다» — 구분하지 않으면 고장난 도구가 가장 좋은 성적을 낸다")

SICK = [("측정 검증이 0건을 통과로",
         ["존재하지 않는 회차 9999 를 줘도",
          "초과 확정 · 초과 홀드가 전부",
          "0 초록이었다.",
          "",
          "시드를 안 돌렸거나 회차를 잘못 준",
          "경우가 «위반 없음»과 구분이 안 된다."]),
        ("시드는 0행을 넣고도 «완료»",
         ["INSERT 가 0행을 넣어도 DB 는",
          "오류를 내지 않는다.",
          "",
          "그러면 만든 쪽도 재는 쪽도",
          "«정상»이라 말하는데,",
          "정작 잰 것이 없다."]),
        ("도구가 볼 목록이 비어도 통과",
         ["확인 대상을 비우면 루프가 0바퀴",
          "돌고 «확인 통과»가 찍힌다.",
          "",
          "가드 하나는 반대로, «막을 것이",
          "없다»를 실패로 돌려줘서",
          "부르는 쪽이 조용히 죽었다."])]
for i, (t, body) in enumerate(SICK):
    x, w = M + i * (412.6 + 24), 412.6
    s.rect(x, 200, w, 300, PANEL)
    s.text(x + PAD, 226, w - 2 * PAD, 30, t, 23.24, INK, True)
    s.text(x + PAD, 286, w - 2 * PAD, 170, body, 18, MUTED, lh=1.6)

s.rect(M, 534, CW, 118, INK, r=10)
s.text(124.2, 560, 1188, 34, "규칙 10 — 확인 건수가 0이면 실패다", 26, WHITE, True)
s.text(124.2, 604, 1188, 28,
       "#184 는 «CI 만 초록», #186 은 «한 환경에서만», 이번은 «초록인데 아무것도 안 함»이다. 세 번째라 사건이 아니라 부류다.",
       19.5, ICE)
tail(s, "검사가 잡는지를 먼저 검사한다 — 위반을 일부러 심어 보는 자가검증이 그 한 겹 위다.")

# ══ 8. 산출물 — 실물을 늘어놓는다 ═════════════════════════════════════
s = d.add(INK)
s.text(M, 44, 900, 44, "지금 저장소에 있는 것", 36, WHITE, True)
s.text(M, 98, 1100, 26,
       "design-spec 7절이 센 산출물 다섯을 열세 장이 나눠 맡는다. 새로 잰 것은 없다 — 전부 문서가 이미 판정한 것이다",
       18, ICE)

SHEET = [("requirements-coverage", "요구사항 14개 × 판정"),
         ("critical-sections-coverage", "임계 구역 여섯 × 무엇이 덮나"),
         ("strategy-comparison", "전략 다섯 × 같은 사건"),
         ("measurement-harness", "k6 → 앱 2대 → PG"),
         ("m3-oversell-by-contention", "초과 확정 × 경합도"),
         ("signal-vs-noise", "왜 성능으로 못 가르나"),
         ("second-campaign", "2차 측정 두 캠페인"),
         ("deploy-architecture", "배포 아키텍처 — 이번 주")]
for i, (name, cap) in enumerate(SHEET):
    col, row = i % 4, i // 4
    x = M + col * (307.8 + 18)
    y = 156 + row * 296
    s.rect(x, y, 307.8, 238, WHITE, r=6)
    s.image(FIG / f"{name}.png", x + 10, y + 10, 287.8, 218)
    s.text(x, y + 250, 307.8, 24, cap, 16.5, ICE)

s.text(M, 754, 900, 30, "열세 장 전부 저장소에 있고, 값의 출처를 문서 어디로 잡았는지까지 적혀 있다", 21, WHITE, True)

# ══ 출력 ══════════════════════════════════════════════════════════════
d.load_notes(NOTES)
d.save_html(PREV)
# 미리보기는 그림을 파일명으로만 참조한다. 옆에 두지 않으면 깨진 상자가 뜬다.
for op in (o for sl in d.slides for o in sl.ops if o[0] == "image"):
    shutil.copy(op[5], PREV / Path(op[5]).name)
print(f"미리보기: {PREV} ({len(d.slides)}장)")

tmp = HERE / "w5-out.pptx"
d.save_pptx(tmp)
try:
    shutil.copy(tmp, OUT)
    print(f"pptx: {OUT}")
except PermissionError:
    print(f"!! {OUT} 가 열려 있어 덮어쓰지 못했다. 닫고 다시 돌리면 된다.")
    print(f"   지금 만든 것: {tmp}")
