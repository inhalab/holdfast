// quota.js — 할당량 경합 시나리오 (설계서 7.2.3). **정합성 축 전용이다.**
//
// ## 왜 따로 만드나
//
// 8절이 "CS-6은 부하 측정의 V-3 검증 쿼리로 확인한다"고 적어 뒀는데, 경합도
// 3단계와 지속 경합이 **전부 그 구역을 비껴간다.** userPool을 VU 수 이상으로
// 두어 동시에 도는 반복이 전부 다른 사용자가 되게 했기 때문이고, 그것은 좌석
// 경합에 할당량 직렬화가 섞이지 않게 하려는 의도였다(7.3). 성능 측정에서는
// 옳은 판단이다.
//
// 그런데 **CS-6은 같은 사용자의 요청 둘이 서로 다른 좌석을 동시에 노릴 때만
// 밟힌다**(1.1). 좌석 행이 전부 다르므로 다섯 전략 중 어느 것도 막지 못하고,
// 막는 것은 user_session_quota 행 하나뿐이다. **검증 방법은 있는데 밟는 경로가
// 없었다**(#191).
//
// ## p95·TPS를 재지 않는다
//
// **요청 종류가 둘이면 그 값이 "홀드+확정의 성능"이 아니다**(api-spec 8.1).
// M3의 60회를 전부 그 조건에서 쟀으므로 여기서 성능을 읽으면 그 60회가 비교
// 대상을 잃는다. 7.7.1의 "성능으로는 전략을 구분할 수 없다"는 성능 축의
// 결론이고 이 시나리오는 그 축에 서지 않는다.
//
// **판정은 verify-quota.sql 이다.** Q-1(활성 홀드 상한 초과) · Q-2(카운터 정합)
// · Q-3(V-3). 이 파일이 내는 숫자는 **경합 구역을 실제로 밟았는지**를 보이는
// 반대 증거일 뿐이다.
//
// 실행: load-test/scripts/run-quota.sh [전략]

import exec from 'k6/execution';
import { loadConfig, stagesFor, resultPath, SUMMARY_TREND_STATS } from './lib/config.js';
import { record, recordRecovery } from './lib/metrics.js';
import { createHold, releaseHold } from './lib/api.js';
import { extractRow, renderText } from './lib/summary.js';

const cfg = loadConfig();

if (cfg.scenario !== 'quota') {
  throw new Error(`이 시나리오는 SCENARIO=quota로만 돈다: ${cfg.scenario}`);
}

export const options = {
  stages: stagesFor(cfg),
  summaryTrendStats: SUMMARY_TREND_STATS,
  thresholds: {
    // 5xx는 여기서도 측정값이 아니라 결함이다(api-spec 3.3).
    'error_rate': ['rate<0.001'],
    'bucket_unclassified_total': ['count==0'],

    // **해제 누수는 실행 폐기 사유다.** 해제가 실패하면 그 사용자의 할당량이
    // TTL까지 묶여 경합이 식는다 — 판정이 아니라 조건이 무너진 것이다.
    'recovery_failed_total': ['count==0'],

    // **p95 임계값을 걸지 않는다.** 이 시나리오는 성능 축이 아니다(7.2.3).
  },
  tags: { strategy: cfg.strategy, scenario: 'quota', run: String(cfg.run) },
};

export function setup() {
  console.log(
    `[setup] 할당량 경합(CS-6) — 전략=${cfg.strategy} 좌석=${cfg.seats} VU=${cfg.vus} ` +
    `사용자풀=${cfg.userPool} 워밍업=${cfg.warmupSec}s 본측정=${cfg.durationSec}s`
  );
  console.log(`[setup] VU ${cfg.vus}개가 사용자 ${cfg.userPool}명을 나눠 쓴다 ` +
              `— 사용자당 약 ${Math.round(cfg.vus / cfg.userPool)}개가 같은 할당량 행을 다툰다`);
  console.log('[setup] 한 반복 = 홀드 → (성공 시) 해제. 확정하지 않는다');
  console.log('[setup] **판정은 이 요약이 아니라 verify-quota.sql 이다** (7.2.3)');
  return {};
}

export default function () {
  // **VU 번호로 사용자를 고른다. 이 한 줄이 이 시나리오의 전부다.**
  //
  // 경합도 3단계는 iterationInTest로 고르고 풀을 VU 수 이상으로 둔다 — 동시에
  // 도는 반복이 전부 다른 사용자가 되어 **CS-6이 밟히지 않는다**(7.3). 여기서는
  // 반대로, 풀을 VU보다 훨씬 작게 두고 VU에 고정해 **여러 VU가 같은 할당량 행에
  // 계속 머물게** 한다.
  //
  // iterationInTest를 쓰면 같은 사용자를 고른 두 반복이 시간상 떨어질 수 있다.
  // VU 고정이면 그 VU들이 실행 내내 같은 행을 다툰다.
  const userId = (exec.vu.idInTest % cfg.userPool) + 1;

  // **좌석은 매번 다르게 고른다.** 같은 좌석을 노리면 좌석 행에서 먼저
  // 직렬화되어 할당량 구역까지 가지 못한다 — CS-6은 "서로 다른 좌석"일 때만
  // 좌석 락이 못 막는 축이다(1.1).
  const seatId = cfg.seatIdBase + Math.floor(Math.random() * cfg.seats);

  const holdRes = createHold(cfg.baseUrl, userId, cfg.sessionId, [seatId]);
  const held = record(holdRes, cfg.warmupMs, { operation: 'hold' });

  if (holdRes.status === 201) {
    let holdId = null;
    try { holdId = holdRes.json('holdId'); } catch (e) { holdId = null; }

    if (holdId === null) {
      if (held.measured) recordRecovery(false, { reason: 'no_hold_id' });
    } else {
      // **반드시 해제한다.** 해제하지 않으면 사용자 20명이 각자 4매를 채운 뒤
      // 모든 요청이 QUOTA_EXCEEDED가 되어 **경합이 멈춘다.** 해제를 섞어야
      // 증가 경로와 감소 경로가 함께 다툰다 — 감소 쪽은 held_count를
      // Math.max(0, ...)로 깎으므로 어긋나도 음수로 드러나지 않는다.
      const relRes = releaseHold(cfg.baseUrl, userId, holdId);
      record(relRes, cfg.warmupMs, { operation: 'release' });
      if (held.measured) {
        recordRecovery(relRes.status >= 200 && relRes.status < 300);
      }
    }
  }

  // **sleep을 넣지 않는다.** 도착률을 낮추면 같은 할당량 행을 동시에 치는 확률이
  // 떨어진다. 이 시나리오가 재는 것은 응답시간이 아니라 "겹쳤을 때 깨지는가"라,
  // 7.2가 sleep(1)로 도착률을 고정한 이유가 여기에는 적용되지 않는다.
}

export function handleSummary(data) {
  const row = extractRow(data, cfg);
  const out = {};
  out[resultPath(cfg)] = JSON.stringify({ config: cfg, row, raw: data }, null, 2);
  out.stdout = renderText(row) +
    '\n**판정은 이 표가 아니다.** verify-quota.sql 의 Q-1·Q-2·Q-3을 본다 (7.2.3).\n' +
    '여기의 409율은 경합 구역을 실제로 밟았는지를 보이는 반대 증거다 — 0이면 시나리오 실패.\n';
  return out;
}
