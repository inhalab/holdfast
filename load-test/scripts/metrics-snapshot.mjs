#!/usr/bin/env node
// metrics-snapshot.mjs — Actuator가 출처인 7.1 지표를 회차마다 파일로 남긴다.
//
//   node load-test/scripts/metrics-snapshot.mjs --label "..." --out <경로>
//
// (구 pool-metrics.mjs. 커넥션 풀만 보다가 앱 커스텀 메트릭까지 넓혔다.)
//
// ## 왜 파일로 남기는가 — 같은 사고를 세 번 겪었다
//
// 7.1은 커넥션 풀 대기·낙관적 재시도·제약 위반의 출처를 "앱 커스텀 메트릭"으로
// 못박았다. 그런데 이 값들은 **인스턴스 기동 시점부터 누적**이라 앱을 재기동하면
// 0으로 돌아간다.
//
// 7.4.2가 세션마다 대조군(`none` 고경합)을 의무화했고, 대조군으로 넘어가려면
// `HOLDFAST_STRATEGY`를 바꿔 앱을 재기동해야 한다. **그 순간 방금 측정한
// 전략의 카운터가 통째로 사라진다.** pessimistic·지속 경합·optimistic에서
// 연달아 같은 일이 났고, 매번 보강 측정을 다시 돌려 40분씩 썼다.
//
// 콘솔 출력만으로는 부족하다 — 로그를 파일로 받지 않으면 터미널 스크롤백과
// 함께 사라진다. 그래서 **회차 직후 자동으로 파일에 쓴다.** 전략 전환 전에
// 마지막 스냅샷이 확보되므로 보강 측정이 필요 없다.
//
// ## 값은 누적이다. 델타는 읽는 쪽이 계산한다
//
// 회차 사이의 증가분을 보려면 연속한 두 스냅샷을 빼면 된다. run.sh가 첫 회차
// 전에 `run0` 기준선을 한 장 찍어두므로 1회차도 델타를 낼 수 있다.
//
// nginx(:8080)는 두 인스턴스를 라운드로빈하므로 그 경유로는 "이 인스턴스의 값"을
// 확정할 수 없다. docker-compose.yml이 app1(:18081)·app2(:18082)를 직접
// 열어두었으므로 각 인스턴스를 따로 찌른다.

import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

const INSTANCES = [
  { name: 'app1', base: process.env.APP1_URL ?? 'http://localhost:18081' },
  { name: 'app2', base: process.env.APP2_URL ?? 'http://localhost:18082' },
];

/**
 * 7.1이 Actuator를 출처로 지정한 지표들.
 *
 * `optional: true`는 "그 전략에서만 등록되는 메트릭"이라는 뜻이다. 낙관적
 * 재시도는 optimistic 전략의 빈이 만들므로 다른 전략에서는 존재하지 않는다.
 * 없는 것과 0인 것을 구분해 기록한다 — 7.6의 `—`(해당 없음)와 `0`(쟀는데 0)을
 * 섞지 않기 위해서다.
 */
const METRICS = [
  { name: 'hikaricp.connections.pending', stats: ['VALUE'] },
  { name: 'hikaricp.connections.acquire', stats: ['COUNT', 'TOTAL_TIME', 'MAX'] },
  { name: 'hikaricp.connections.usage', stats: ['COUNT', 'TOTAL_TIME', 'MAX'] },
  { name: 'holdfast.optimistic.retries', stats: ['COUNT'], optional: true },
  { name: 'holdfast.optimistic.retry-exhausted', stats: ['COUNT'], optional: true },
  // **제약 이름별로도 받는다.** 앱은 constraint 태그를 붙여 세는데
  // (ApiExceptionHandler), 태그 없이 조회하면 Actuator가 전부 합쳐 하나로 준다.
  // 그러면 "제약 위반 N건"이 어느 제약인지 알 수 없다 — U-2(앱 락이 샜다)와
  // U-13(멱등키 경합)은 뜻이 정반대인데 같은 칸에 들어간다(7.6 해석 주의).
  { name: 'holdfast.constraint.violations', stats: ['COUNT'], byTag: 'constraint' },
];

function parseArgs(argv) {
  const out = {};
  for (let i = 2; i < argv.length; i += 2) {
    out[argv[i].replace(/^--/, '')] = argv[i + 1];
  }
  return out;
}

async function fetchMetric(base, name, tag) {
  const url = `${base}/actuator/metrics/${name}` + (tag ? `?tag=${encodeURIComponent(tag)}` : '');
  const res = await fetch(url);
  // 404는 "앱은 살아 있는데 그 미터가 아직 없다"는 뜻이다. Micrometer 카운터는
  // 처음 증가할 때 등록되는 경우가 있어, 한 번도 발생하지 않은 사건은 404가 된다.
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

function stat(measurements, key) {
  return measurements.find((m) => m.statistic === key)?.value ?? null;
}

async function snapshot(instance) {
  const values = {};
  let reachable = true;
  for (const metric of METRICS) {
    try {
      const body = await fetchMetric(instance.base, metric.name);
      if (body === null) {
        // 미등록. optional이면 "해당 없음", 아니면 "아직 한 번도 발생하지 않음"이다.
        values[metric.name] = metric.optional ? null : 0;
        // 분해 칸도 같은 모양으로 남긴다. 키가 있다 없다 하면 읽는 쪽이
        // "안 쟀음"과 "쟀는데 비었음"을 구분할 수 없다.
        if (metric.byTag) values[`${metric.name}.by-${metric.byTag}`] = {};
        continue;
      }
      const picked = {};
      for (const s of metric.stats) picked[s] = stat(body.measurements, s);
      values[metric.name] = metric.stats.length === 1 ? picked[metric.stats[0]] : picked;

      // 태그별 분해. availableTags가 알려주는 값마다 한 번씩 더 물어 본다.
      if (metric.byTag) {
        const tagValues = body.availableTags?.find((t) => t.tag === metric.byTag)?.values ?? [];
        const breakdown = {};
        for (const v of tagValues) {
          const tagged = await fetchMetric(instance.base, metric.name, `${metric.byTag}:${v}`);
          breakdown[v] = tagged === null ? null : stat(tagged.measurements, metric.stats[0]);
        }
        values[`${metric.name}.by-${metric.byTag}`] = breakdown;
      }
    } catch (e) {
      reachable = false;
      values[metric.name] = null;
    }
  }
  return { instance: instance.name, reachable, values };
}

function fmtMs(seconds) {
  return seconds === null || seconds === undefined ? 'N/A' : `${(seconds * 1000).toFixed(2)}ms`;
}

const args = parseArgs(process.argv);
const label = args.label ?? '';
const snapshots = [];

for (const instance of INSTANCES) {
  try {
    snapshots.push(await snapshot(instance));
  } catch (e) {
    // verify.sh와 같은 태도: 관측 실패로 측정 전체를 죽이지 않는다.
    snapshots.push({ instance: instance.name, reachable: false, error: String(e), values: {} });
  }
}

console.log(`[metrics] Actuator 스냅샷${label ? ` — ${label}` : ''} (7.1, 값은 기동 이후 누적)`);
for (const s of snapshots) {
  if (!s.reachable) {
    console.log(`  ${s.instance}: 조회 실패 — 앱이 떠 있는지, 포트가 열려 있는지 확인`);
    continue;
  }
  const acq = s.values['hikaricp.connections.acquire'] ?? {};
  const use = s.values['hikaricp.connections.usage'] ?? {};
  const avg = acq.COUNT ? acq.TOTAL_TIME / acq.COUNT : null;
  const useAvg = use.COUNT ? use.TOTAL_TIME / use.COUNT : null;
  const retries = s.values['holdfast.optimistic.retries'];
  const exhausted = s.values['holdfast.optimistic.retry-exhausted'];
  console.log(
    `  ${s.instance}: pending=${s.values['hikaricp.connections.pending']} ` +
    `acquire.avg=${fmtMs(avg)} usage.avg=${fmtMs(useAvg)} ` +
    `재시도=${retries ?? '—'} 소진=${exhausted ?? '—'} ` +
    `제약위반=${s.values['holdfast.constraint.violations']}`
  );
}

if (args.out) {
  mkdirSync(dirname(args.out), { recursive: true });
  writeFileSync(args.out, JSON.stringify({
    label,
    capturedAt: new Date().toISOString(),
    strategy: args.strategy ?? null,
    scenario: args.scenario ?? null,
    run: args.run ?? null,
    snapshots,
  }, null, 2));
  console.log(`  → ${args.out}`);
}
