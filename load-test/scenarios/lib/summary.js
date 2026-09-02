// summary.js — k6 실행 끝에 남길 요약을 만든다.
//
// jslib.k6.io의 textSummary를 쓰지 않고 직접 쓴다. 원격 import는 k6 컨테이너에
// 외부망을 요구하는데, 측정 실행이 CDN 가용성에 묶이면 안 되기 때문이다.

function metricValue(data, name, field) {
  const m = data.metrics[name];
  if (!m || !m.values) return null;
  const v = m.values[field];
  return v === undefined ? null : v;
}

function fmt(v, digits = 2) {
  if (v === null || v === undefined) return '—';
  return typeof v === 'number' ? v.toFixed(digits) : String(v);
}

/**
 * 7.6 기록 양식에서 k6가 채울 수 있는 칸만 뽑아낸다.
 * 초과 예약·재시도·제약 위반·풀 대기는 출처가 k6가 아니므로(7.1) 여기서 만들지
 * 않는다. 없는 값을 0으로 채우면 "쟀는데 0"과 "안 쟀음"이 구분되지 않는다.
 */
export function extractRow(data, cfg) {
  return {
    strategy: cfg.strategy,
    scenario: cfg.scenario,
    run: cfg.run,
    vus: cfg.vus,
    seats: cfg.seats,
    warmupSec: cfg.warmupSec,
    durationSec: cfg.durationSec,
    isFinalRun: cfg.isFinalRun === true,

    // k6가 정본인 값들(7.1)
    p95: metricValue(data, 'measured_req_duration', 'p(95)'),
    p99: metricValue(data, 'measured_req_duration', 'p(99)'),
    tps: metricValue(data, 'measured_req_duration', 'count') !== null
      ? metricValue(data, 'measured_req_duration', 'count') / cfg.durationSec
      : null,
    normalRejectionRate: metricValue(data, 'normal_rejection_rate', 'rate'),
    lockGiveupRate: metricValue(data, 'lock_giveup_rate', 'rate'),
    errorRate: metricValue(data, 'error_rate', 'rate'),
    phantomHoldRate: metricValue(data, 'phantom_hold_rate', 'rate'),

    // 7.2.2 지속 경합의 헤드라인 지표. 해제 요청이 섞이지 않은 홀드만의 값이다.
    // 다른 시나리오에서는 이 메트릭이 비어 있어 null이 된다.
    holdP95: metricValue(data, 'hold_req_duration', 'p(95)'),
    holdP99: metricValue(data, 'hold_req_duration', 'p(99)'),
    holdCount: metricValue(data, 'hold_req_duration', 'count'),
    recoveryRate: metricValue(data, 'recovery_rate', 'rate'),

    counts: {
      success: metricValue(data, 'bucket_success_total', 'count') || 0,
      normalRejection: metricValue(data, 'bucket_normal_rejection_total', 'count') || 0,
      lockGiveup: metricValue(data, 'bucket_lock_giveup_total', 'count') || 0,
      serverError: metricValue(data, 'bucket_server_error_total', 'count') || 0,
      stateRejection: metricValue(data, 'bucket_state_rejection_total', 'count') || 0,
      clientError: metricValue(data, 'bucket_client_error_total', 'count') || 0,
      unclassified: metricValue(data, 'bucket_unclassified_total', 'count') || 0,
      lockTimeout: metricValue(data, 'giveup_lock_timeout_total', 'count') || 0,
      retryExhausted: metricValue(data, 'giveup_retry_exhausted_total', 'count') || 0,
      phantomHold: metricValue(data, 'phantom_hold_total', 'count') || 0,
      recoveryFailed: metricValue(data, 'recovery_failed_total', 'count') || 0,
    },

    // k6가 재지 않는 값들. summarize.mjs가 DB·Actuator에서 채운다(7.1).
    notMeasuredByK6: ['초과 확정', '초과 홀드', '재시도 횟수', '제약 위반', '풀 대기'],
  };
}

export function renderText(row) {
  const L = [];
  L.push('');
  L.push('─'.repeat(62));
  L.push(` 전략 ${row.strategy} · 시나리오 ${row.scenario} (좌석 ${row.seats} / VU ${row.vus}) · ${row.run}회차`);
  L.push(` 워밍업 ${row.warmupSec}s 제외, 본 측정 ${row.durationSec}s` +
         (row.isFinalRun ? ' (최종 측정용)' : ' ← 개발 확인용. 7.6 기록 양식에 싣지 않는다'));
  L.push('─'.repeat(62));
  L.push(` p95            ${fmt(row.p95)} ms`);
  L.push(` p99            ${fmt(row.p99)} ms`);
  L.push(` TPS            ${fmt(row.tps)}`);
  L.push('');
  L.push(` 409율(정상거절) ${fmt(pct(row.normalRejectionRate))} %   ${row.counts.normalRejection}건`);
  L.push(` 락 포기율       ${fmt(pct(row.lockGiveupRate))} %   ${row.counts.lockGiveup}건` +
         `  (LOCK_TIMEOUT ${row.counts.lockTimeout} / RETRY_EXHAUSTED ${row.counts.retryExhausted})`);
  L.push(` 오류율(5xx)     ${fmt(pct(row.errorRate))} %   ${row.counts.serverError}건`);
  L.push(` 팬텀 홀드율     ${fmt(pct(row.phantomHoldRate))} %   ${row.counts.phantomHold}건` +
         '  (홀드 201 → 확정 실패, 7.6.3)');
  // 7.2.2 지속 경합에서만 값이 있다. 메트릭은 어느 시나리오에서나 등록되므로
  // 값의 유무가 아니라 **표본 수**로 판단한다 — 0 표본이면 p95가 0으로 나온다.
  if (row.holdCount > 0) {
    L.push('');
    L.push(' ── 지속 경합 (7.2.2) — 7.6 표에 넣지 않는다 ──');
    L.push(` 홀드 p95       ${fmt(row.holdP95)} ms   ← 헤드라인 지표 (해제 제외)`);
    L.push(` 홀드 p99       ${fmt(row.holdP99)} ms`);
    L.push(` 홀드 요청 수   ${row.holdCount}`);
    L.push(` 회수 성공률    ${fmt(pct(row.recoveryRate))} %   실패 ${row.counts.recoveryFailed}건` +
           (row.counts.recoveryFailed > 0
             ? '  ← 0이 아니다. 좌석이 순환에서 빠졌으므로 이 실행을 폐기한다'
             : ''));
  }

  L.push('');
  L.push(` 성공            ${row.counts.success}건`);
  L.push(` 상태 거절       ${row.counts.stateRejection}건  (어느 열에도 넣지 않음)`);
  L.push(` 클라이언트 오류 ${row.counts.clientError}건  (어느 열에도 넣지 않음)`);
  L.push(` 미분류          ${row.counts.unclassified}건` +
         (row.counts.unclassified > 0
           ? '  ← 0이 아니다. 이 실행의 숫자는 쓰지 않는다 (classify.js 갱신 필요)'
           : ''));
  L.push('');
  L.push(' k6가 재지 않는 값: ' + row.notMeasuredByK6.join(', '));
  L.push(' → 초과 예약은 sql/verify.sql, 나머지는 Actuator에서 가져온다 (7.1)');
  L.push('─'.repeat(62));
  L.push('');
  return L.join('\n');
}

function pct(rate) {
  return rate === null || rate === undefined ? null : rate * 100;
}
