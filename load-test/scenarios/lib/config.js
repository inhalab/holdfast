// config.js — 7.2 경합도 3단계와 7.4 측정 프로토콜을 환경변수로 전환한다.

// 7.2 경합도 3단계. 좌석 수와 VU는 설계서가 못박은 값이므로 바꾸지 않는다.
//
// userPool은 7.3 고정 변수 표의 "시나리오별 사용자 수"이며 **VU 수와 다르다.**
// 사용자 풀이 VU 수와 같으면 저경합에서 1인 최대 매수(4매)가 좌석보다 먼저
// 바닥나, 좌석 경합이 아니라 할당량 거절을 재게 된다. scripts/seed.sh의 표와
// 반드시 같은 값이어야 한다 — 시드가 만들지 않은 사용자로 요청하면 앱이
// 할당량 행을 찾지 못해 500을 낸다.
export const PROFILES = {
  low:     { seats: 1000, vus: 100, userPool: 400, purpose: '오버헤드 비교' },
  high:    { seats: 10,   vus: 500, userPool: 500, purpose: '전략 차이' },
  extreme: { seats: 1,    vus: 200, userPool: 200, purpose: '정합성 한계' },

  // 7.2.2 지속 경합. 경합도 3단계와 **다른 표에 기록한다** — 이쪽 p95에는 락
  // 대기가 들어 있고 저쪽에는 들어 있지 않다.
  //
  // 좌석 수는 행 락 점유율로 역산한 값이며 SUSTAINED_SEATS로 보정한다. 보정은
  // pessimistic 한 전략에서만 하고 그 값을 고정한다 — 전략마다 맞추면 좌석 수가
  // 변수가 되어 비교가 깨진다.
  sustained: { seats: sustainedSeats(), vus: 500, userPool: 500, purpose: '락 대기 유지' },
};

/**
 * 지속 경합 시나리오의 좌석 수.
 *
 * 기본값 3석은 `f = (홀드+해제 트랜잭션) × (초당 회수 ÷ 좌석 수)` 로 역산한
 * 출발점이다(7.2.2). VU 500 · sleep 1초에서 10석은 f≈0.2로 대기가 얕고, 1석은
 * f>1로 포화해 p95가 "포기까지 걸린 시간"이 된다.
 *
 * **scripts/seed.sh의 sustained 항목과 반드시 같은 값이어야 한다.** 시드가 만든
 * 좌석보다 큰 번호를 고르면 SEAT_NOT_IN_SESSION이 섞여 측정이 오염된다.
 */
function sustainedSeats() {
  const v = __ENV.SUSTAINED_SEATS;
  if (v === undefined || v === '') return 3;
  const n = parseInt(v, 10);
  if (Number.isNaN(n) || n < 1) throw new Error(`SUSTAINED_SEATS가 잘못됐다: ${v}`);
  return n;
}

function intEnv(name, fallback) {
  const v = __ENV[name];
  if (v === undefined || v === '') return fallback;
  const n = parseInt(v, 10);
  if (Number.isNaN(n)) throw new Error(`${name}이 숫자가 아니다: ${v}`);
  return n;
}

/** 7.4 최종 측정용 본 측정 길이. 이 값 미만이면 개발 확인용 실행으로 본다. */
export const FINAL_DURATION_SEC = 120;

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

  // 본 측정 길이. 7.4가 용도별로 두 값을 구분해 둔다.
  //   개발 확인용 30초(기본값) — 시나리오·집계를 고치며 반복 실행할 때
  //   최종 측정용 120초        — 보고서에 실을 숫자를 뽑을 때
  //
  // 기본값이 30초인 이유는 전체 실행 시간이다. 5전략 × 3회 × 3시나리오 = 45회에
  // 회차마다 워밍업 30초가 붙어, 120초로 전부 돌리면 두 시간을 넘는다.
  // **개발 확인용 실행의 숫자는 7.6 기록 양식에 싣지 않는다.**
  const durationSec = intEnv('DURATION_SEC', 30);
  const isFinalRun = durationSec >= FINAL_DURATION_SEC;

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
    userPool: profile.userPool,
    warmupSec,
    warmupMs: warmupSec * 1000,
    durationSec,
    isFinalRun,
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
