// config.js — 7.2 경합도 3단계와 7.4 측정 프로토콜을 환경변수로 전환한다.

// 7.2 경합도 3단계. 좌석 수와 VU는 설계서가 못박은 값이므로 바꾸지 않는다.
export const PROFILES = {
  low:     { seats: 1000, vus: 100, purpose: '오버헤드 비교' },
  high:    { seats: 10,   vus: 500, purpose: '전략 차이' },
  extreme: { seats: 1,    vus: 200, purpose: '정합성 한계' },
};

function intEnv(name, fallback) {
  const v = __ENV[name];
  if (v === undefined || v === '') return fallback;
  const n = parseInt(v, 10);
  if (Number.isNaN(n)) throw new Error(`${name}이 숫자가 아니다: ${v}`);
  return n;
}

export function loadConfig() {
  const name = __ENV.SCENARIO || 'high';
  const profile = PROFILES[name];
  if (!profile) {
    throw new Error(`SCENARIO는 ${Object.keys(PROFILES).join('|')} 중 하나여야 한다: ${name}`);
  }

  // 전략은 앱이 holdfast.strategy로 스위칭한다(4절). k6는 결과에 라벨만 남긴다.
  const strategy = __ENV.STRATEGY || 'unset';

  // 7.4: 전략당 3회 반복 후 중앙값. 몇 번째 실행인지 결과 파일에 남긴다.
  const run = intEnv('RUN', 1);

  // 7.4: 워밍업 30초는 집계에서 제외.
  const warmupSec = intEnv('WARMUP_SEC', 30);

  // 본 측정 길이. **7절에 명시가 없어 기본값을 둔 값이다**(설계값 아님).
  // 4.3의 재시도 상한처럼 관측 후 조정하고, 조정 전후를 함께 기록한다.
  const durationSec = intEnv('DURATION_SEC', 120);

  // VU를 한 번에 올리면 그 자체가 스파이크가 되어 워밍업 구간이 왜곡된다.
  // 램프업을 워밍업 안에서 끝내, 본 측정은 목표 VU 고정 상태로만 돌게 한다.
  const rampSec = intEnv('RAMP_SEC', Math.min(15, Math.floor(warmupSec / 2)));
  if (rampSec >= warmupSec) {
    throw new Error(`RAMP_SEC(${rampSec})는 WARMUP_SEC(${warmupSec})보다 작아야 한다`);
  }

  // 좌석 ID는 시드가 만든 연속 구간을 쓴다(sql/seed.sql).
  const seatIdBase = intEnv('SEAT_ID_BASE', 1);
  const sessionId = intEnv('SESSION_ID', 1);

  // 한 요청에 몇 좌석을 잡을지. 기본 1이다 — 복수 좌석 홀드는 전부 아니면
  // 전무라서(api-spec 4절) 2석 이상이면 거절 사유가 좌석별로 섞여 경합도
  // 해석이 흐려진다. 7.2의 세 시나리오는 좌석 경합을 보는 것이 목적이다.
  const seatsPerHold = intEnv('SEATS_PER_HOLD', 1);

  return {
    scenario: name,
    strategy,
    run,
    profile,
    seats: profile.seats,
    vus: profile.vus,
    warmupSec,
    warmupMs: warmupSec * 1000,
    durationSec,
    rampSec,
    sessionId,
    seatIdBase,
    seatsPerHold,
    baseUrl: __ENV.BASE_URL || 'http://nginx:80',
  };
}

/**
 * k6 Trend의 기본 요약에는 p(99)와 count가 없다. 7.1이 p99를 "참고" 지표로
 * 요구하고 TPS 계산에도 count가 필요하므로 명시적으로 켠다. 이 값을 빠뜨리면
 * 요약에서 p99와 TPS가 조용히 비어 나온다.
 */
export const SUMMARY_TREND_STATS = ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'count'];

/** 7.4에 맞춘 stages — 램프업과 워밍업이 끝난 뒤에 본 측정이 시작된다. */
export function stagesFor(cfg) {
  return [
    { duration: `${cfg.rampSec}s`, target: cfg.vus },                  // 램프업(워밍업 안)
    { duration: `${cfg.warmupSec - cfg.rampSec}s`, target: cfg.vus },  // 안정화(워밍업 안)
    { duration: `${cfg.durationSec}s`, target: cfg.vus },              // 본 측정
  ];
}

export function resultPath(cfg) {
  return `/results/${cfg.strategy}-${cfg.scenario}-run${cfg.run}.json`;
}
