package com.inhalab.holdfast.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>회차 여럿을 프로그램 한 줄로 접는 규칙을 고정한다.</b> 이슈 #192.
 *
 * <p>프로그램 목록은 회차를 낱개로 보여 주지 않는다 — 카드 한 장이 "지금 살 수
 * 있나"에 답해야 한다. 그 한 장을 만드는 규칙이 틀리면 <b>목록과 상세가
 * 어긋난다</b>: 목록에서 "매진"인데 들어가면 자리가 있거나, 반대가 된다.
 * {@link SaleStatePolicyTest}가 회차 하나의 상태를 고정한다면 이 테스트는
 * 그것들을 접는 방식을 고정한다.
 *
 * <p><b>DB가 필요 없다.</b> {@link ProgramCard#of}가 값만 받는 순수 함수라서
 * 그렇고, JPA 엔티티를 안 받게 만든 이유가 이것이다.
 */
@DisplayName("프로그램 카드: 회차들을 한 줄로 접는다")
class ProgramCardTest {

    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");
    private static final Instant PAST = NOW.minusSeconds(3600);
    private static final Instant SOON = NOW.plusSeconds(3600);
    private static final Instant LATER = NOW.plusSeconds(7200);

    private static SessionCard session(Instant startsAt, long available, SaleState state) {
        return new SessionCard(1L, startsAt, startsAt, null, "OPEN", available, 100, state);
    }

    private static ProgramCard card(SessionCard... sessions) {
        return ProgramCard.of(7L, "데모 공연", "화면 시연용", List.of(sessions), NOW);
    }

    @Nested
    @DisplayName("대표 상태 — «지금 살 수 있나»가 먼저다")
    class State {

        @Test
        @DisplayName("하나라도 팔고 있으면 판매중이다")
        void 하나라도_팔면_판매중() {
            ProgramCard c = card(
                    session(SOON, 0, SaleState.SOLD_OUT),
                    session(LATER, 5, SaleState.ON_SALE),
                    session(LATER, 0, SaleState.CLOSED));

            assertThat(c.saleState()).isEqualTo(SaleState.ON_SALE);
        }

        @Test
        @DisplayName("파는 회차가 없으면 곧 열리는 쪽을 먼저 알린다")
        void 오픈_전이_매진보다_앞선다() {
            ProgramCard c = card(
                    session(SOON, 0, SaleState.SOLD_OUT),
                    session(LATER, 0, SaleState.NOT_YET_OPEN));

            assertThat(c.saleState()).isEqualTo(SaleState.NOT_YET_OPEN);
        }

        @Test
        @DisplayName("매진이 종료보다 앞선다 — 취소분이 돌아올 수 있는 쪽이다")
        void 매진이_종료보다_앞선다() {
            ProgramCard c = card(
                    session(PAST, 0, SaleState.CLOSED),
                    session(SOON, 0, SaleState.SOLD_OUT));

            assertThat(c.saleState()).isEqualTo(SaleState.SOLD_OUT);
        }

        @Test
        @DisplayName("회차가 하나도 없으면 종료다 — 팔 것이 없다는 점에서 같다")
        void 회차가_없으면_종료() {
            ProgramCard c = card();

            assertThat(c.saleState()).isEqualTo(SaleState.CLOSED);
            assertThat(c.hasSessions()).isFalse();
            assertThat(c.sessionCount()).isZero();
        }
    }

    @Nested
    @DisplayName("잔여 — 살 수 있는 자리만 센다")
    class Available {

        @Test
        @DisplayName("파는 회차의 잔여만 더한다")
        void 파는_회차만_더한다() {
            ProgramCard c = card(
                    session(SOON, 3, SaleState.ON_SALE),
                    session(LATER, 4, SaleState.ON_SALE));

            assertThat(c.available()).isEqualTo(7);
        }

        @Test
        @DisplayName("닫힌 회차에 남은 자리는 빈자리가 아니다")
        void 닫힌_회차는_빼고_센다() {
            // 이것을 더하면 "잔여 40석인데 살 수 없다"가 화면에 뜬다.
            ProgramCard c = card(
                    session(SOON, 2, SaleState.ON_SALE),
                    session(LATER, 40, SaleState.CLOSED),
                    session(LATER, 38, SaleState.NOT_YET_OPEN));

            assertThat(c.available()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("다음 회차 — 아직 시작 안 한 것 중 가장 이른 것")
    class NextSession {

        @Test
        @DisplayName("이미 시작한 회차는 «다음»이 아니다")
        void 지난_회차는_고르지_않는다() {
            ProgramCard c = card(
                    session(PAST, 0, SaleState.CLOSED),
                    session(LATER, 1, SaleState.ON_SALE),
                    session(SOON, 1, SaleState.ON_SALE));

            assertThat(c.nextStartsAt()).isEqualTo(SOON);
        }

        @Test
        @DisplayName("판매 상태와 무관하게 고른다 — 매진이어도 언제 하는지는 알려야 한다")
        void 매진이어도_시각은_보여준다() {
            ProgramCard c = card(session(SOON, 0, SaleState.SOLD_OUT));

            assertThat(c.nextStartsAt()).isEqualTo(SOON);
        }

        @Test
        @DisplayName("남은 회차가 모두 지났으면 비어 있다 — 화면이 그 줄을 감춘다")
        void 남은_회차가_없으면_비어있다() {
            ProgramCard c = card(session(PAST, 0, SaleState.CLOSED));

            assertThat(c.nextStartsAt()).isNull();
            assertThat(c.nextStartsAtText()).isEmpty();
        }

        @Test
        @DisplayName("시각은 Asia/Seoul로 찍는다 — 관리자가 넣은 기준과 같아야 한다")
        void 서울_기준으로_찍는다() {
            // 09:00Z 는 서울에서 18:00 이다. UTC 로 찍으면 관리자 화면과 9시간 어긋난다.
            ProgramCard c = card(session(Instant.parse("2026-12-01T09:00:00Z"), 1, SaleState.ON_SALE));

            assertThat(c.nextStartsAtText()).isEqualTo("12/01 18:00");
        }
    }
}
