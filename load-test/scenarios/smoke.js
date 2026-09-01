// smoke.js — k6 실행 환경 검증용 최소 시나리오.
//
// 목적: k6 컨테이너가 앱(nginx 경유 2대)에 도달하고, 결과를 남기는지 확인.
// 실제 측정 시나리오(경합도 저/고/극단, 좌석 홀드·확정)는 load-test 담당(최건)이
// 동시성 설계서 7장에 따라 별도로 작성한다. 이 파일은 환경이 도는지만 본다.

import http from 'k6/http';
import { check } from 'k6';

// 실행 환경에서 BASE_URL 주입(기본: 컨테이너 네트워크의 nginx)
const BASE_URL = __ENV.BASE_URL || 'http://nginx:80';

export const options = {
  // 가벼운 검증용. 실제 부하 프로파일은 최건이 설계서 7.2 경합도 3단계로 교체.
  vus: 5,
  duration: '10s',
  thresholds: {
    // 설계서 검수 기준: p95 3초 이내. 환경 검증 단계에서도 걸어둔다.
    http_req_duration: ['p(95)<3000'],
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  // 로드밸런싱 확인용 엔드포인트. app1/app2가 번갈아 응답해야 정상.
  const res = http.get(`${BASE_URL}/api/status`);
  check(res, {
    'status 200': (r) => r.status === 200,
    'has instance field': (r) => r.json('instance') !== undefined,
  });
}

// 결과를 results/ 에 JSON 요약으로 남긴다(설계서 7.4: 원본 결과 JSON 커밋).
export function handleSummary(data) {
  return {
    '/results/smoke-summary.json': JSON.stringify(data, null, 2),
    stdout: textSummary(data),
  };
}

// k6 기본 요약을 stdout에도 출력
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.2/index.js';
