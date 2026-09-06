// pages.js — PER-002 화면별 응답시간 측정 (이슈 #105).
//
// 근거: docs/rfp-scope.md 2.3(PER-002 원문과 적용 화면 목록),
//       docs/concurrency-spec.md 7.3(고정 변수)·7.4(측정 프로토콜)
//       docs/results/per-002-page-timing.md(이 측정의 프로토콜과 판정)
//
// ## reservation.js와 무엇이 다른가
//
// **재는 대상이 다르다.** 저쪽은 홀드·확정 API의 p95이고 사실상 409 거절의
// p95다(concurrency-spec 7.6.1). 이쪽은 **서버렌더 화면 하나가 브라우저에
// 도착하기까지**를 잰다. 두 값을 나란히 놓지 않는다.
//
// **집계 경로도 다르다.** classify.js를 쓰지 않는다 — 그 표는 problem+json의
// `code`로 409 안의 네 성격을 가르는 장치이고, 화면은 HTML을 돌려주며 정상
// 거절이라는 개념 자체가 없다. 화면에서 2xx가 아닌 것은 전부 **측정이 잘못
// 놓였거나 앱이 깨진 것**이므로 한 버킷에 모아 세고, 0이 아니면 그 실행을
// 쓰지 않는다. 같은 규율을 다른 계약에 맞게 다시 적용하는 것이다.
//
// ## 무엇을 응답시간으로 보는가
//
// PER-002 원문은 "완전 표시"라 브라우저의 파싱·페인트까지를 말하지만, k6는
// 브라우저가 아니다. 여기서 재는 것은
//
//   문서 요청 시간 + 그 문서가 부르는 정적 자원 전부를 병렬로 받는 데 걸린 시간
//
// 이고, **브라우저 안에서 일어나는 일은 재지 않는다.** 그 간극을 어떻게 다루는지는
// 결과 문서의 "무엇을 재는가"에 적었다 — 감추지 않고 여백을 보여 준다.
//
// 정적 자원을 회차마다 다시 받는 것은 **첫 방문을 재는 것**이다. 실제 브라우저는
// 두 번째부터 캐시를 쓰므로 이쪽이 항상 불리하고, 검수 판정에서 불리한 쪽으로
// 치우치는 것은 안전한 방향이다.

import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';
import { SUMMARY_TREND_STATS } from './lib/config.js';

const BASE_URL = __ENV.BASE_URL || 'http://nginx:80';

// 7.4와 같은 워밍업 30초. 화면 쪽에는 JIT 말고 이유가 하나 더 있다 —
// **Thymeleaf 템플릿이 첫 요청에서 파싱·캐시된다.** 워밍업 없이 재면 화면마다
// 첫 회차에 일회성 컴파일 비용이 얹히고, 그것은 운영 중 화면의 응답시간이 아니다.
const WARMUP_SEC = intEnv('WARMUP_SEC', 30);
const WARMUP_MS = WARMUP_SEC * 1000;
const DURATION_SEC = intEnv('DURATION_SEC', 60);

const SESSION_ID = intEnv('SESSION_ID', 1);
const PROGRAM_ID = intEnv('PROGRAM_ID', 1);
const LAYOUT_ID = intEnv('LAYOUT_ID', 1);
// 화면이 열 예약. pages-fixture.sql이 만들고 run-pages.sh가 넘긴다.
const RESERVATION_ID = intEnv('RESERVATION_ID', 1);
const USER_ID = intEnv('USER_ID', 1);

// 조건 라벨(none|low). 값을 바꾸지 않고 결과에 붙기만 한다.
const LOAD_CONDITION = __ENV.LOAD_CONDITION || 'unset';
const RUN = intEnv('RUN', 1);
const MEASURE_SESSION = __ENV.MEASURE_SESSION || 'adhoc';
const MEASURE_COMMIT = __ENV.MEASURE_COMMIT || 'unknown';
const MEASURE_DIRTY = __ENV.MEASURE_DIRTY === 'yes';
const MEASURE_IMAGE = __ENV.MEASURE_IMAGE || 'unknown';
const MEASURE_IMAGE_STALE = __ENV.MEASURE_IMAGE_STALE || 'unknown';

function intEnv(name, fallback) {
  const v = __ENV[name];
  if (v === undefined || v === '') return fallback;
  const n = parseInt(v, 10);
  if (Number.isNaN(n)) throw new Error(`${name}이 숫자가 아니다: ${v}`);
  return n;
}

// --- 잴 화면 -----------------------------------------------------------------
//
// `rfp` 열은 rfp-scope.md 2.3의 원문 문구를 그대로 옮긴 것이다. **한 화면이
// 원문의 여러 줄을 겸하는 경우가 있고 그것도 결과다** — 결과 문서가 그 대응을
// 표로 편다.
//
// `assets`는 그 화면의 템플릿이 실제로 부르는 정적 자원이다. 지어내지 않고
// templates/ 에서 확인해 옮겼다.

const CSS_RESERVATION = ['/css/reservation.css'];
const SEATMAP_ASSETS = [
  '/css/seatmap.css',
  '/webjars/htmx.org/2.0.4/dist/htmx.min.js',
  '/js/seatmap.js',
];

export const SCREENS = [
  {
    id: 'catalog_programs',
    label: '프로그램 목록',
    rfp: '이용자 예약 신청',
    path: () => '/programs',
    assets: CSS_RESERVATION,
  },
  {
    id: 'catalog_sessions',
    label: '회차 목록',
    rfp: '이용자 예약 신청',
    path: () => `/programs/${PROGRAM_ID}?userId=${USER_ID}`,
    assets: CSS_RESERVATION,
  },
  {
    id: 'seatmap',
    label: '좌석맵 (예약 신청)',
    rfp: '이용자 예약 신청',
    path: () => `/sessions/${SESSION_ID}?userId=${USER_ID}`,
    assets: SEATMAP_ASSETS,
  },
  {
    id: 'reservation_detail',
    label: '예약 상세 (확인·티켓)',
    rfp: '이용자 예약 조회',
    path: () => `/reservations/${RESERVATION_ID}?userId=${USER_ID}`,
    assets: CSS_RESERVATION,
  },
  {
    id: 'lookup_form',
    label: '비회원 조회 폼',
    rfp: '비회원 예약 조회',
    path: () => '/reservations/lookup',
    assets: CSS_RESERVATION,
  },
  {
    // **조회 결과까지 재는 화면이다.** 폼이 맞으면 상세로 리다이렉트하므로
    // (design-spec 5.4) k6가 리다이렉트를 따라가고, 그 왕복 전체가 사용자가
    // "조회 결과"를 보기까지 걸리는 시간이다. PER-002 원문이 "화면·조회 결과"를
    // 함께 적은 것이 이 구분이다.
    id: 'lookup_result',
    label: '비회원 조회 결과 (리다이렉트 포함)',
    rfp: '비회원 예약 조회 — 조회 결과',
    path: () => `/reservations/lookup?reservationId=${RESERVATION_ID}&userId=${USER_ID}`,
    assets: CSS_RESERVATION,
  },
  {
    id: 'my_reservations',
    label: '마이페이지',
    rfp: '마이페이지 조회',
    path: () => `/my/reservations?userId=${USER_ID}`,
    assets: CSS_RESERVATION,
  },
  {
    // 예약관리·신청현황·검표현황·통계조회를 한 화면이 겸한다. #104가 통계와
    // 발권·검표 열을 이 화면에 붙였다.
    id: 'admin_reservations',
    label: '관리자 예약 현황 (목록 200줄 + 회차별 통계)',
    rfp: '관리자 예약관리 · 신청현황 · 검표현황 · 통계조회',
    path: () => '/admin/reservations',
    assets: CSS_RESERVATION,
  },
  {
    id: 'admin_programs',
    label: '관리자 프로그램 목록·등록',
    rfp: '관리자 프로그램 등록·수정',
    path: () => '/admin/programs',
    assets: CSS_RESERVATION,
  },
  {
    id: 'admin_program_detail',
    label: '관리자 회차관리',
    rfp: '관리자 회차관리',
    path: () => `/admin/programs/${PROGRAM_ID}`,
    assets: CSS_RESERVATION,
  },
  {
    id: 'admin_layouts',
    label: '관리자 좌석배치도 목록',
    rfp: '(원문 목록 밖 — 참고)',
    path: () => '/admin/layouts',
    assets: CSS_RESERVATION,
  },
  {
    // 좌석 수만큼 격자를 그린다. 저경합 시드(1000석)에서 **가장 무거운 화면**이
    // 될 후보이고, 그래서 원문 목록 밖이지만 잰다.
    id: 'admin_layout_detail',
    label: '관리자 좌석배치도 상세 (좌석 격자)',
    rfp: '(원문 목록 밖 — 참고)',
    path: () => `/admin/layouts/${LAYOUT_ID}`,
    assets: CSS_RESERVATION,
  },
  {
    // 원문의 "검표현황"은 집계 화면이고 이쪽은 게이트 단말이다. 다른 것이지만
    // 검표 축의 유일한 전용 화면이라 참고로 잰다.
    id: 'scan',
    label: '검표 단말',
    rfp: '(원문 목록 밖 — 참고)',
    path: () => '/scan',
    assets: [],
  },
];

// --- 메트릭 ------------------------------------------------------------------
//
// k6 Trend는 init 컨텍스트에서 만들어야 한다. 화면마다 하나씩 미리 만든다 —
// k6 요약이 태그별로 Trend를 쪼개 주지 않으므로, 태그만으로는 화면별 p95를
// 뽑을 수 없다(metrics.js가 hold_req_duration을 따로 둔 것과 같은 이유).

const pageTrend = {};
const docTrend = {};
for (const s of SCREENS) {
  pageTrend[s.id] = new Trend(`page_${s.id}`, true);
  docTrend[s.id] = new Trend(`doc_${s.id}`, true);
}

/** 전 화면 합산. 판정은 화면별로 하지만 전체 분포도 한 줄 남긴다. */
export const pageDuration = new Trend('page_duration', true);

/**
 * 2xx가 아닌 응답. **0이 아니면 그 실행의 숫자를 쓰지 않는다.**
 *
 * 화면에는 정상 거절이 없다. 여기 잡히는 것은 시드·픽스처가 화면과 어긋났거나
 * (예: 없는 예약을 열었다) 앱이 깨진 것이고, 어느 쪽이든 **재려던 화면을 재지
 * 않은 것**이다. classify.js의 미분류 카운터와 같은 자리다.
 */
export const badStatusTotal = new Counter('page_bad_status_total');
/** 정적 자원 쪽 실패. 문서와 나눠 세야 어디가 깨졌는지 알 수 있다. */
export const badAssetTotal = new Counter('page_bad_asset_total');

export const options = {
  summaryTrendStats: SUMMARY_TREND_STATS,
  vus: 1,
  duration: `${WARMUP_SEC + DURATION_SEC}s`,
  // **리다이렉트를 따라간다.** 비회원 조회 결과가 그 왕복이다(위 lookup_result).
  maxRedirects: 5,
  // **화면마다 임계값을 따로 건다.** 합산 p95만 보면 빠른 화면 열둑에
  // 느린 화면 하나가 묻힌다 — PER-002는 화면마다 지키라는 기준이다.
  thresholds: Object.assign(
    {
      'page_duration': ['p(95)<3000'],
      'page_bad_status_total': ['count==0'],
      'page_bad_asset_total': ['count==0'],
    },
    Object.fromEntries(SCREENS.map((s) => [`page_${s.id}`, ['p(95)<3000']])),
  ),
};

function inWarmup() {
  return exec.instance.currentTestRunDuration < WARMUP_MS;
}

export default function () {
  for (const screen of SCREENS) {
    measure(screen);
    // 화면 사이에 텀을 둔다. 사람이 화면을 넘기는 속도에 가깝고, 1 VU가
    // 쉬지 않고 때려 자기 자신과 겨루는 것을 막는다.
    sleep(0.2);
  }
}

function measure(screen) {
  const url = `${BASE_URL}${screen.path()}`;
  const tags = { screen: screen.id };

  const doc = http.get(url, { tags: Object.assign({ part: 'document' }, tags) });
  const docMs = doc.timings.duration;

  // 정적 자원은 브라우저처럼 **병렬로** 받는다. 그래서 걸린 시간은 합이 아니라
  // **가장 느린 하나**다. 더하면 자원이 많은 화면(좌석맵)에만 왜곡이 붙어
  // 화면 간 비교가 깨진다.
  //
  // **벽시계(Date.now())를 쓰지 않는다.** 해상도가 1ms라 5ms짜리 화면에서는
  // 반올림이 값보다 크게 보인다 — 실제로 자원이 없는 화면의 "화면 시간"이
  // 문서 시간보다 작게 나왔다. k6의 실수 타이밍만 쓴다.
  let assetMs = 0;
  let assetsOk = true;
  if (screen.assets.length > 0) {
    const responses = http.batch(
      screen.assets.map((a) => ({
        method: 'GET',
        url: `${BASE_URL}${a}`,
        params: { tags: Object.assign({ part: 'asset' }, tags) },
      })),
    );
    for (const r of responses) {
      if (r.status < 200 || r.status >= 300) assetsOk = false;
      if (r.timings.duration > assetMs) assetMs = r.timings.duration;
    }
  }
  const totalMs = docMs + assetMs;

  if (inWarmup()) return;

  const docOk = doc.status >= 200 && doc.status < 300;
  if (!docOk) badStatusTotal.add(1, Object.assign({ status: String(doc.status) }, tags));
  if (!assetsOk) badAssetTotal.add(1, tags);

  docTrend[screen.id].add(docMs, tags);
  pageTrend[screen.id].add(totalMs, tags);
  pageDuration.add(totalMs, tags);
}

export function handleSummary(data) {
  const cfg = {
    measurement: 'per-002-page-timing',
    loadCondition: LOAD_CONDITION,
    run: RUN,
    warmupSec: WARMUP_SEC,
    durationSec: DURATION_SEC,
    sessionId: SESSION_ID,
    reservationId: RESERVATION_ID,
    userId: USER_ID,
    measureSession: MEASURE_SESSION,
    commit: MEASURE_COMMIT,
    dirty: MEASURE_DIRTY,
    image: MEASURE_IMAGE,
    imageStale: MEASURE_IMAGE_STALE,
  };

  const screens = SCREENS.map((s) => {
    const page = data.metrics[`page_${s.id}`];
    const doc = data.metrics[`doc_${s.id}`];
    return {
      id: s.id,
      label: s.label,
      rfp: s.rfp,
      path: s.path(),
      assets: s.assets.length,
      samples: stat(page, 'count'),
      docP95: stat(doc, 'p(95)'),
      p95: stat(page, 'p(95)'),
      med: stat(page, 'med'),
      max: stat(page, 'max'),
    };
  });

  const integrity = {
    badStatus: stat(data.metrics.page_bad_status_total, 'count'),
    badAsset: stat(data.metrics.page_bad_asset_total, 'count'),
    httpReqFailed: stat(data.metrics.http_req_failed, 'passes'),
    totalSamples: stat(data.metrics.page_duration, 'count'),
  };

  const out = { config: cfg, integrity, screens };
  return {
    [`/results/pages-${LOAD_CONDITION}-${MEASURE_SESSION}-run${RUN}.json`]:
      JSON.stringify(out, null, 2),
    stdout: render(out),
  };
}

function stat(metric, key) {
  if (!metric || !metric.values) return null;
  const v = metric.values[key];
  return typeof v === 'number' ? v : null;
}

function render(out) {
  const ms = (v) => (v === null ? '   -  ' : `${v.toFixed(1)}ms`);
  const lines = [];
  lines.push('');
  lines.push(`PER-002 화면별 응답시간 — 부하 조건 ${out.config.loadCondition} · ${out.config.run}회차`);
  lines.push(`커밋 ${out.config.commit}${out.config.dirty ? ' (dirty)' : ''} · 이미지 ${out.config.image}` +
    (out.config.imageStale === 'yes' ? ' ※ HEAD보다 오래됨' : ''));
  lines.push('');
  lines.push('화면                                        표본   문서p95    화면p95    최대');
  for (const s of out.screens) {
    lines.push(
      `${s.label.padEnd(42)}${String(s.samples ?? 0).padStart(5)}  ` +
      `${ms(s.docP95).padStart(9)}  ${ms(s.p95).padStart(9)}  ${ms(s.max).padStart(9)}`,
    );
  }
  lines.push('');
  lines.push(`측정 무결성 — 2xx 아님 ${out.integrity.badStatus} · 자원 실패 ${out.integrity.badAsset} · 표본 ${out.integrity.totalSamples}`);
  if (out.integrity.badStatus || out.integrity.badAsset) {
    lines.push('!! 0이 아니다. 이 실행의 숫자를 쓰지 않는다.');
  }
  lines.push('');
  return lines.join('\n');
}
