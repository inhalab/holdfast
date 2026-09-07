#!/usr/bin/env node
// render-md.mjs — 마크다운 하나를 HTML 한 장으로 만든다. **의존성이 없다.**
//
//   tools/render-md.mjs <입력.md> <출력.html> [제목]
//
// 용도는 하나다 — `./holdfast demo`가 `docs/demo-script.md`를 발표용으로 띄운다
// (이슈 #146). 범용 마크다운 렌더러가 아니며, 그럴 필요도 없다.
//
// ## 왜 npx marked 를 쓰지 않는가
//
// **이 저장소는 이미 같은 판단을 했다.**
//
// > jslib 원격 import를 쓰지 않는다 — k6 컨테이너가 외부망 없이도 돌아야 하고,
// > 측정 실행이 외부 CDN 가용성에 묶이면 안 되기 때문이다.
// > (load-test/scenarios/lib/api.js, 같은 취지가 summary.js에도 있다)
//
// **측정에 적용한 이유가 발표에는 더 강하게 적용된다.** `npx marked`는 첫 실행에
// 네트워크를 요구하고, 발표장에 네트워크가 없을 수 있다. devDependency로 내려도
// `npm ci`라는 사전 준비가 생기는데, **사전 준비가 필요한 발표 도구는 그 준비를
// 잊었을 때 발표 중에 실패한다.** 이 저장소에 `package.json`이 하나도 없는 것도
// 같은 선택의 결과다.
//
// 그 대가로 **여기 있는 것이 마크다운의 전부는 아니다.** 지원 범위 밖의 문법은
// 그냥 글자로 나온다. `demo-script.md`가 실제로 쓰는 것만 다룬다 — 제목 · 표 ·
// 코드펜스 · 인용 · 목록 · 수평선, 그리고 인라인으로 굵게 · 코드 · 링크 · 자동링크.
//
// **못 그려도 발표는 계속된다.** 호출하는 쪽(`holdfast`)이 실패를 삼키고 원문
// 경로를 알려준다.

import { readFileSync, writeFileSync } from 'node:fs';
import { basename } from 'node:path';

const [, , inPath, outPath, titleArg] = process.argv;
if (!inPath || !outPath) {
  console.error('사용: tools/render-md.mjs <입력.md> <출력.html> [제목]');
  process.exit(2);
}

const src = readFileSync(inPath, 'utf8');
const title = titleArg || basename(inPath);
writeFileSync(outPath, page(title, blocks(src.split(/\r?\n/))), 'utf8');

// --- 블록 ---------------------------------------------------------------------

function blocks(lines) {
  const out = [];
  let i = 0;

  while (i < lines.length) {
    const line = lines[i];

    // 코드펜스. **가장 먼저 본다** — 펜스 안에서는 다른 문법을 해석하지 않는다.
    const fence = line.match(/^\s*```+\s*(\S*)/);
    if (fence) {
      const body = [];
      i++;
      while (i < lines.length && !/^\s*```+\s*$/.test(lines[i])) body.push(lines[i++]);
      i++; // 닫는 펜스
      const lang = fence[1] ? ` data-lang="${esc(fence[1])}"` : '';
      out.push(`<pre${lang}><code>${body.map(esc).join('\n')}</code></pre>`);
      continue;
    }

    if (/^\s*(---+|\*\*\*+|___+)\s*$/.test(line)) { out.push('<hr>'); i++; continue; }

    const heading = line.match(/^(#{1,6})\s+(.*)$/);
    if (heading) {
      const level = heading[1].length;
      out.push(`<h${level}>${inline(heading[2])}</h${level}>`);
      i++;
      continue;
    }

    // 표. 헤더 줄 + 구분 줄(|---|)이 붙어 있어야 표로 본다.
    if (/^\s*\|/.test(line) && i + 1 < lines.length && /^\s*\|[\s:|-]+\|\s*$/.test(lines[i + 1])) {
      const header = cells(line);
      const align = cells(lines[i + 1]).map(alignOf);
      i += 2;
      const rows = [];
      while (i < lines.length && /^\s*\|/.test(lines[i])) rows.push(cells(lines[i++]));
      out.push(table(header, align, rows));
      continue;
    }

    // 인용. 이어지는 `>` 줄을 모아 안쪽을 다시 블록으로 해석한다 —
    // demo-script.md의 인용 안에 표와 목록이 들어 있다.
    if (/^\s*>/.test(line)) {
      const body = [];
      while (i < lines.length && /^\s*>/.test(lines[i])) {
        body.push(lines[i++].replace(/^\s*>\s?/, ''));
      }
      out.push(`<blockquote>${blocks(body)}</blockquote>`);
      continue;
    }

    const list = listAt(lines, i);
    if (list) { out.push(list.html); i = list.next; continue; }

    if (line.trim() === '') { i++; continue; }

    // 나머지는 문단. 빈 줄까지 모아 한 문단으로 합친다 — 원문이 80자에서
    // 줄바꿈하므로 줄마다 <p>를 만들면 문장이 토막 난다.
    const para = [];
    while (i < lines.length && lines[i].trim() !== ''
           && !/^\s*(#{1,6}\s|>|```|---+\s*$|\|)/.test(lines[i]) && !listAt(lines, i)) {
      para.push(lines[i++]);
    }
    // **한 줄도 못 먹으면 i가 멈춘다.** 표처럼 보이는데 구분 줄이 없는 `|` 줄이
    // 그 경우다 — 위 표 분기에도 안 걸리고 이 루프에서도 걸러진다. 한 줄은
    // 반드시 소비해 무한 루프를 막는다.
    if (para.length === 0) para.push(lines[i++]);
    out.push(`<p>${inline(para.join('\n'))}</p>`);
  }

  return out.join('\n');
}

/** 한 자리에서 시작하는 목록 전체를 HTML로. 아니면 null. */
function listAt(lines, start) {
  const bullet = /^(\s*)([-*+])\s+(.*)$/;
  const number = /^(\s*)(\d+)\.\s+(.*)$/;
  const first = lines[start].match(bullet) || lines[start].match(number);
  if (!first) return null;

  const ordered = number.test(lines[start]);
  const items = [];
  let i = start;

  while (i < lines.length) {
    const m = lines[i].match(ordered ? number : bullet);
    if (!m) break;
    const body = [m[3]];
    i++;
    // 이어지는 들여쓴 줄은 같은 항목의 계속이다.
    while (i < lines.length && lines[i].trim() !== ''
           && /^\s{2,}/.test(lines[i])
           && !lines[i].match(bullet) && !lines[i].match(number)) {
      body.push(lines[i++].trim());
    }
    items.push(`<li>${inline(body.join(' '))}</li>`);
  }

  const tag = ordered ? 'ol' : 'ul';
  const startAttr = ordered && first[2] !== '1' ? ` start="${Number(first[2])}"` : '';
  return { html: `<${tag}${startAttr}>${items.join('')}</${tag}>`, next: i };
}

function cells(row) {
  return row.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((c) => c.trim());
}

function alignOf(spec) {
  const left = spec.startsWith(':');
  const right = spec.endsWith(':');
  if (left && right) return ' style="text-align:center"';
  if (right) return ' style="text-align:right"';
  return '';
}

function table(header, align, rows) {
  const th = header.map((c, n) => `<th${align[n] || ''}>${inline(c)}</th>`).join('');
  const body = rows
    .map((r) => `<tr>${r.map((c, n) => `<td${align[n] || ''}>${inline(c)}</td>`).join('')}</tr>`)
    .join('');
  // 표가 넓으면 화면 밖으로 나가는 대신 자기 안에서 스크롤한다(design-spec 5.3과
  // 같은 자세). 프로젝터에서 가로 스크롤바가 생기면 아무도 못 찾는다.
  return `<div class="scroll"><table><thead><tr>${th}</tr></thead><tbody>${body}</tbody></table></div>`;
}

// --- 인라인 -------------------------------------------------------------------

/**
 * **코드 조각을 먼저 떼어 낸다.** 그러지 않으면 `**`가 들어 있는 코드가 굵게
 * 바뀌고, 코드 안의 `<`가 태그로 읽힌다.
 *
 * **자리표시자로 제어문자를 쓴다.** 처음에는 공백-숫자-공백으로 뒀는데, 본문의
 * "탭 2 —"나 표의 "| 1 |"이 그 모양이라 **코드가 아닌 숫자가 코드로 되돌아왔다.**
 * 마크다운 원문에 나올 수 없는 글자여야 한다. 소스에 제어문자를 직접 박지 않고
 * `fromCharCode`로 만드는 이유는 따로다 — 박으면 편집기도 git도 이 파일을
 * binary로 본다.
 */
function inline(text) {
  const MARK = String.fromCharCode(0);
  const codes = [];
  let s = text.replace(/`([^`]+)`/g, (_, code) => {
    codes.push(code);
    return MARK + (codes.length - 1) + MARK;
  });

  s = esc(s);
  s = s.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  s = s.replace(/(^|[\s(])\*([^*\n]+)\*/g, '$1<em>$2</em>');
  // [글자](주소) 와 <주소> 자동링크. 새 탭으로 연다 — 대본을 잃지 않는다.
  s = s.replace(/\[([^\]]+)]\(([^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noreferrer">$1</a>');
  s = s.replace(/&lt;(https?:\/\/[^\s&]+)&gt;/g, '<a href="$1" target="_blank" rel="noreferrer">$1</a>');

  const marked = new RegExp(MARK + '(\\d+)' + MARK, 'g');
  return s.replace(marked, (_, n) => `<code>${esc(codes[Number(n)])}</code>`);
}

function esc(s) {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

// --- 페이지 -------------------------------------------------------------------
//
// **프로젝터에서 읽히는 것이 목적이다.** 기본 마크다운 HTML은 본문 16px에 폭이
// 화면 끝까지 가서, 뒷자리에서 표가 읽히지 않는다. 본문을 키우고 폭을 묶는다.
// 스타일은 파일 안에 둔다 — 외부 CSS를 부르면 그 파일도 함께 다녀야 한다.

function page(title, body) {
  return `<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)}</title>
<style>
  :root { color-scheme: light dark; }
  body {
    margin: 0 auto; padding: 2.5rem 1.5rem 6rem; max-width: 56rem;
    font: 20px/1.75 "Malgun Gothic", "맑은 고딕", system-ui, sans-serif;
    word-break: keep-all;
  }
  h1 { font-size: 2.1rem; margin: 0 0 1.5rem; }
  h2 {
    font-size: 1.6rem; margin: 3rem 0 1rem;
    padding-top: .8rem; border-top: 3px solid currentColor;
  }
  h3 { font-size: 1.25rem; margin: 2rem 0 .8rem; }
  p, li { margin: .7rem 0; }
  ul, ol { padding-left: 1.6rem; }
  code {
    font: .9em/1.5 Consolas, "D2Coding", monospace;
    padding: .1em .35em; border-radius: 3px;
    background: color-mix(in srgb, currentColor 12%, transparent);
  }
  pre {
    padding: 1rem 1.2rem; overflow-x: auto; border-radius: 6px;
    background: color-mix(in srgb, currentColor 8%, transparent);
  }
  pre code { background: none; padding: 0; font-size: .95rem; }
  blockquote {
    margin: 1.2rem 0; padding: .2rem 1.2rem;
    border-left: 5px solid color-mix(in srgb, currentColor 35%, transparent);
  }
  .scroll { overflow-x: auto; margin: 1.2rem 0; }
  table { border-collapse: collapse; width: 100%; font-size: .95rem; }
  th, td {
    border: 1px solid color-mix(in srgb, currentColor 30%, transparent);
    padding: .5rem .7rem; text-align: left; vertical-align: top;
  }
  th { background: color-mix(in srgb, currentColor 10%, transparent); }
  hr { margin: 2.5rem 0; border: 0; border-top: 1px solid color-mix(in srgb, currentColor 25%, transparent); }
  a { color: inherit; }
</style>
</head>
<body>
${body}
</body>
</html>
`;
}
