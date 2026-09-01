// smoke.js — k6 실행 환경과 **집계 파이프라인**이 도는지 확인한다.
//
// 예약 API가 아직 없으므로 지금 실제로 돌릴 수 있는 것은 이 파일뿐이다.
// /api/status와 /api/health만 때리지만, 응답을 reservation.js와 **같은**
// classify → record → summary 경로에 통과시킨다. 그래야 API가 생기기 전에
// 집계 로직이 실제로 도는지, 버킷이 제대로 갈리는지 확인할 수 있다.
//
// 실행:
//   docker compose -f docker-compose.yml -f docker-compose.k6.yml \
//     --profile load run --rm k6 run /scenarios/smoke.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { record } from './lib/metrics.js';
import { extractRow, renderText } from './lib/summary.js';
import { SUMMARY_TREND_STATS } from './lib/config.js';

const BASE_URL = __ENV.BASE_URL || 'http://nginx:80';

// 스모크는 워밍업을 짧게 둔다. 목적이 측정이 아니라 경로 확인이라서다.
// (본 측정의 워밍업 30초는 7.4 — reservation.js가 따른다.)
const WARMUP_MS = parseInt(__ENV.WARMUP_SEC || '3', 10) * 1000;
const DURATION_SEC = parseInt(__ENV.DURATION_SEC || '10', 10);

export const options = {
  summaryTrendStats: SUMMARY_TREND_STATS,
  vus: parseInt(__ENV.VUS || '5', 10),
  duration: `${WARMUP_MS / 1000 + DURATION_SEC}s`,
  thresholds: {
    'measured_req_duration': ['p(95)<3000'],
    'error_rate': ['rate<0.01'],
    'bucket_unclassified_total': ['count==0'],
  },
};

export default function () {
  // 로드밸런싱 확인. nginx 뒤에서 app1/app2가 번갈아 나와야 정상이다.
  const statusRes = http.get(`${BASE_URL}/api/status`, { tags: { operation: 'status' } });
  record(statusRes, WARMUP_MS, { operation: 'status' });
  check(statusRes, {
    'status 200': (r) => r.status === 200,
    'instance 필드 존재': (r) => {
      try { return r.json('instance') !== undefined; } catch (e) { return false; }
    },
  });

  // DB·Redis 연결 확인.
  const healthRes = http.get(`${BASE_URL}/api/health`, { tags: { operation: 'health' } });
  record(healthRes, WARMUP_MS, { operation: 'health' });
  check(healthRes, {
    'health 200': (r) => r.status === 200,
    'db up': (r) => {
      try { return r.json('db') === 'up'; } catch (e) { return false; }
    },
    'redis up': (r) => {
      try { return r.json('redis') === 'up'; } catch (e) { return false; }
    },
  });

  sleep(1);
}

export function handleSummary(data) {
  const cfg = {
    strategy: 'smoke',
    scenario: 'smoke',
    run: 1,
    vus: parseInt(__ENV.VUS || '5', 10),
    seats: 0,
    warmupSec: WARMUP_MS / 1000,
    durationSec: DURATION_SEC,
  };
  const row = extractRow(data, cfg);
  return {
    '/results/smoke-summary.json': JSON.stringify({ config: cfg, row, raw: data }, null, 2),
    stdout: renderText(row),
  };
}
