// classify.js — 응답을 집계 버킷으로 분류한다. 이 파일이 측정 해석의 핵심이다.
//
// 근거: docs/api-spec.md 3.1 분류표, 3.2 집계 규칙 / docs/concurrency-spec.md 7.1, 7.6.1
//
// 이 프로젝트의 산출물은 락 전략 비교 데이터이고, 그 비교가 성립하려면
// **정상 거절과 오류와 락 포기가 절대 섞이지 않아야 한다.** 급하게 만들면
// 가장 먼저 흐려지는 것이 이 분리라서, API 구현보다 먼저 여기를 고정한다.

// --- api-spec.md 3.1의 분류를 그대로 옮긴 표 ---------------------------------

// 정상 거절 5종. 시스템이 제 역할을 한 결과이며 실패가 아니다(7.1).
// 7.6 기록 양식의 "409율" 열에 들어가는 것은 오직 이 다섯 개다.
export const NORMAL_REJECTION = [
  'SEAT_ALREADY_SOLD',
  'SEAT_HELD_BY_OTHER',
  'HOLD_EXPIRED',
  'QUOTA_EXCEEDED',
  'RESERVATION_NOT_OPEN',
];

// 락 포기 2종. 409로 오지만 409율이 아니라 "락 포기율" 열로 간다(7.6.1).
// 좌석이 팔려서 거절된 게 아니라, 남아 있었을 수도 있는데 포기한 것이다.
//   LOCK_TIMEOUT    — 시간 기반 포기 (pessimistic: lock_timeout 1초, redis: waitTime 1초)
//   RETRY_EXHAUSTED — 횟수 기반 포기 (optimistic: 재시도 상한 3회 소진)
export const LOCK_GIVEUP = [
  'LOCK_TIMEOUT',
  'RETRY_EXHAUSTED',
];

// 상태 거절 4종. 409지만 경합이 아니다. 부하 시나리오에서 나오면
// 시나리오가 잘못 짜인 것이므로 별도로 세어 두고 신호로 쓴다(api-spec 3.2).
export const STATE_REJECTION = [
  'HOLD_RELEASED',
  'HOLD_ALREADY_CONFIRMED',
  'RESERVATION_ALREADY_CANCELLED',
  'RESERVATION_NOT_CANCELLABLE',
];

// 클라이언트 오류. 이것도 409율·오류율 어느 쪽에도 넣지 않는다(api-spec 3.2).
export const CLIENT_ERROR = [
  'IDEMPOTENCY_KEY_REUSED',
  'IDEMPOTENCY_KEY_IN_PROGRESS',
  'VALIDATION_FAILED',
  'SESSION_NOT_FOUND',
  'SEAT_NOT_IN_SESSION',
  'HOLD_NOT_FOUND',
  'RESERVATION_NOT_FOUND',
];

// 서버 결함. 7.6의 "오류율" 열은 이것과 타임아웃만 센다.
export const SERVER_ERROR = ['INTERNAL_ERROR'];

// --- 버킷 이름 ---------------------------------------------------------------

export const BUCKET = {
  SUCCESS: 'success',
  NORMAL_REJECTION: 'normal_rejection', // → 7.6 "409율"
  LOCK_GIVEUP: 'lock_giveup',           // → 7.6 "락 포기율"
  SERVER_ERROR: 'server_error',         // → 7.6 "오류율"
  STATE_REJECTION: 'state_rejection',   // 어느 열에도 안 들어감
  CLIENT_ERROR: 'client_error',         // 어느 열에도 안 들어감
  UNCLASSIFIED: 'unclassified',         // 0이어야 한다 — 아래 설명
};

const codeBucket = new Map();
for (const c of NORMAL_REJECTION) codeBucket.set(c, BUCKET.NORMAL_REJECTION);
for (const c of LOCK_GIVEUP) codeBucket.set(c, BUCKET.LOCK_GIVEUP);
for (const c of STATE_REJECTION) codeBucket.set(c, BUCKET.STATE_REJECTION);
for (const c of CLIENT_ERROR) codeBucket.set(c, BUCKET.CLIENT_ERROR);
for (const c of SERVER_ERROR) codeBucket.set(c, BUCKET.SERVER_ERROR);

/**
 * 응답 하나를 버킷과 코드로 분류한다.
 *
 * **모르는 코드는 어느 버킷에도 넣지 않고 UNCLASSIFIED로 센다.** 새 오류 코드가
 * 생겼는데 이 파일을 갱신하지 않으면 그 코드가 조용히 정상 거절이나 오류에
 * 섞여 비교표를 오염시킨다. 미분류 카운터가 0이 아니면 집계가 계약을 따라가지
 * 못했다는 신호이며, 그 실행의 숫자는 쓰지 않는다.
 *
 * @param {object} res k6 http 응답
 * @returns {{bucket: string, code: string}}
 */
export function classify(res) {
  // status 0 = 연결 실패·타임아웃. 7.1의 "오류율(5xx·타임아웃)"에 포함된다.
  if (res.status === 0) {
    return { bucket: BUCKET.SERVER_ERROR, code: 'TIMEOUT_OR_NETWORK' };
  }
  if (res.status >= 500) {
    return { bucket: BUCKET.SERVER_ERROR, code: readCode(res) || `HTTP_${res.status}` };
  }
  if (res.status >= 200 && res.status < 300) {
    return { bucket: BUCKET.SUCCESS, code: 'OK' };
  }

  // 4xx는 반드시 problem+json의 code로 판단한다. HTTP 상태만으로는
  // 409 안에 섞인 네 가지 성격을 구분할 수 없다(api-spec 3절).
  const code = readCode(res);
  if (!code) {
    return { bucket: BUCKET.UNCLASSIFIED, code: `NO_CODE_HTTP_${res.status}` };
  }
  const bucket = codeBucket.get(code);
  if (!bucket) {
    return { bucket: BUCKET.UNCLASSIFIED, code };
  }
  return { bucket, code };
}

function readCode(res) {
  try {
    const body = res.json();
    return body && typeof body.code === 'string' ? body.code : null;
  } catch (e) {
    return null;
  }
}
