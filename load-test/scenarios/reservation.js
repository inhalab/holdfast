// reservation.js — 좌석 선점·확정 경합 시나리오 (설계서 7장 본 측정).
//
// **예약 API는 아직 구현되지 않았다.** 이 시나리오는 API가 생겼을 때 그대로
// 돌도록 계약(openapi.yaml)에 맞춰 미리 써 둔 것이고, 지금 실행하면 404가
// 미분류로 잡힌다. 지금 확인할 수 있는 것은 smoke.js뿐이다.
//
// 실행:
//   SCENARIO=high STRATEGY=pessimistic RUN=1 k6 run /scenarios/reservation.js
//
// 경합도는 SCENARIO(low|high|extreme)로 전환한다 — 7.2.

import { sleep } from 'k6';
import exec from 'k6/execution';
import { loadConfig, stagesFor, resultPath, SUMMARY_TREND_STATS } from './lib/config.js';
import { record, inWarmup, recordHoldOutcome } from './lib/metrics.js';
import { createHold, confirmReservation } from './lib/api.js';
import { extractRow, renderText } from './lib/summary.js';

const cfg = loadConfig();

export const options = {
  stages: stagesFor(cfg),
  summaryTrendStats: SUMMARY_TREND_STATS,
  // 내장 http_req_duration에는 워밍업이 섞이므로 임계값을 걸지 않는다.
  // 검수 기준(p95 3초)은 워밍업을 제외한 measured_req_duration에 건다.
  thresholds: {
    'measured_req_duration': ['p(95)<3000'],
    // 5xx는 측정값이 아니라 결함이다(api-spec 3.3). 거의 0이어야 한다.
    'error_rate': ['rate<0.001'],
    // 미분류가 하나라도 나오면 집계가 계약을 못 따라간 것이다(classify.js).
    'bucket_unclassified_total': ['count==0'],
  },
  // 태그에 전략·시나리오를 실어 결과 JSON만 보고도 어떤 실행인지 알 수 있게 한다.
  tags: { strategy: cfg.strategy, scenario: cfg.scenario, run: String(cfg.run) },
};

export function setup() {
  console.log(
    `[setup] 전략=${cfg.strategy} 시나리오=${cfg.scenario} ` +
    `좌석=${cfg.seats} VU=${cfg.vus} 워밍업=${cfg.warmupSec}s 본측정=${cfg.durationSec}s`
  );
  console.log('[setup] 시드 초기화는 k6가 하지 않는다 — scripts/seed.sh를 먼저 실행한다 (7.4-1)');
  return { startedAt: new Date().toISOString() };
}

export default function () {
  // 사용자는 VU마다 다르게 둔다. 같은 사용자로 몰면 1인 최대 매수(CS-6)가 먼저
  // 걸려서, 7.2가 보려는 좌석 경합이 아니라 할당량 경합을 재게 된다.
  const userId = exec.vu.idInTest;

  // 좌석은 시드가 만든 연속 구간에서 고른다. 극단(1석)이면 모든 VU가 같은 좌석을
  // 노리고, 고경합(10석)이면 10석에 500 VU가 몰린다 — 7.2가 의도한 구도다.
  const seatIds = pickSeats(cfg);

  const holdRes = createHold(cfg.baseUrl, userId, cfg.sessionId, seatIds);
  const hold = record(holdRes, cfg.warmupMs);

  // 선점에 성공했을 때만 확정으로 넘어간다. 거절은 정상 흐름이므로
  // 여기서 재시도하지 않는다 — 앱 레벨 재시도를 클라이언트가 흉내 내면
  // 전략별 재시도 특성(4.3)이 측정에서 가려진다.
  if (holdRes.status === 201) {
    let holdId = null;
    try { holdId = holdRes.json('holdId'); } catch (e) { holdId = null; }
    if (holdId) {
      const confirmRes = confirmReservation(cfg.baseUrl, userId, holdId);
      record(confirmRes, cfg.warmupMs);

      // 팬텀 홀드(7.6.3): 홀드에 201을 받고도 확정에 실패한 경우.
      // 워밍업 구간은 다른 지표와 같은 기준으로 제외한다(7.4).
      if (!inWarmup(cfg.warmupMs)) {
        recordHoldOutcome(confirmRes.status === 201);
      }
    }
  }

  // 사용자 체감 간격. 0으로 두면 VU가 서버 응답 속도만큼 무한히 밀어붙여
  // 부하가 VU 수가 아니라 서버 처리량에 종속된다.
  sleep(1);
}

function pickSeats(c) {
  const out = [];
  for (let i = 0; i < c.seatsPerHold; i++) {
    const offset = Math.floor(Math.random() * c.seats);
    out.push(c.seatIdBase + offset);
  }
  // 정렬해 보낸다 — 잘 동작하는 클라이언트를 흉내 내는 것이다.
  //
  // 5.1의 정렬 규칙을 지켜야 하는 주체는 서버다. 그 규칙이 실제로 도는지
  // 확인하려면 오히려 **뒤섞어** 보내야 하는데, 그러면 여기서 거절 사유가
  // 섞여 경합도 해석이 흐려진다. 그래서 서버 정렬 검증은 SEATS_PER_HOLD=3으로
  // 뒤섞어 보내는 deadlock.js가 맡는다(7.2.1).
  //
  // 기본값 SEATS_PER_HOLD=1에서는 정렬이 무의미하지만, 이 값을 올려 쓰는
  // 경우에도 경합도 시나리오는 잘 동작하는 클라이언트를 가정한다.
  return [...new Set(out)].sort((a, b) => a - b);
}

export function handleSummary(data) {
  const row = extractRow(data, cfg);
  const out = {};
  out[resultPath(cfg)] = JSON.stringify({ config: cfg, row, raw: data }, null, 2);
  out.stdout = renderText(row);
  return out;
}
