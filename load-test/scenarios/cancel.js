// cancel.js — 취소·재선점 경합 시나리오 (설계서 7.2.4). **정합성 축 전용이다.**
//
// ## 왜 따로 만드나
//
// 1절이 CS-4를 "취소 시 좌석 반환 / 판매된 행 / 이중 반환"으로 세어 두었는데,
// **그 구역을 밟는 경로가 하나도 없었다**(#200).
//
//   - 검증 쿼리 — verify.sql은 V-1~V-5뿐이고 CS-4 전용이 없다
//   - 부하 경로 — lib/api.js에 cancelReservation()이 있는데 **아무도 부르지 않는다**
//   - 동시성 테스트 — MinimumScopeFlowTest의 **단일 스레드 재취소**뿐이다
//
// #191이 CS-6에서 진단한 것과 같은 형태다 — "검증 방법은 있는데 밟는 경로가 없다".
//
// ## 무엇을 밟나 — 확정된 좌석이 돌아오는 순간
//
// 한 반복은 **홀드 → 확정 → 취소 둘을 동시에**다. 확정까지 가야 좌석이 SOLD가
// 되고, 그때부터가 CS-4 구역이다. 홀드만 하고 해제하면 7.2.2·7.2.3이 이미 도는
// 경로이고 CS-4가 아니다.
//
// **취소를 둘 쏘는 것이 이 시나리오의 전부다.** ReservationService#cancel은
// reservation을 읽은 뒤 할당량 행을 FOR UPDATE로 잡는데, READ COMMITTED에서
// 뒤엣 트랜잭션은 락을 기다리는 동안 갱신되지 않은 스냅샷을 쥔다. 둘이 겹치지
// 않으면 그 창이 열리지 않는다.
//
// **재선점은 다른 VU가 맡는다.** 좌석 풀이 작아 취소로 돌아온 좌석을 곧바로
// 다른 VU가 홀드한다 — 이슈 제목의 "재선점"이 그것이고, 별도 코드가 필요 없다.
//
// ## 멱등키를 둘로 다르게 쓴다
//
// **같은 키면 재는 대상에 닿지 못한다.** IdempotencyService.execute()가 먼저
// 돌아 두 번째는 IDEMPOTENCY_KEY_IN_PROGRESS로 거절되거나 저장된 응답을
// 재생한다 — ReservationService#cancel이 한 번만 도는 것이다.
//
// **같은 키의 동시 재시도는 다른 축이다**(멱등 계층). 이 시나리오에서 다루지
// 않는다 — 여기서 재는 것은 취소 트랜잭션 둘이 겹쳤을 때다.
//
// ## p95·TPS를 재지 않는다
//
// **요청 종류가 넷이다**(홀드·확정·취소×2). 그 값은 "홀드+확정의 성능"이
// 아니므로(api-spec 8.1) 읽으면 M3의 60회가 비교 대상을 잃는다. 7.2.3이 CS-6에
// 대해 정한 것과 같다.
//
// **판정은 verify-cancel.sql 이다.** C-1(동시 보유) · C-2(확정인데 재고가 빈
// 좌석) · C-3(할당량 드리프트) · C-4(V-1·V-4). 이 파일이 내는 숫자는 **경합
// 구역을 실제로 밟았는지**를 보이는 반대 증거일 뿐이다.
//
// 실행: load-test/scripts/run-cancel.sh [전략]

import http from 'k6/http';
import { Counter } from 'k6/metrics';
import exec from 'k6/execution';
import { loadConfig, stagesFor, resultPath, SUMMARY_TREND_STATS } from './lib/config.js';
import { record } from './lib/metrics.js';
import { createHold, confirmReservation, releaseHold, uuidv4 } from './lib/api.js';
import { extractRow, renderText } from './lib/summary.js';

const cfg = loadConfig();

if (cfg.scenario !== 'cancel') {
  throw new Error(`이 시나리오는 SCENARIO=cancel로만 돈다: ${cfg.scenario}`);
}

// **경합 구역을 밟았는지를 보이는 반대 증거.** 7.2.3이 QUOTA_EXCEEDED 거절 수로
// 같은 일을 했다 — 0이면 판정이 아니라 시나리오 실패다.
const cancelPairsFired = new Counter('cancel_pairs_fired');   // 동시 취소 둘을 쏜 횟수
const cancelBothOk = new Counter('cancel_both_ok');           // 둘 다 2xx로 돌아온 횟수
const confirmed = new Counter('cancel_confirmed_total');      // 확정까지 간 반복 수

export const options = {
  stages: stagesFor(cfg),
  summaryTrendStats: SUMMARY_TREND_STATS,
  thresholds: {
    // 5xx는 여기서도 측정값이 아니라 결함이다(api-spec 3.3).
    'error_rate': ['rate<0.001'],
    'bucket_unclassified_total': ['count==0'],

    // **p95 임계값을 걸지 않는다.** 이 시나리오는 성능 축이 아니다(7.2.4).
  },
  tags: { strategy: cfg.strategy, scenario: 'cancel', run: String(cfg.run) },
};

export function setup() {
  console.log(
    `[setup] 취소·재선점(CS-4) — 전략=${cfg.strategy} 좌석=${cfg.seats} VU=${cfg.vus} ` +
    `사용자풀=${cfg.userPool} 워밍업=${cfg.warmupSec}s 본측정=${cfg.durationSec}s`
  );
  console.log('[setup] 한 반복 = 홀드 → 확정 → **취소 둘을 동시에**. 멱등키는 둘이 다르다');
  console.log(`[setup] 좌석 ${cfg.seats}석을 VU ${cfg.vus}개가 다툰다 ` +
              `— 취소로 돌아온 좌석을 다른 VU가 곧바로 잡는 것이 재선점이다`);
  console.log('[setup] **판정은 이 요약이 아니라 verify-cancel.sql 이다** (7.2.4)');
  return {};
}

export default function () {
  // 사용자는 VU마다 다르게 둔다 — 풀이 VU 이상이라 같은 할당량 행을 두 VU가
  // 다투지 않는다(7.3). **CS-6을 여기 섞지 않는다.** 같은 예약을 취소하는 둘은
  // 어차피 같은 사용자이고, 그것은 할당량 경합이 아니라 취소 경합이다.
  const userId = (exec.scenario.iterationInTest % cfg.userPool) + 1;
  const seatId = cfg.seatIdBase + Math.floor(Math.random() * cfg.seats);

  const holdRes = createHold(cfg.baseUrl, userId, cfg.sessionId, [seatId]);
  record(holdRes, cfg.warmupMs, { operation: 'hold' });

  // **홀드 실패는 정상이다.** 좌석 풀이 작아 대부분이 409로 거절된다 — 그것이
  // 재선점 경합의 모습이고, 이 시나리오는 그 거절을 세지 않는다.
  if (holdRes.status !== 201) return;

  let holdId = null;
  try { holdId = holdRes.json('holdId'); } catch (e) { holdId = null; }
  if (holdId === null) return;

  const confRes = confirmReservation(cfg.baseUrl, userId, holdId);
  const conf = record(confRes, cfg.warmupMs, { operation: 'confirm' });

  if (confRes.status !== 201) {
    // 확정에 실패하면 홀드를 돌려준다 — 안 돌려주면 그 좌석이 TTL까지 묶여
    // 재선점 경합이 식는다. 7.2.2가 회수를 시나리오에 넣은 것과 같은 이유다.
    releaseHold(cfg.baseUrl, userId, holdId);
    return;
  }
  if (conf.measured) confirmed.add(1);

  let reservationId = null;
  try { reservationId = confRes.json('reservationId'); } catch (e) { reservationId = null; }
  if (reservationId === null) return;

  // **취소 둘을 한 번에 쏜다.** http.batch가 두 요청을 병렬로 보낸다 — 순차로
  // 두 번 부르면 앞엣것이 커밋된 뒤 뒤엣것이 시작해 CS-4 구역이 열리지 않는다.
  //
  // 멱등키가 서로 다르다 — 같으면 IdempotencyService가 먼저 답해 취소
  // 트랜잭션이 한 번만 돈다(머리말).
  const url = `${cfg.baseUrl}/api/reservations/${reservationId}/cancel`;
  const headers = () => ({
    'Content-Type': 'application/json',
    'Idempotency-Key': uuidv4(),
    'X-User-Id': String(userId),
  });
  const responses = http.batch([
    { method: 'POST', url, body: null, params: { headers: headers(), tags: { operation: 'cancel' } } },
    { method: 'POST', url, body: null, params: { headers: headers(), tags: { operation: 'cancel' } } },
  ]);

  let ok = 0;
  for (const res of responses) {
    const r = record(res, cfg.warmupMs, { operation: 'cancel' });
    if (r.measured && res.status >= 200 && res.status < 300) ok += 1;
  }
  if (conf.measured) {
    cancelPairsFired.add(1);
    if (ok === 2) cancelBothOk.add(1);
  }

  // **sleep을 넣지 않는다.** 도착률을 낮추면 취소가 겹칠 확률과 재선점 경합이
  // 함께 떨어진다. 이 시나리오가 재는 것은 응답시간이 아니라 "겹쳤을 때
  // 깨지는가"라, 7.2가 sleep(1)로 도착률을 고정한 이유가 적용되지 않는다.
}

export function handleSummary(data) {
  const row = extractRow(data, cfg);
  const out = {};
  out[resultPath(cfg)] = JSON.stringify({ config: cfg, row, raw: data }, null, 2);

  const n = (name) => (data.metrics[name] && data.metrics[name].values.count) || 0;
  out.stdout = renderText(row) +
    `\n확정까지 간 반복 ${n('cancel_confirmed_total')}회 · ` +
    `동시 취소 발사 ${n('cancel_pairs_fired')}쌍 · 둘 다 2xx ${n('cancel_both_ok')}쌍\n` +
    '**판정은 이 표가 아니다.** verify-cancel.sql 의 C-1~C-4를 본다 (7.2.4).\n' +
    '위 발사 수와 재선점 횟수는 경합 구역을 실제로 밟았는지를 보이는 반대 증거다 — 0이면 시나리오 실패.\n';
  return out;
}
