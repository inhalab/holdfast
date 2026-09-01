#!/usr/bin/env node
// summarize.mjs — 측정 결과를 설계서 7.6 기록 양식 표로 출력한다.
//
//   node load-test/scripts/summarize.mjs [--scenario high] [--strategy pessimistic]
//
// 7.4: 전략당 3회 반복 후 **중앙값**을 채택한다. 평균이 아니라 중앙값인 이유는
// GC 일시정지 같은 단발 이상치가 평균을 끌고 가기 때문이다.
//
// ## 이 스크립트가 만들지 않는 값
//
// 7.1이 지표마다 출처를 못박아 두었고, k6가 아닌 것은 여기서 지어내지 않는다.
//
//   초과 예약   → sql/verify.sql (DB 검증 쿼리)
//   재시도 횟수 → Actuator 앱 커스텀 메트릭
//   제약 위반   → Actuator 앱 커스텀 메트릭
//   풀 대기     → hikaricp.connections.pending / .acquire (run.sh가 회차마다
//               scripts/pool-metrics.mjs로 콘솔에 찍는다. 마지막 회차 값을 옮겨 적는다)
//
// 이 칸들은 `?`로 남는다. **0으로 채우지 않는다** — "쟀는데 0"과 "안 쟀음"이
// 구분되지 않으면 비교표를 믿을 수 없게 된다.

import { readdirSync, readFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const RESULTS_DIR = join(dirname(fileURLToPath(import.meta.url)), '..', 'results');
const STRATEGY_ORDER = ['none', 'pessimistic', 'optimistic', 'unique', 'redis'];

// 7.6 표의 `—` 규칙. 해당 전략에서 그 지표가 성립하지 않는다는 뜻이며,
// 값이 0이라는 뜻이 아니다.
const NOT_APPLICABLE = {
  none:        ['lockGiveup', 'retries', 'violations'], // 락 없음 / 제약 자체를 안 검
  pessimistic: ['retries'],                             // 재시도는 optimistic만
  optimistic:  [],
  unique:      ['lockGiveup', 'retries'],               // 앱 락 없음
  redis:       ['retries'],
};

function parseArgs(argv) {
  const out = {};
  for (let i = 2; i < argv.length; i += 2) {
    const k = argv[i].replace(/^--/, '');
    out[k] = argv[i + 1];
  }
  return out;
}

function median(xs) {
  const v = xs.filter((x) => typeof x === 'number' && !Number.isNaN(x)).sort((a, b) => a - b);
  if (v.length === 0) return null;
  const mid = Math.floor(v.length / 2);
  return v.length % 2 ? v[mid] : (v[mid - 1] + v[mid]) / 2;
}

function loadRuns(scenario, strategy) {
  if (!existsSync(RESULTS_DIR)) return [];
  return readdirSync(RESULTS_DIR)
    .filter((f) => f.endsWith('.json') && f !== 'smoke-summary.json')
    .map((f) => {
      try { return { file: f, ...JSON.parse(readFileSync(join(RESULTS_DIR, f), 'utf8')) }; }
      catch { return null; }
    })
    .filter((r) => r && r.row)
    .filter((r) => (!scenario || r.row.scenario === scenario))
    .filter((r) => (!strategy || r.row.strategy === strategy));
}

function fmtMs(v) { return v === null ? '?' : `${Math.round(v)}ms`; }
function fmtPct(v) { return v === null ? '?' : `${(v * 100).toFixed(2)}%`; }
function fmtNum(v) { return v === null ? '?' : v.toFixed(1); }

function buildRow(strategy, runs) {
  const na = NOT_APPLICABLE[strategy] || [];
  const dash = (key, value) => (na.includes(key) ? '—' : value);

  // 미분류가 하나라도 있으면 그 실행의 숫자는 쓰지 않는다(classify.js).
  const unclassified = runs.reduce((a, r) => a + (r.row.counts?.unclassified || 0), 0);

  return {
    strategy,
    runs: runs.length,
    unclassified,
    devRuns: runs.filter((r) => r.row.isFinalRun !== true).length,
    cells: {
      oversellConfirmed: '?',                                 // ← verify.sql V-1 (7.6.2)
      oversellHeld: '?',                                      // ← verify.sql V-2 (7.6.2)
      phantomHold: fmtPct(median(runs.map((r) => r.row.phantomHoldRate))),
      p95: fmtMs(median(runs.map((r) => r.row.p95))),
      p99: fmtMs(median(runs.map((r) => r.row.p99))),
      tps: fmtNum(median(runs.map((r) => r.row.tps))),
      rejection409: fmtPct(median(runs.map((r) => r.row.normalRejectionRate))),
      lockGiveup: dash('lockGiveup', fmtPct(median(runs.map((r) => r.row.lockGiveupRate)))),
      errorRate: fmtPct(median(runs.map((r) => r.row.errorRate))),
      retries: dash('retries', '?'),                          // ← Actuator(7.1)
      violations: dash('violations', '?'),                    // ← Actuator(7.1)
      poolWait: '?',                                          // ← Actuator(7.1)
    },
    giveupBreakdown: {
      lockTimeout: runs.reduce((a, r) => a + (r.row.counts?.lockTimeout || 0), 0),
      retryExhausted: runs.reduce((a, r) => a + (r.row.counts?.retryExhausted || 0), 0),
    },
  };
}

function render(scenario, rows) {
  const L = [];
  L.push(`## 7.6 기록 양식 — ${scenario || '전체'} 시나리오`);
  L.push('');
  L.push('| 전략 | 초과 확정 | 초과 홀드 | 팬텀 홀드율 | p95 | p99 | TPS | 409율 | 락 포기율 | 오류율 | 재시도 | 제약위반 | 풀 대기 |');
  L.push('|---|---|---|---|---|---|---|---|---|---|---|---|---|');
  for (const r of rows) {
    const c = r.cells;
    L.push(`| ${r.strategy} | ${c.oversellConfirmed} | ${c.oversellHeld} | ${c.phantomHold} | ` +
           `${c.p95} | ${c.p99} | ${c.tps} | ` +
           `${c.rejection409} | ${c.lockGiveup} | ${c.errorRate} | ${c.retries} | ` +
           `${c.violations} | ${c.poolWait} |`);
  }
  L.push('');
  L.push('`?` = 이 스크립트가 만들지 않는 값. 출처가 k6가 아니다(7.1).');
  L.push('  초과 확정(V-1)·초과 홀드(V-2) → `load-test/scripts/verify.sh`');
  L.push('  재시도·제약위반·풀 대기 → Actuator 앱 커스텀 메트릭');
  L.push('`—` = 그 전략에서 성립하지 않는 지표. **0이 아니라 해당 없음이다.**');
  L.push('');

  // 락 포기의 원인 분해. 두 코드를 나눈 이유가 여기서 보인다(api-spec 3.2).
  L.push('### 락 포기 내역 (같은 열, 다른 원인)');
  L.push('');
  L.push('| 전략 | LOCK_TIMEOUT (시간 기반) | RETRY_EXHAUSTED (횟수 기반) |');
  L.push('|---|---|---|');
  for (const r of rows) {
    const na = (NOT_APPLICABLE[r.strategy] || []).includes('lockGiveup');
    L.push(`| ${r.strategy} | ${na ? '—' : r.giveupBreakdown.lockTimeout} | ` +
           `${na ? '—' : r.giveupBreakdown.retryExhausted} |`);
  }
  L.push('');
  L.push('**재시도 횟수와 락 포기율을 환산하지 않는다.** 재시도 칸은 앱이 세는 누적');
  L.push('*횟수*이고 락 포기율은 k6가 세는 요청 *비율*이라 단위가 다르다. 한 요청이');
  L.push('3회 재시도 후 포기하면 재시도에는 3, 락 포기율에는 요청 1건이 더해진다(7.6.1).');
  L.push('');

  // 7.4: 3회 반복 · 중앙값
  for (const r of rows) {
    if (r.runs !== 3) {
      L.push(`> ⚠ ${r.strategy}: 실행 ${r.runs}회. 7.4는 전략당 3회 반복 후 중앙값을 요구한다.`);
    }
    if (r.devRuns > 0) {
      L.push(`> ⚠ ${r.strategy}: 개발 확인용 실행 ${r.devRuns}건 포함(본 측정 120초 미만). ` +
             `**7.6 기록 양식은 최종 측정용 실행으로만 채운다**(7.4). ` +
             `\`DURATION_SEC=120 load-test/scripts/run.sh ...\`로 다시 돌린다.`);
    }
    if (r.unclassified > 0) {
      L.push(`> ⚠ ${r.strategy}: 미분류 ${r.unclassified}건. **이 실행의 숫자는 쓰지 않는다** — ` +
             `계약에 새 오류 코드가 생겼는데 classify.js가 따라가지 못한 것이다.`);
    }
  }
  return L.join('\n');
}

const args = parseArgs(process.argv);
const all = loadRuns(args.scenario, args.strategy);

if (all.length === 0) {
  console.log('결과 파일이 없다. 먼저 측정을 실행한다:');
  console.log('  load-test/scripts/run.sh high pessimistic');
  process.exit(0);
}

const scenarios = args.scenario ? [args.scenario] : [...new Set(all.map((r) => r.row.scenario))];
for (const sc of scenarios) {
  const inScenario = all.filter((r) => r.row.scenario === sc);
  const strategies = STRATEGY_ORDER.filter((s) => inScenario.some((r) => r.row.strategy === s));
  const rows = strategies.map((s) => buildRow(s, inScenario.filter((r) => r.row.strategy === s)));
  console.log(render(sc, rows));
  console.log();
}
