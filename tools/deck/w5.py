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
s.header("01", "확인이 거짓말하던 자리를 닫았다",
         "닷새 — 넷이 다른 일 같지만 같은 병이었다")

WORK = [("9/20", "도식", "#214",
         ["틀린 문장을 고쳤는데", "고친 원본이 임시 폴더에만", "있었다. 저장소로 들였다."]),
        ("9/22", "가드", "#212",
         ["전체 apply 앞에 확인을 두고,", "오타 하나가 전체 스택을", "세우던 길을 막았다."]),
        ("9/23", "배포", "#42",
         ["엣지와 오리진이 «같은", "순간에» 처음으로 섰다.", "그리고 바로 내렸다."]),
        ("9/23", "검증", "#222",
         ["0건을 통과로 찍던 것 넷.", "가드가 통과시켜야 할 때", "죽는 것도 있었다."])]
for i, (day, name, iss, body) in enumerate(WORK):
    x, w = M + i * (307.8 + 18), 307.8
    s.rect(x, 200, w, 330, PANEL)
    s.rect(x + 26, 226, 96, 34, NAVY, r=6)
    s.text(x + 26, 226, 96, 34, day, 18, WHITE, True, "c", "m")
    s.text(x + 26, 288, w - 52, 38, name, 30, INK, True)
    s.text(x + 26, 344, w - 52, 108, body, 18, MUTED, lh=1.6)
    s.text(x + 26, 478, w - 52, 26, iss, 17.25, NAVY, True)

s.text(M, 570, CW, 40, "«확인이 참이 아니었다»", 30, INK, True)
s.text(M, 620, CW, 60,
       ["태그가 거짓 실패를 내고, 가드가 통과시킬 때 죽고, 검증이 0건을 통과로 찍고,",
        "«고쳤다»고 말한 원본이 저장소 밖에 있었다."], 20, MUTED, lh=1.5)
tail(s, "그래서 이번 주에 늘어난 것은 기능이 아니라 «확인을 믿을 수 있게 된 것»이다.")

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

SICK = [("가드가 반대로 죽었다",
         ["«아무것도 안 떠 있다»를 «실패»로",
          "돌려줘서, 부르는 쪽이 조용히 죽었다.",
          "",
          "통과시켜야 할 때 정확히 죽는다.",
          "여섯 자리가 같은 줄을 지나고 있었다."]),
        ("0건을 통과로 찍었다",
         ["존재하지 않는 회차 9999 를 줘도",
          "검증이 전부 0 초록이었다.",
          "",
          "시드를 안 돌렸거나 번호를 잘못 준",
          "경우가 «통과»와 구분이 안 된다."]),
        ("볼 목록이 비어도 통과",
         ["확인 대상을 비우면 루프가 0바퀴",
          "돌고 «확인 통과»가 찍힌다.",
          "",
          "시드는 0행을 넣고도 «완료»였다 —",
          "0행 삽입은 오류가 아니기 때문이다."])]
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

# ══ 5. 오타 하나 ══════════════════════════════════════════════════════
s = d.add()
s.header("04", "오타 하나가 전체 스택을 세운다",
         "#212 를 고치러 들어갔다가 옆에서 찾은 것 — 게이트도 못 잡는 자리였다")

s.rect(M, 200, 612.6, 260, PANEL)
s.text(M + PAD, 226, 556, 26, "있던 것", 17.25, RED, True)
s.text(M + PAD, 266, 556, 120,
       ["$ ./holdfast aws up bogus",
        "  알 수 없는 단계: bogus",
        "  ... 그리고 계속 간다.   종료코드 = 0"], 19.5, INK, lh=1.7)
s.text(M + PAD, 396, 556, 52,
       ["단계 목록이 비면 그것이 곧 «전체 apply» 다.",
        "함수는 exit 2 로 죽었는데 부르는 쪽이 못 받는다."], 17.25, MUTED, lh=1.6)

RIGHT = [("왜 게이트도 못 잡나", "bogus 는 app 이 아니다 — 단계 «이름»으로 물으면 그냥 지나간다"),
         ("그래서 조건을 바꿨다", "«이름이 app 이냐»가 아니라 «대상 목록이 비었냐»로 본다"),
         ("이름이 아니라 결과에", "단계가 하나 늘 때마다 게이트를 같이 고쳐야 하면 언젠가 빠뜨린다")]
for i, (t, b) in enumerate(RIGHT):
    x, y = M + 612.6 + 60, 200 + i * 90
    s.rect(x, y, 612.6, 80, PANEL)
    s.text(x + PAD, y + 14, 612.6 - 2 * PAD, 26, t, 19.5, NAVY, True)
    s.text(x + PAD, y + 46, 612.6 - 2 * PAD, 24, b, 17.25, INK)

s.text(M, 500, CW, 36, "그래서 전체 단계는 apply 앞에서 한 번 묻는다", 27, INK, True)
s.text(M, 548, 612.6, 84,
       ["만들 리소스 수와 그중 과금되는 것,",
        "시간당 비용을 보이고 «단계 이름»을 직접 치게 한다.",
        "자동화용 --yes 는 두되 기본은 묻는 쪽이다."], 18.75, MUTED, lh=1.6)
s.text(M + 612.6 + 60, 548, 612.6, 84,
       ["인자 없이 불러 전체 스택이 서고, 중간에 끊자",
        "state 에 기록되기 전의 RDS 가 남았다.",
        "destroy 는 모르는 것을 못 지운다 — 그 사고가 이 자리다."], 18.75, MUTED, lh=1.6)
tail(s, "«빨리 가려다 틀리는» 자리라 기본을 묻는 쪽으로 뒀다.")

# ══ 6. 태그가 거짓말한다 ══════════════════════════════════════════════
s = d.add()
s.header("05", "태그가 같은 날 두 번 거짓말했다",
         "태그는 «우리 것»을 가리는 기준이지 «과금되는 것»을 가리는 기준이 아니다")

s.rect(M, 200, 612.6, 300, PANEL)
s.text(M + PAD, 228, 556, 28, "내린 직후 태그가 말한 것", 19.5, RED, True)
TAGS = [("보안그룹 규칙", "69"), ("서브넷", "2"),
        ("ECS 클러스터", "1"), ("태스크 정의", "3")]
for i, (k, v) in enumerate(TAGS):
    y = 280 + i * 50
    s.rect(M + PAD, y, 556, 42, WHITE, r=6)
    s.text(M + PAD + 20, y, 340, 42, k, 19.5, INK, anchor="m")
    s.text(M + PAD + 380, y, 150, 42, v, 22, RED, True, anchor="m")

x2 = M + 612.6 + 60
s.rect(x2, 200, 612.6, 300, PANEL)
s.text(x2 + PAD, 228, 556, 28, "AWS 에 직접 물어본 결과", 19.5, GREEN, True)
s.text(x2 + PAD, 286, 556, 44, "전부 없거나 INACTIVE", 30, INK, True)
s.text(x2 + PAD, 348, 556, 130,
       ["태그 API 갱신 지연과, AWS 가 한동안",
        "남겨 두는 «비석»이다.",
        "",
        "태그로 판정했다면 — 다 내렸는데",
        "실패로 끝났을 것이다."], 18.75, MUTED, lh=1.6)

s.text(M, 536, CW, 36, "그래서 판정을 «돈이 나가나»로 바꿔 두었다", 27, INK, True)
s.text(M, 584, CW, 60,
       ["ECS · RDS · ALB · NAT · EIP 를 AWS 에 직접 세고 하나라도 있으면 실패다.",
        "이 판정이 apply 를 중간에 죽여 state 밖에 남은 RDS 를 실제로 잡았다 — state 를 믿었다면 «destroy 성공»으로 끝났다."],
       18.75, MUTED, lh=1.5)
tail(s, "«태그가 남았나»가 아니라 «돈이 나가나»다. 확인이 거짓 실패도 거짓 통과도 내면 안 된다.")

# ══ 7. 원본이 저장소 밖에 있었다 ══════════════════════════════════════
s = d.add()
s.header("06", "«고쳤다»고 말한 것이 저장소 밖에 있었다",
         "리뷰가 안 붙고, 무엇이 바뀌었는지 남지 않고, 틀린 문장을 회수할 자리가 없다")

IN = [("아키텍처 도식", "#214 · #229",
       ["PNG 에 틀린 문장이 구워져 있어 픽셀로 덮어 뒀었다.",
        "«다시 뽑으면 되돌아간다»가 그때의 상태였다.",
        "정본 SVG 를 저장소로 들였다 — 이제 되돌아갈 자리가 없다."]),
      ("발표 자료 생성기", "#217 · #233",
       ["스크립트와 직접 고친 .pptx 가 둘 다 원본 행세를 했다.",
        "저장소 안이라는 것 하나로 갈랐다 — 리뷰 · 회수 · 추적.",
        "노트는 공개 저장소 밖 파일에서 읽는다."])]
for i, (t, iss, body) in enumerate(IN):
    y = 200 + i * 172
    s.rect(M, y, CW, 152, PANEL)
    s.text(M + PAD, y + 28, 420, 32, t, 25.5, NAVY, True)
    s.text(M + PAD, y + 72, 420, 26, iss, 18, MUTED)
    s.text(M + 500, y + 28, CW - 500 - PAD, 96, body, 18.75, MUTED, lh=1.65)

s.text(M, 560, CW, 36, "그래서 이 발표 자료도 저장소 안 스크립트가 만들었다", 27, INK, True)
s.text(M, 608, CW, 30,
       "각자 자기 장을 PR 로 올리고, 합치는 자리는 PowerPoint 가 아니다. 노트는 발표자가 쓰고 생성기가 읽어 넣는다.",
       18.75, MUTED)
tail(s, "내 담당 열린 이슈는 0이다. 만료 인수(#199)와 대본(#215)이 남았고 둘 다 최건 몫이다.")

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

s.text(M, 754, 700, 30, "열세 장 전부 docs/figures 에 SVG 로 있다", 21, WHITE, True)
s.text(820, 754, 544, 30, "PNG 는 거기서 뽑은 빌드 산출물이다 — 두 형식으로 두면 한 벌만 낡는다",
       17.25, ICE)

# ══ 9. 남긴 것 ════════════════════════════════════════════════════════
s = d.add()
s.header("07", "이번 주가 남긴 것", "«했다»가 아니라 «저장소에 있다»로 세었다")

BIG = [("13", "docs/figures",
        "아키텍처 도식이 이번에 들어와 열세 장이 됐다. 그 전에는 저장소에 도식이 아예 없었다."),
       ("2", "tools/deck",
        "생성기와 엔진. 자료의 정본이 저장소 안으로 들어왔고, 노트는 공개 저장소 밖에서 읽는다."),
       ("10", "workflow.md 규칙",
        "«확인 건수가 0이면 실패다»가 열 번째다. 세 번 같은 형태로 당하고 규칙이 됐다."),
       ("5", "닫은 이슈",
        "#42 · #212 · #214 · #217 · #222 — 머지된 PR 여섯 장으로."),
       ("0", "내 담당 열린 이슈",
        "남은 것은 «고칠 것»이 아니라 «잴 것»이다. 다음은 5주차 측정이다.")]
for i, (num, lab, body) in enumerate(BIG):
    y = 200 + i * 104
    s.rect(M, y, CW, 90, PANEL)
    s.text(M + 20, y, 150, 90, num, 46, NAVY, True, "c", "m")
    s.text(M + 200, y, 320, 90, lab, 22.5, INK, True, anchor="m")
    s.text(M + 540, y, CW - 540 - 24, 90, body, 18.75, MUTED, anchor="m")

tail(s, "전 구간 기동은 절차가 잡혔다 — Git Bash 에서 net → push → app, 10분이면 선다.")

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
