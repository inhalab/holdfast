#!/usr/bin/env node
// summarize.mjs — 측정 결과를 설계서 7.6 기록 양식 표로 출력한다.
//
//   load-test/scripts/summarize.mjs [--scenario high] [--strategy pessimistic]
//                                    [--session 20260903-1745 | --session all]
//
// ## 측정 세션
//
// 결과 파일명과 row.session에 측정 세션 태그가 들어간다. **지정하지 않으면
// 가장 최근 세션만 읽는다** — 재측정한 값과 예전 값이 한 중앙값에 섞이는 것을
// 막기 위해서다. 실제로 그렇게 섞여 폐기분과 M3 원본이 함께 집힌 적이 있다
// (docs/results/discarded-measurements.md 4번).
//
// 세션 태그는 정렬하면 시간순이 되는 형식(YYYYMMDD-HHMM)이라 최신 판정이
// 문자열 비교로 끝난다. 태그가 없는 옛 파일은 `(이전)`으로 묶이며, 진짜 세션
// 태그보다 항상 낮게 정렬돼 새 세션이 하나라도 있으면 밀려난다.
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
//               scripts/metrics-snapshot.mjs로 results/에 파일로 남긴다. 7.4.2)
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

/** 세션 태그가 없는 옛 결과의 묶음 이름. 어떤 실제 태그보다도 낮게 정렬된다. */
const LEGACY_SESSION = '(이전)';

function sessionOf(row) {
  return row.session || LEGACY_SESSION;
}

function loadRuns(scenario, strategy) {
  if (!existsSync(RESULTS_DIR)) return [];
  return readdirSync(RESULTS_DIR)
    // **최상위만 읽는다.** 재귀하지 않으므로 results/discarded/ 에 격리해 둔
    // 폐기분은 잡히지 않는다 — 치워 둔 것을 다시 집으면 의미가 없다.
    .filter((f) => f.endsWith('.json') && f !== 'smoke-summary.json')
    .map((f) => {
      try { return { file: f, ...JSON.parse(readFileSync(join(RESULTS_DIR, f), 'utf8')) }; }
      catch { return null; }
    })
    .filter((r) => r && r.row)
    .filter((r) => (!scenario || r.row.scenario === scenario))
    .filter((r) => (!strategy || r.row.strategy === strategy));
}

/**
 * 세션을 고른다. 지정이 없으면 **가장 최근 세션 하나**만 남긴다.
 *
 * `--session all`이면 전부 남긴다 — 세션 간 비교를 손으로 할 때 쓰고,
 * 이때 나오는 중앙값은 여러 묶음이 섞인 값이라 기록 양식에 싣지 않는다.
 */
/** run.sh가 만드는 태그 형식. 이 모양인 것만 "최신" 후보로 본다. */
const SESSION_TAG = /^\d{8}-\d{4}$/;

function selectSession(runs, requested) {
  const available = [...new Set(runs.map((r) => sessionOf(r.row)))].sort();
  if (requested === 'all') return { runs, picked: 'all', available };

  // **최신을 고를 때는 형식에 맞는 태그만 본다.** 단순 문자열 최대값으로 고르면
  // 손으로 붙인 태그가 진짜 측정을 가린다 — 실제로 임시 실행에 쓴 `TESTONLY`가
  // `2026...`보다 크게 정렬돼 기본 선택을 가로챈 적이 있다. 형식에 맞는 것이
  // 하나도 없을 때만 전체에서 고른다.
  const dated = available.filter((s) => SESSION_TAG.test(s));
  const pool = dated.length > 0 ? dated : available;
  const picked = requested || pool[pool.length - 1];
  return { runs: runs.filter((r) => sessionOf(r.row) === picked), picked, available };
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

/** 7.2.2 지속 경합 — 7.6과 다른 표다. 헤드라인은 홀드 요청만의 p95다. */
function buildSustained(strategy, runs) {
  const na = NOT_APPLICABLE[strategy] || [];
  const dash = (key, value) => (na.includes(key) ? '—' : value);
  return {
    strategy,
    runs: runs.length,
    devRuns: runs.filter((r) => r.row.isFinalRun !== true).length,
    recoveryFailed: runs.reduce((a, r) => a + (r.row.counts?.recoveryFailed || 0), 0),
    cells: {
      holdP95: fmtMs(median(runs.map((r) => r.row.holdP95))),
      holdP99: fmtMs(median(runs.map((r) => r.row.holdP99))),
      lockGiveup: dash('lockGiveup', fmtPct(median(runs.map((r) => r.row.lockGiveupRate)))),
      poolWait: '?',                                   // ← Actuator(7.1)
      poolUsage: '?',                                  // ← Actuator(7.1)
      retries: dash('retries', '?'),
      violations: dash('violations', '?'),
      recovery: fmtPct(median(runs.map((r) => r.row.recoveryRate))),
      rejection409: fmtPct(median(runs.map((r) => r.row.normalRejectionRate))),
      errorRate: fmtPct(median(runs.map((r) => r.row.errorRate))),
    },
  };
}

function renderSustained(rows) {
  const L = [];
  L.push('## 7.2.2 지속 경합 시나리오 — **7.6 기록 양식과 다른 표다**');
  L.push('');
  L.push('이 표의 p95에는 락 대기가 들어 있고 7.6의 p95에는 들어 있지 않다.');
  L.push('같은 열에 놓으면 두 값이 같은 것을 재는 것처럼 보인다.');
  L.push('');
  L.push('| 전략 | 홀드 p95 | 홀드 p99 | 락 포기율 | 풀 대기 | 풀 점유 | 재시도 | 제약위반 | 회수 성공률 | 409율 | 오류율 |');
  L.push('|---|---|---|---|---|---|---|---|---|---|---|');
  for (const r of rows) {
    const c = r.cells;
    L.push(`| ${r.strategy} | ${c.holdP95} | ${c.holdP99} | ${c.lockGiveup} | ${c.poolWait} | ` +
           `${c.poolUsage} | ${c.retries} | ${c.violations} | ${c.recovery} | ${c.rejection409} | ${c.errorRate} |`);
  }
  L.push('');
  L.push('**홀드 p95는 `operation=hold` 태그만의 값이다** — 해제 요청은 빠져 있다(7.2.2).');
  L.push('`?` = 출처가 k6가 아니다. 풀 대기·점유는 Actuator에서 가져온다(7.1).');
  L.push('');
  for (const r of rows) {
    if (r.recoveryFailed > 0) {
      L.push(`> ❌ ${r.strategy}: 회수 실패 ${r.recoveryFailed}건. **이 실행을 폐기한다** — ` +
             `홀드해 놓고 해제하지 못한 좌석이 순환에서 빠졌다(7.2.2).`);
    }
    if (r.runs !== 3) {
      L.push(`> ⚠ ${r.strategy}: 실행 ${r.runs}회. 7.4는 전략당 3회 반복 후 중앙값을 요구한다.`);
    }
    if (r.devRuns > 0) {
      L.push(`> ⚠ ${r.strategy}: 파일럿/개발 실행 ${r.devRuns}건 포함(본 측정 120초 미만). ` +
             `좌석 수 보정에만 쓰고 기록 양식에는 싣지 않는다.`);
    }
    // 락 포기율은 판정 지표가 아니다(7.2.2 보정 기록). 확정 설정에서 0이 정상이고,
    // 0이기 때문에 7.6.1의 함정을 피해 두 전략의 홀드 p95를 직접 비교할 수 있다.
  }
  return L.join('\n');
}

const args = parseArgs(process.argv);
const loaded = loadRuns(args.scenario, args.strategy);

if (loaded.length === 0) {
  console.log('결과 파일이 없다. 먼저 측정을 실행한다:');
  console.log('  load-test/scripts/run.sh high pessimistic');
  process.exit(0);
}

const { runs: all, picked, available } = selectSession(loaded, args.session);

if (all.length === 0) {
  console.log(`세션 ${picked} 의 결과가 없다. 있는 세션: ${available.join(', ')}`);
  process.exit(1);
}

// **어느 묶음을 읽었는지 먼저 밝힌다.** 표만 보면 그 숫자가 어느 측정의 것인지
// 알 수 없고, 그것을 모른 채 기록 양식에 옮기는 것이 이 기능을 만든 이유다.
console.log(`<!-- 측정 세션: ${picked}${available.length > 1 ? ` (전체: ${available.join(', ')}) ` : ' '}-->`);
if (picked === 'all' && available.length > 1) {
  console.log('> ⚠ `--session all` — 여러 측정 묶음이 한 중앙값에 섞여 있다.');
  console.log('> **7.6 기록 양식에 싣지 않는다.** 세션을 지정해 다시 뽑는다.');
}

// **한 세션 안에서 커밋이 갈리면 경고하되 세션을 쪼개지 않는다**(7.3).
//
// 세션은 "한 묶음으로 재려고 한 의도"의 라벨이고, 7.4.2의 대조군은 그 의도된
// 묶음 안에서만 뜻이 있다. 해시가 다르다고 자동으로 쪼개면 대조군이 어느
// 조각에 속하는지가 임의로 정해진다 — `none` 대조군만 다른 조각으로 떨어지면
// 나머지 넷은 대조군 없는 묶음이 된다.
//
// 더 나쁜 것은 자동 분할이 오염을 감춘다는 점이다. 쪼개진 두 묶음이 각각
// 깨끗해 보이지만 실제로는 서로 비교할 수 없는 값이다. 그래서 표는 그대로
// 내되 어느 전략이 어느 해시였는지 보여주고, 폐기 여부는 사람이 정한다 —
// "숫자 하나만 보고 판정하지 않는다"(discarded-measurements.md).
const commits = [...new Set(all.map((r) => r.row.commit ?? '(기록 없음)'))];
if (commits.length > 1) {
  console.log(`> ⚠ **이 세션은 커밋 ${commits.length}개에 걸쳐 있다** — ${commits.join(', ')}`);
  console.log('> 전략마다 다른 코드를 잰 것이므로 **전략 간 비교가 성립하지 않을 수 있다**(7.3).');
  for (const c of commits) {
    const who = [...new Set(all.filter((r) => (r.row.commit ?? '(기록 없음)') === c)
                               .map((r) => r.row.strategy))];
    console.log(`>   - \`${c}\` — ${who.join(', ')}`);
  }
  console.log('> 측정 대상 코드가 그 사이 바뀌었는지 확인하고, 바뀌었다면 폐기한다.');
}
// 이미지가 갈리면 커밋보다 더 직접적인 문제다 — 실제로 돌아간 바이너리가 다르다.
const images = [...new Set(all.map((r) => r.row.image).filter(Boolean))];
if (images.length > 1) {
  console.log(`> ⚠ **이 세션은 앱 이미지 ${images.length}개에 걸쳐 있다** — ${images.join(', ')}`);
  console.log('> 커밋이 같아도 다른 바이너리를 잰 것이다. 전략 간 비교가 성립하지 않는다(7.3).');
}
const stale = all.some((r) => r.row.imageStale === 'yes');
if (stale) {
  console.log('> ℹ 앱 이미지가 HEAD 커밋보다 오래됐다 — 트리가 아니라 그 이미지를 잰 값이다(7.3).');
}

const dirty = [...new Set(all.filter((r) => r.row.dirty === true).map((r) => r.row.strategy))];
if (dirty.length > 0) {
  console.log(`> ⚠ **커밋되지 않은 변경으로 잰 실행이 있다** — ${dirty.join(', ')}`);
  console.log('> 해시만으로는 재현되지 않는다(7.3).');
}
console.log();

const scenarios = args.scenario ? [args.scenario] : [...new Set(all.map((r) => r.row.scenario))];
for (const sc of scenarios) {
  const inScenario = all.filter((r) => r.row.scenario === sc);
  const strategies = STRATEGY_ORDER.filter((s) => inScenario.some((r) => r.row.strategy === s));
  // 7.2.2 지속 경합은 7.6 기록 양식에 넣지 않는다. 그쪽 p95에는 락 대기가 들어
  // 있고 7.6의 p95에는 들어 있지 않아, 같은 표에 놓으면 두 값이 같은 것을 재는
  // 것처럼 보인다.
  if (sc === 'sustained') {
    console.log(renderSustained(strategies.map((s) => buildSustained(s, inScenario.filter((r) => r.row.strategy === s)))));
    console.log();
    continue;
  }
  const rows = strategies.map((s) => buildRow(s, inScenario.filter((r) => r.row.strategy === s)));
  console.log(render(sc, rows));
  console.log();
}
