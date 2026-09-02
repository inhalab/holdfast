// sustained.js — 지속 경합 시나리오 (설계서 7.2.2).
//
// **경합도 3단계로는 락 대기를 잴 수 없어서 만든 시나리오다.** 재초기화
// 버스트가 지나면 좌석이 전부 SOLD가 되고, 그 뒤로는 아무도 행 락을 쥐지 않아
// FOR UPDATE가 즉시 성공한다. pessimistic 실측에서 락 포기가 경합도와 무관하게
// 0에 수렴한 이유이며, 그래서 7.5 가설("대기하는 동안 커넥션을 쥔다")을 시험할
// 조건이 만들어지지 않는다.
//
// ## 한 반복은 홀드 → 해제다
//
// 잡은 사람이 곧 반납하므로 **회수가 소비 속도를 자동으로 따라간다.** TTL
// 만료로 회수하면 회수 주체가 전략별 홀드 경로라 전략마다 속도가 달라지고
// (erd 4.1 — none은 아예 회수하지 않는다), 별도 리셋으로 회수하면 리셋이
// 경합 중인 바로 그 행에 주기적으로 쓰기를 한다.
//
// **확정하지 않는 것이 의도다.** 7.5가 말하는 것은 락 획득(CS-1)이고, 확정을
// 넣으면 요청 종류가 셋이 되어 p95가 무엇의 응답시간인지 흐려진다.
// CS-2·CS-3 경합은 경합도 3단계와 Mock PG 지연 시나리오가 담당한다.
//
// **결과를 7.6 기록 양식에 넣지 않는다.** 이쪽 p95에는 락 대기가 들어 있고
// 저쪽에는 들어 있지 않다.
//
// 실행: load-test/scripts/run-sustained.sh <strategy>

import { sleep } from 'k6';
import exec from 'k6/execution';
import { loadConfig, stagesFor, resultPath, SUMMARY_TREND_STATS } from './lib/config.js';
import { record, holdDuration, recordRecovery } from './lib/metrics.js';
import { createHold, releaseHold } from './lib/api.js';
import { extractRow, renderText } from './lib/summary.js';

const cfg = loadConfig();

/**
 * 반복 사이 대기(ms). 기본 1000은 경합도 3단계와 같은 값이고, 낮출수록 도착률이
 * 올라가 행 락 대기열이 깊어진다. 7.2.2의 좌석 수 보정이 목표 구간에 닿지 못해
 * 추가한 손잡이다.
 */
const SLEEP_MS = (() => {
  const v = __ENV.SUSTAINED_SLEEP_MS;
  if (v === undefined || v === '') return 1000;
  const n = parseInt(v, 10);
  if (Number.isNaN(n) || n < 1) throw new Error(`SUSTAINED_SLEEP_MS가 잘못됐다: ${v}`);
  return n;
})();

if (cfg.scenario !== 'sustained') {
  throw new Error(`이 시나리오는 SCENARIO=sustained로만 돈다: ${cfg.scenario}`);
}

export const options = {
  stages: stagesFor(cfg),
  summaryTrendStats: SUMMARY_TREND_STATS,
  thresholds: {
    // 5xx는 여기서도 측정값이 아니라 결함이다(api-spec 3.3).
    'error_rate': ['rate<0.001'],
    'bucket_unclassified_total': ['count==0'],

    // **회수 누수는 실행 폐기 사유다**(7.2.2). 비율이 아니라 절대 건수로 건다 —
    // 좌석이 3석뿐이라 한 건만 새도 재고의 3분의 1이 순환에서 빠진다.
    'recovery_failed_total': ['count==0'],

    // p95 임계값은 걸지 않는다. 검수 기준(REQ-10)의 측정 대상은 경합도
    // 3단계이지 이 시나리오가 아니다.
  },
  tags: { strategy: cfg.strategy, scenario: 'sustained', run: String(cfg.run) },
};

export function setup() {
  console.log(
    `[setup] 지속 경합 — 전략=${cfg.strategy} 좌석=${cfg.seats} VU=${cfg.vus} ` +
    `사용자풀=${cfg.userPool} sleep=${SLEEP_MS}ms 워밍업=${cfg.warmupSec}s 본측정=${cfg.durationSec}s`
  );
  console.log('[setup] 한 반복 = 홀드 → (성공 시) 해제. 확정하지 않는다 (7.2.2)');
  console.log('[setup] 헤드라인 지표는 hold_req_duration p95 — 해제 요청은 제외된다');
  console.log('[setup] 재초기화(7.4.1)를 하지 않는다 — 좌석이 순환하므로 워밍업이 재고를 소모하지 않는다');
  return {};
}

export default function () {
  // 사용자는 요청마다 다르게 둔다. VU 번호를 그대로 쓰면 4매를 채운 뒤 전부
  // QUOTA_EXCEEDED가 된다. 해제가 할당량을 되돌리므로 이 시나리오에서는 덜
  // 치명적이지만, 경합도 3단계와 같은 규칙을 유지한다.
  const userId = (exec.scenario.iterationInTest % cfg.userPool) + 1;

  // 좌석 하나. 소수의 좌석에 VU 전원이 몰리는 것이 이 시나리오의 구도다.
  const seatId = cfg.seatIdBase + Math.floor(Math.random() * cfg.seats);

  const holdRes = createHold(cfg.baseUrl, userId, cfg.sessionId, [seatId]);
  const held = record(holdRes, cfg.warmupMs, { operation: 'hold' });

  // 헤드라인 지표. 해제가 섞이지 않도록 홀드 요청만 따로 담는다(7.2.2).
  if (held.measured) {
    holdDuration.add(holdRes.timings.duration, { bucket: held.bucket, code: held.code });
  }

  if (holdRes.status === 201) {
    let holdId = null;
    try { holdId = holdRes.json('holdId'); } catch (e) { holdId = null; }

    if (holdId === null) {
      // 홀드는 성공했는데 holdId를 읽지 못했다 = 해제할 수단이 없다.
      // 좌석이 순환에서 빠지므로 누수로 센다.
      if (held.measured) recordRecovery(false, { reason: 'no_hold_id' });
    } else {
      // **반드시 해제한다.** 해제하지 않으면 그 좌석은 TTL이 만료될 때까지
      // 순환에서 빠지고, 청소 스케줄러가 없어 none에서는 영구히 빠진다.
      const relRes = releaseHold(cfg.baseUrl, userId, holdId);
      record(relRes, cfg.warmupMs, { operation: 'release' });
      if (held.measured) {
        // 해제는 멱등하므로 정상 흐름에서 2xx가 아닐 이유가 없다(api-spec 6.1).
        recordRecovery(relRes.status >= 200 && relRes.status < 300);
      }
    }
  }

  // **이 시나리오의 보정 손잡이다**(7.2.2). 경합도 3단계는 1초로 고정이지만,
  // 여기서는 도착률을 올려야 락 대기가 실제로 깊어진다. 좌석 수를 줄이는 것으로는
  // 목표에 닿지 못한다는 것이 파일럿에서 확인됐다 — 행 락은 커밋 시점에 풀려서
  // 좌석이 논리적으로 잡혀 있는 동안에도 대부분 자유롭기 때문이다.
  //
  // 0으로 두지 않는다. 완전한 폐루프가 되면 도착률이 서버 처리량에 종속되어
  // 손잡이가 사라진다.
  sleep(SLEEP_MS / 1000);
}

export function handleSummary(data) {
  const row = extractRow(data, cfg);
  const out = {};
  out[resultPath(cfg)] = JSON.stringify({ config: cfg, row, raw: data }, null, 2);
  out.stdout = renderText(row);
  return out;
}
