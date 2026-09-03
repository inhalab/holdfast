// deadlock.js — 데드락 회피 규칙 검증 시나리오 (설계서 7.2.1).
//
// **성능을 재는 시나리오가 아니다.** 판정 기준은 처리량도 p95도 아니고
// **데드락 발생 0건**이다. 결과를 7.6 기록 양식에 넣지 않는다.
//
// ## 왜 따로 필요한가
//
// 경합도 3단계(7.2)는 요청당 1석을 유지한다. 여러 좌석을 한 번에 잡으면 거절
// 사유가 좌석별로 섞여 경합도 해석이 흐려지기 때문이다(api-spec 4.2).
//
// 그런데 그러면 **erd.md 4.1의 좌석 ID 오름차순 획득과 concurrency-spec 5.1의
// 전역 락 순서가 부하 테스트에서 한 번도 실행되지 않는다.** 1석만 잡으면 정렬할
// 것이 없고, 여러 좌석을 잡는 코드 경로가 통째로 미검증으로 남는다. 데드락은
// 바로 그 경로에서만 난다.
//
// 실행: load-test/scripts/run-deadlock.sh

import { sleep } from 'k6';
import exec from 'k6/execution';
import { loadConfig, stagesFor, SUMMARY_TREND_STATS } from './lib/config.js';
import { record } from './lib/metrics.js';
import { createHold, confirmReservation } from './lib/api.js';
import { extractRow, renderText } from './lib/summary.js';

const cfg = loadConfig();

// 요청당 3석. 이 시나리오의 존재 이유이므로 환경변수로 낮추지 않는다.
const SEATS_PER_HOLD = 3;

export const options = {
  stages: stagesFor(cfg),
  summaryTrendStats: SUMMARY_TREND_STATS,
  thresholds: {
    // 5xx는 여기서도 결함이다. 데드락은 409로 변환되어야 하고(api-spec 3.3)
    // 그대로 500으로 새어 나오면 안 된다.
    'error_rate': ['rate<0.001'],
    'bucket_unclassified_total': ['count==0'],
    // p95 임계값은 걸지 않는다. 이 시나리오의 판정 기준이 아니다.
  },
  tags: { strategy: cfg.strategy, scenario: 'deadlock', run: String(cfg.run) },
};

export function setup() {
  console.log(`[setup] 데드락 검증 — 전략=${cfg.strategy} 좌석풀=${cfg.seats} ` +
              `요청당=${SEATS_PER_HOLD}석 VU=${cfg.vus}`);
  console.log('[setup] 판정은 k6가 아니라 pg_stat_database.deadlocks 차이로 한다 (7.2.1)');
  if (cfg.seats < SEATS_PER_HOLD * 2) {
    // 좌석 풀이 너무 작으면 서로 다른 순서의 겹침이 생기지 않아 검증이 성립하지 않는다.
    console.warn(`[setup] 좌석 풀이 ${cfg.seats}석이라 순서가 엇갈릴 여지가 적다. ` +
                 `SCENARIO=high(10석) 이상을 권장한다.`);
  }
  return {};
}

export default function () {
  const userId = exec.vu.idInTest;
  const seatIds = pickShuffledSeats(cfg, SEATS_PER_HOLD);

  const holdRes = createHold(cfg.baseUrl, userId, cfg.sessionId, seatIds);
  record(holdRes, cfg.warmupMs, { operation: 'hold' });

  if (holdRes.status === 201) {
    let holdId = null;
    try { holdId = holdRes.json('holdId'); } catch (e) { holdId = null; }
    if (holdId) {
      const confirmRes = confirmReservation(cfg.baseUrl, userId, holdId);
      record(confirmRes, cfg.warmupMs, { operation: 'confirm' });
    }
  }

  sleep(1);
}

/**
 * 좌석을 고르되 **정렬하지 않고 뒤섞어 보낸다.**
 *
 * 이것이 이 시나리오의 핵심이다. 5.1이 정렬을 요구하는 주체는 애플리케이션이다.
 * 클라이언트가 이미 정렬해 보내면 서버가 정렬을 빠뜨려도 데드락이 나지 않아,
 * 서버의 정렬 로직이 실제로 도는지 확인할 수 없다. 두 요청이 반대 순서로 좌석을
 * 요청해야 그 규칙이 시험된다.
 *
 * reservation.js는 정렬해 보낸다(잘 동작하는 클라이언트). 여기서만 뒤섞는다.
 */
function pickShuffledSeats(c, count) {
  const pool = [];
  for (let i = 0; i < c.seats; i++) pool.push(c.seatIdBase + i);

  // Fisher-Yates로 앞에서 count개만 뽑는다.
  for (let i = 0; i < count && i < pool.length; i++) {
    const j = i + Math.floor(Math.random() * (pool.length - i));
    const t = pool[i]; pool[i] = pool[j]; pool[j] = t;
  }
  return pool.slice(0, Math.min(count, pool.length));
}

export function handleSummary(data) {
  const row = extractRow(data, Object.assign({}, cfg, { scenario: 'deadlock' }));
  const out = {};
  // resultPath와 같은 규칙으로 측정 세션 태그를 넣는다(config.js). 이 시나리오는
  // scenario 이름이 profile 키가 아니라 'deadlock'이라 경로를 직접 만든다.
  out[`/results/${cfg.strategy}-deadlock-${cfg.measureSession}-run${cfg.run}.json`] =
    JSON.stringify({ config: cfg, seatsPerHold: SEATS_PER_HOLD, row, raw: data }, null, 2);
  out.stdout = renderText(row) +
    '\n 이 시나리오의 판정 기준은 위 숫자가 아니라 데드락 0건이다.\n' +
    ' run-deadlock.sh가 pg_stat_database.deadlocks 차이를 출력한다 (7.2.1).\n\n';
  return out;
}
