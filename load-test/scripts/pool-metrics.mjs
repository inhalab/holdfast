#!/usr/bin/env node
// pool-metrics.mjs — 커넥션 풀 대기 스냅샷 (이슈 #44, concurrency-spec 7.1 "해석의 핵심").
//
//   node load-test/scripts/pool-metrics.mjs [label]
//
// nginx(:8080)는 두 인스턴스를 라운드로빈하므로 그 경유로는 "이 인스턴스의
// 풀 대기"를 확정할 수 없다. docker-compose.yml이 app1(:18081)·app2(:18082)를
// 직접 열어두었으므로 이 스크립트는 그 포트로 각 인스턴스를 따로 찌른다.
//
// summarize.mjs의 `poolWait` 칸은 이 스크립트가 채우지 않는다(summarize.mjs
// 주석 참고 — 출처가 k6가 아닌 값은 7.6 표에 사람이 직접 옮겨 적는다). 이
// 스크립트는 verify.sh와 같은 자리에서, 같은 방식으로 콘솔에 값을 출력하는
// 역할만 한다.
//
// COUNT/TOTAL_TIME/MAX는 앱 기동 시점부터 누적이다. 전략 하나를 3회 반복하는
// 동안(7.4) 인스턴스를 재시작하지 않으므로, 이 값은 "그 회차만의" 값이 아니라
// "지금까지 누적"이다. 전략 비교에는 마지막 회차 직후 값을 쓴다.

const INSTANCES = [
  { name: 'app1', base: process.env.APP1_URL ?? 'http://localhost:18081' },
  { name: 'app2', base: process.env.APP2_URL ?? 'http://localhost:18082' },
];

async function fetchMetric(base, name) {
  const res = await fetch(`${base}/actuator/metrics/${name}`);
  if (!res.ok) throw new Error(`${name} → HTTP ${res.status}`);
  return res.json();
}

function stat(measurements, key) {
  return measurements.find((m) => m.statistic === key)?.value ?? null;
}

function fmtMs(seconds) {
  return seconds === null ? 'N/A' : `${(seconds * 1000).toFixed(2)}ms`;
}

async function snapshot(instance) {
  const [pending, acquire] = await Promise.all([
    fetchMetric(instance.base, 'hikaricp.connections.pending'),
    fetchMetric(instance.base, 'hikaricp.connections.acquire'),
  ]);

  const pendingNow = stat(pending.measurements, 'VALUE');
  const count = stat(acquire.measurements, 'COUNT');
  const totalTime = stat(acquire.measurements, 'TOTAL_TIME');
  const max = stat(acquire.measurements, 'MAX');
  const avg = count && totalTime !== null ? totalTime / count : null;

  return { instance: instance.name, pendingNow, count, avg, max };
}

const label = process.argv[2] ?? '';
console.log(`[pool] 커넥션 풀 스냅샷${label ? ` — ${label}` : ''} (7.1 해석의 핵심, MAX는 2분 슬라이딩 윈도)`);

for (const instance of INSTANCES) {
  try {
    const s = await snapshot(instance);
    console.log(
      `  ${s.instance}: pending=${s.pendingNow} ` +
      `acquire.count=${s.count} acquire.avg=${fmtMs(s.avg)} acquire.max=${fmtMs(s.max)}`
    );
  } catch (e) {
    // verify.sh와 같은 태도: 관측 실패로 측정 전체를 죽이지 않는다.
    console.log(`  ${instance.name}: 조회 실패 (${e.message}) — 앱이 떠 있는지, 포트가 열려 있는지 확인`);
  }
}
