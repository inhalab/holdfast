// api.js — docs/openapi.yaml 계약에 맞춘 호출 래퍼.
//
// **예약 API는 아직 구현되지 않았다.** 이 파일은 계약(openapi.yaml)만 보고 쓴
// 호출 코드이며, 서버가 생기면 그대로 돈다. 계약에 없는 엔드포인트나 필드를
// 임의로 만들지 않는다 — 여기서 지어내면 서버 구현이 그것을 따라가게 된다.

import http from 'k6/http';

/** 상태를 바꾸는 요청은 전부 Idempotency-Key가 필수다(openapi.yaml, 6절). */
function mutatingHeaders(userId) {
  return {
    'Content-Type': 'application/json',
    'Idempotency-Key': uuidv4(),
    'X-User-Id': String(userId),
  };
}

/**
 * UUID v4. jslib 원격 import를 쓰지 않는다 — k6 컨테이너가 외부망 없이도
 * 돌아야 하고, 측정 실행이 외부 CDN 가용성에 묶이면 안 되기 때문이다.
 * 멱등키 용도라 암호학적 강도는 필요 없다.
 */
export function uuidv4() {
  let s = '';
  for (let i = 0; i < 36; i++) {
    if (i === 8 || i === 13 || i === 18 || i === 23) { s += '-'; continue; }
    if (i === 14) { s += '4'; continue; }
    const r = (Math.random() * 16) | 0;
    s += (i === 19 ? (r & 0x3) | 0x8 : r).toString(16);
  }
  return s;
}

/** GET /api/sessions/{sessionId}/seats — 좌석맵 전체 조회 */
export function getSeatMap(base, sessionId) {
  return http.get(`${base}/api/sessions/${sessionId}/seats`, {
    tags: { operation: 'seat_map' },
  });
}

/** GET /api/sessions/{sessionId}/seats/status — 3초 폴링용 경량 스냅샷 */
export function getSeatStatus(base, sessionId, etag) {
  const headers = etag ? { 'If-None-Match': etag } : {};
  return http.get(`${base}/api/sessions/${sessionId}/seats/status`, {
    headers,
    tags: { operation: 'seat_status' },
  });
}

/**
 * POST /api/holds — 좌석 선점.
 * 전부 아니면 전무이므로 부분 성공 응답을 기대하지 않는다(api-spec 4절).
 */
export function createHold(base, userId, sessionId, seatIds) {
  return http.post(
    `${base}/api/holds`,
    JSON.stringify({ sessionId, seatIds }),
    { headers: mutatingHeaders(userId), tags: { operation: 'hold' } },
  );
}

/** POST /api/reservations — 예약 확정. CS-2, 가장 경합이 심한 구간 */
export function confirmReservation(base, userId, holdId) {
  return http.post(
    `${base}/api/reservations`,
    JSON.stringify({ holdId }),
    { headers: mutatingHeaders(userId), tags: { operation: 'confirm' } },
  );
}

/** DELETE /api/holds/{holdId} — 선점 해제 */
export function releaseHold(base, userId, holdId) {
  return http.del(`${base}/api/holds/${holdId}`, null, {
    headers: mutatingHeaders(userId),
    tags: { operation: 'release' },
  });
}

/** POST /api/reservations/{id}/cancel — 예약 취소 */
export function cancelReservation(base, userId, reservationId) {
  return http.post(`${base}/api/reservations/${reservationId}/cancel`, null, {
    headers: mutatingHeaders(userId),
    tags: { operation: 'cancel' },
  });
}
