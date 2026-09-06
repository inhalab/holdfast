#!/usr/bin/env node
// summarize-pages.mjs — PER-002 화면별 응답시간을 판정 표로 출력한다 (이슈 #105).
//
//   load-test/scripts/summarize-pages.mjs [--session 20260907-1830 | --session all]
//
// ## summarize.mjs와 무엇이 다른가
//
// **판정이 다르다.** 저쪽은 전략 간 비교라 "대조군 대비 비율"이 결론이고, 그래서
// 잡음 바닥이 비율보다 크면 아무것도 말할 수 없다(concurrency-spec 7.8.4).
// 이쪽은 **절대 기준(3초)과의 대조**라 화면마다 통과·초과가 곧 결론이다.
//
// 그래서 여백(3000ms까지 몇 배 남았는가)과 **그 화면의 회차 간 폭**을 함께
// 찍는다. 폭을 여백에 실어도 기준 아래면 그 판정은 잡음에 흔들리지 않는다.
// 잡음을 다른 측정에서 빌려오지 않고 여기서 재는 이유는 spreadOf에 적었다.
//
// ## 3회 중앙값
//
// 7.4와 같다. 회차마다 화면별 p95를 내고, 그 셋의 중앙값을 채택한다. 평균이
// 아니라 중앙값인 이유도 같다 — GC 일시정지 같은 단발 이상치가 평균을 끈다.

import { readdirSync, readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const RESULTS_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', 'results');

/** PER-002 검수 기준. 이 값은 요구사항이므로 옵션으로 두지 않는다. */
const BUDGET_MS = 3000;

/**
 * 잡음을 **빌려오지 않고 이 측정에서 잰다.**
 *
 * 처음에는 7.8.4가 관측한 2.28배(`none` 여섯 번의 p95 폭)를 상수로 박아 뒀는데,
 * 그것은 **다른 시나리오의 잡음**이다. 이 측정은 조건마다 3회를 같은 조건으로
 * 돌리므로 **화면마다 자기 폭(최대/최소)이 그 자리에서 나온다.**
 *
 * 판정은 그 폭을 여백에 실어서 한다 — `p95 × 폭 < 3000`이면, 잡음이 최악으로
 * 실린 회차를 잡았더라도 기준을 넘지 않는다. 회차가 셋뿐이라 이 폭은 잡음의
 * 하한에 가깝지만, **빌려온 상수보다는 이 측정에 대해 말하는 값이다.**
 */
function spreadOf(values) {
  const v = values.filter((x) => typeof x === 'number' && x > 0);
  if (v.length < 2) return null;
  return Math.max(...v) / Math.min(...v);
}

const CONDITION_LABEL = { none: '무부하', low: '부하 중(저경합)' };

function parseArgs(argv) {
  const out = {};
  for (let i = 2; i < argv.length; i += 2) out[argv[i].replace(/^--/, '')] = argv[i + 1];
  return out;
}

function median(xs) {
  const v = xs.filter((x) => typeof x === 'number' && !Number.isNaN(x)).sort((a, b) => a - b);
  if (v.length === 0) return null;
  const mid = Math.floor(v.length / 2);
  return v.length % 2 ? v[mid] : (v[mid - 1] + v[mid]) / 2;
}

function load() {
  return readdirSync(RESULTS_DIR)
    .filter((f) => /^pages-(none|low)-.*\.json$/.test(f))
    .map((f) => {
      try {
        return JSON.parse(readFileSync(join(RESULTS_DIR, f), 'utf8'));
      } catch {
        return null;
      }
    })
    .filter((r) => r && r.config && Array.isArray(r.screens));
}

const args = parseArgs(process.argv);
let results = load();
if (results.length === 0) {
  console.error('[summarize-pages] 결과가 없다. load-test/scripts/run-pages.sh를 먼저 돌린다.');
  process.exit(1);
}

// **지정하지 않으면 가장 최근 세션만 읽는다.** 재측정한 값과 예전 값이 한
// 중앙값에 섞이는 것을 막는다(summarize.mjs와 같은 규칙, 같은 사고에서 나왔다).
const sessions = [...new Set(results.map((r) => r.config.measureSession))].sort();
const session = args.session || sessions[sessions.length - 1];
if (session !== 'all') {
  results = results.filter((r) => r.config.measureSession === session);
}

console.log('');
console.log(`PER-002 화면별 응답시간 — 세션 ${session === 'all' ? '전부' : session}`);
const anyCfg = results[0].config;
console.log(`커밋 ${anyCfg.commit}${anyCfg.dirty ? ' (dirty)' : ''} · 이미지 ${anyCfg.image}` +
  (anyCfg.imageStale === 'yes' ? ' ※ HEAD보다 오래됨' : ''));
console.log(`검수 기준 ${BUDGET_MS}ms · 판정은 화면마다 실측한 회차 간 폭을 여백에 실어서 한다`);

// --- 측정 무결성이 먼저다 ----------------------------------------------------
//
// 값을 먼저 보여주면 무결성을 읽지 않는다. 폐기한 넷 중 셋이 "숫자가
// 그럴듯해서" 통과했다(discarded-measurements.md).
console.log('');
console.log('측정 무결성');
let dirty = false;
for (const r of results.sort(cmpRun)) {
  const i = r.integrity;
  const bad = (i.badStatus || 0) + (i.badAsset || 0);
  if (bad > 0) dirty = true;
  console.log(
    `  ${(CONDITION_LABEL[r.config.loadCondition] || r.config.loadCondition).padEnd(16)}` +
    `${r.config.run}회차  표본 ${String(i.totalSamples ?? 0).padStart(5)}  ` +
    `2xx 아님 ${i.badStatus ?? '?'}  자원 실패 ${i.badAsset ?? '?'}` +
    (bad > 0 ? '   ← 이 회차의 숫자를 쓰지 않는다' : ''),
  );
}
if (dirty) {
  console.log('');
  console.log('  !! 2xx가 아닌 응답이 있다. 화면에는 정상 거절이 없으므로,');
  console.log('     시드·픽스처가 화면과 어긋났거나 앱이 깨진 것이다.');
}

// --- 화면별 판정 -------------------------------------------------------------

const conditions = [...new Set(results.map((r) => r.config.loadCondition))]
  .sort((a, b) => (a === 'none' ? -1 : b === 'none' ? 1 : a.localeCompare(b)));

const screenIds = [];
for (const r of results) {
  for (const s of r.screens) if (!screenIds.includes(s.id)) screenIds.push(s.id);
}

for (const condition of conditions) {
  const runs = results.filter((r) => r.config.loadCondition === condition);
  console.log('');
  console.log(`── ${CONDITION_LABEL[condition] || condition} · ${runs.length}회 중앙값 ─────────────────`);
  console.log('');
  console.log('화면                                        p95      최대    회차폭    여백    판정');
  let worst = null;
  let widest = null;
  for (const id of screenIds) {
    const rows = runs.map((r) => r.screens.find((s) => s.id === id)).filter(Boolean);
    if (rows.length === 0) continue;
    const p95 = median(rows.map((s) => s.p95));
    const max = median(rows.map((s) => s.max));
    const spread = spreadOf(rows.map((s) => s.p95));
    const label = rows[0].label;
    const headroom = p95 === null ? null : BUDGET_MS / p95;

    // **폭을 여백에 실어서 판정한다.** 이 화면이 관측된 만큼 최악으로 흔들려도
    // 기준 아래인가를 묻는 것이다.
    const worstCase = p95 === null ? null : p95 * (spread ?? 1);
    const verdict = p95 === null ? '?'
      : p95 >= BUDGET_MS ? '초과'
        : worstCase < BUDGET_MS ? '통과' : '통과(폭을 실으면 초과)';

    if (p95 !== null && (worst === null || p95 > worst.p95)) worst = { label, p95, headroom, spread };
    if (spread !== null && (widest === null || spread > widest.spread)) widest = { label, spread };
    console.log(
      `${label.padEnd(42)}${fmt(p95).padStart(8)} ${fmt(max).padStart(8)} ` +
      `${(spread === null ? '-' : `${spread.toFixed(2)}배`).padStart(8)} ` +
      `${(headroom === null ? '-' : `${headroom.toFixed(0)}배`).padStart(7)}  ${verdict}`,
    );
  }
  if (worst) {
    console.log('');
    console.log(`  가장 느린 화면 — ${worst.label} ${fmt(worst.p95)} ` +
      `(기준의 ${((worst.p95 / BUDGET_MS) * 100).toFixed(1)}%, 여백 ${worst.headroom.toFixed(0)}배)`);
  }
  if (widest) {
    // **가장 흔들리는 화면을 따로 알린다.** 빠른 화면일수록 폭이 크게 잡히는데,
    // 그것은 그 화면이 불안정해서가 아니라 값이 작아 상대 폭이 커지는 것이다.
    // 판정을 위협하지 않지만, 조건 간 차이를 읽으려 할 때 그 폭 안에 들어가는
    // 차이는 해석하면 안 된다는 신호다.
    console.log(`  회차 간 폭이 가장 큰 화면 — ${widest.label} ${widest.spread.toFixed(2)}배`);
  }
}

console.log('');

function fmt(v) {
  return v === null || v === undefined ? '-' : `${v.toFixed(1)}ms`;
}

function cmpRun(a, b) {
  if (a.config.loadCondition !== b.config.loadCondition) {
    return a.config.loadCondition === 'none' ? -1 : 1;
  }
  return a.config.run - b.config.run;
}
