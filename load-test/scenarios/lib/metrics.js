// metrics.js — 7.1 지표 중 k6가 정본인 것들을 커스텀 메트릭으로 집계한다.
//
// 근거: docs/concurrency-spec.md 7.1(지표·출처), 7.4(워밍업 제외), 7.6/7.6.1(기록 양식)
//
// ## k6가 재지 않는 지표
//
// 7.1은 지표마다 출처를 따로 못박아 두었다. k6가 재는 것만 여기서 다룬다.
//
//   초과 예약 건수   → DB 검증 쿼리   (sql/verify.sql)      ← k6 아님
//   낙관적 재시도 횟수 → 앱 커스텀 메트릭 (Actuator)          ← k6 아님
//   제약 위반 횟수   → 앱 커스텀 메트릭 (Actuator)          ← k6 아님
//   커넥션 풀 대기   → hikaricp.connections.pending/.acquire ← k6 아님
//
// **재시도 횟수를 k6에서 세지 않는 것은 실수가 아니다.** 재시도는 앱 내부에서
// 일어나 요청 하나 안에 묻히므로 클라이언트에서는 관측되지 않는다. 락 포기율과
// 단위가 다르며(횟수 vs 요청 비율) 서로 환산하지 않는다(7.6.1).

import { Counter, Rate, Trend } from 'k6/metrics';
import exec from 'k6/execution';
import { classify, BUCKET } from './classify.js';

// --- 7.6 기록 양식에 직접 들어가는 메트릭 ------------------------------------

// p95·p99의 정본(7.1). 내장 http_req_duration은 워밍업까지 포함하므로 쓰지 않고,
// 워밍업을 제외한 요청만 여기에 담는다.
export const measuredDuration = new Trend('measured_req_duration', true);

// 세 버킷의 비율. 분모가 같아야 서로 비교되므로 측정 구간의 모든 요청에 대해
// add(true/false)를 호출한다.
export const normalRejectionRate = new Rate('normal_rejection_rate'); // → 409율
export const lockGiveupRate = new Rate('lock_giveup_rate');           // → 락 포기율
export const errorRate = new Rate('error_rate');                      // → 오류율

// --- 절대 건수 (해석·검산용) -------------------------------------------------

export const successTotal = new Counter('bucket_success_total');
export const normalRejectionTotal = new Counter('bucket_normal_rejection_total');
export const lockGiveupTotal = new Counter('bucket_lock_giveup_total');
export const serverErrorTotal = new Counter('bucket_server_error_total');

// 409율·오류율 어느 쪽에도 들어가지 않는 것들. 0이 아니면 시나리오를 고친다.
export const stateRejectionTotal = new Counter('bucket_state_rejection_total');
export const clientErrorTotal = new Counter('bucket_client_error_total');

// **0이 아니면 그 실행의 숫자를 쓰지 않는다.** 계약에 새 오류 코드가 생겼는데
// classify.js가 따라가지 못했다는 신호다(classify.js 주석 참조).
export const unclassifiedTotal = new Counter('bucket_unclassified_total');

// 락 포기의 원인을 코드로 구분해 센다. 두 코드를 나눈 이유가 여기서 쓰인다 —
// 시간 기반 포기(LOCK_TIMEOUT)와 횟수 기반 포기(RETRY_EXHAUSTED)를 보고서에서
// 전략 열로 추론하지 않고 숫자로 구분하기 위해서다(api-spec 3.2).
export const lockTimeoutTotal = new Counter('giveup_lock_timeout_total');
export const retryExhaustedTotal = new Counter('giveup_retry_exhausted_total');

// --- 팬텀 홀드 (7.6.3) -------------------------------------------------------

/**
 * 홀드에 201을 받았으나 확정에 실패한 요청의 비율.
 *
 * **none의 사용자 대면 결함이다.** "좌석을 잡았습니다"라는 응답을 받은 뒤
 * 결제 화면에서 좌석을 잃는다. 초과 홀드(V-2)가 DB 상태로 드러나는 실패라면
 * 이 값은 그 실패가 사용자에게 어떻게 보이는가를 잰다.
 *
 * 분모는 "홀드에 성공한 요청"이다 — 홀드 자체가 거절된 요청은 애초에 약속을
 * 받지 않았으므로 여기 들어가지 않는다. 홀드 성공 건마다 add(true/false)를
 * 정확히 한 번 호출한다.
 */
export const phantomHoldRate = new Rate('phantom_hold_rate');
export const phantomHoldTotal = new Counter('phantom_hold_total');

/**
 * 홀드에 성공한 요청 하나의 결말을 기록한다.
 *
 * @param confirmed 확정까지 성공했으면 true
 */
export function recordHoldOutcome(confirmed, tags = {}) {
  phantomHoldRate.add(!confirmed, tags);
  if (!confirmed) {
    phantomHoldTotal.add(1, tags);
  }
}

// --- 지속 경합 시나리오 (7.2.2) --------------------------------------------

/**
 * <b>홀드 요청만의 응답시간.</b> 7.2.2의 헤드라인 지표다.
 *
 * `measured_req_duration`에는 해제 요청이 함께 들어간다. 해제는 락을 두고
 * 겨루는 요청이 아니라 반납이라, 섞이면 "락을 기다린 시간"이 희석된다.
 * 7.2.2가 `operation=hold` 태그만 쓰라고 정한 이유이며, k6 요약은 태그별로
 * 쪼개 주지 않으므로 메트릭을 따로 둔다.
 */
export const holdDuration = new Trend('hold_req_duration', true);

/**
 * 회수 성공률. 홀드에 성공한 요청 중 해제까지 성공한 비율이다.
 *
 * <b>이 값이 1에서 떨어지면 그 실행을 폐기한다(7.2.2).</b> 홀드해 놓고 해제하지
 * 못한 좌석은 순환에서 빠지는데, 청소 스케줄러가 없으므로 `none`에서는 영구히
 * 빠진다. 나머지 네 전략은 TTL 만료 뒤 다음 요청이 정리하지만 그때까지 그
 * 좌석은 죽어 있다. 좌석이 3석뿐이라 한 건만 새도 재고의 3분의 1이 사라진다.
 */
export const recoveryRate = new Rate('recovery_rate');
export const recoveryFailedTotal = new Counter('recovery_failed_total');

/** 홀드 성공 한 건의 회수 결과를 기록한다. */
export function recordRecovery(released, tags = {}) {
  recoveryRate.add(released, tags);
  if (!released) {
    recoveryFailedTotal.add(1, tags);
  }
}

// --- 워밍업 경계 -------------------------------------------------------------

/**
 * 지금이 워밍업 구간인지 판단한다.
 *
 * 7.4는 워밍업 30초를 집계에서 제외하라고 정했다. JIT 컴파일 전 첫 수천 요청이
 * 느려서, 버리지 않으면 첫 번째로 측정한 전략이 무조건 손해를 본다.
 *
 * 벽시계(Date.now())가 아니라 k6의 테스트 경과 시간을 쓴다. VU마다 시작 시각이
 * 다르면 경계가 흔들리기 때문이다.
 */
export function inWarmup(warmupMs) {
  return exec.instance.currentTestRunDuration < warmupMs;
}

/**
 * 응답 하나를 집계한다. 워밍업 구간이면 아무것도 기록하지 않는다.
 *
 * @param {object} res  k6 http 응답
 * @param {number} warmupMs 워밍업 길이(ms)
 * @param {object} tags 추가 태그(operation 등)
 * @returns {{bucket: string, code: string, measured: boolean}}
 */
export function record(res, warmupMs, tags = {}) {
  const { bucket, code } = classify(res);

  if (inWarmup(warmupMs)) {
    return { bucket, code, measured: false };
  }

  const t = Object.assign({ bucket, code }, tags);
  measuredDuration.add(res.timings.duration, t);

  // 세 비율의 분모를 같게 유지한다. 한 요청은 셋 모두에 정확히 한 번씩 들어간다.
  normalRejectionRate.add(bucket === BUCKET.NORMAL_REJECTION, t);
  lockGiveupRate.add(bucket === BUCKET.LOCK_GIVEUP, t);
  errorRate.add(bucket === BUCKET.SERVER_ERROR, t);

  switch (bucket) {
    case BUCKET.SUCCESS:
      successTotal.add(1, t);
      break;
    case BUCKET.NORMAL_REJECTION:
      normalRejectionTotal.add(1, t);
      break;
    case BUCKET.LOCK_GIVEUP:
      lockGiveupTotal.add(1, t);
      if (code === 'LOCK_TIMEOUT') lockTimeoutTotal.add(1, t);
      if (code === 'RETRY_EXHAUSTED') retryExhaustedTotal.add(1, t);
      break;
    case BUCKET.SERVER_ERROR:
      serverErrorTotal.add(1, t);
      break;
    case BUCKET.STATE_REJECTION:
      stateRejectionTotal.add(1, t);
      break;
    case BUCKET.CLIENT_ERROR:
      clientErrorTotal.add(1, t);
      break;
    default:
      unclassifiedTotal.add(1, t);
  }

  return { bucket, code, measured: true };
}
