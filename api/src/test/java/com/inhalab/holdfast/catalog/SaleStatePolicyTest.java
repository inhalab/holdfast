package com.inhalab.holdfast.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>접수종료 노출 정책을 고정한다.</b> 이슈 #108 — SFR-006의 검수 기준이
 * "접수종료 노출 방식이 운영정책과 일치"이고, 그 운영정책은 우리가
 * {@code design-spec.md} 5.6에 정한 것이다. 이 테스트가 그 문서의 표를 코드로 옮긴 것이다.
 *
 * <p><b>DB가 필요 없다.</b> {@link SaleState#of}가 순수 함수라서 그렇고, 순수 함수로
 * 뽑은 이유가 이것이다 — 정책은 화면 둘이 함께 쓰는 규칙이므로 화면과 따로 검증할
 * 수 있어야 한다.
 */
@DisplayName("접수종료 노출 정책 (design-spec 5.6)")
class SaleStatePolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");
    private static final Instant PAST = NOW.minusSeconds(3600);
    private static final Instant FUTURE = NOW.plusSeconds(3600);

    @Nested
    @DisplayName("status가 잔여석보다 먼저다")
    class StatusFirst {

        @Test
        @DisplayName("닫힌 회차는 자리가 남아도 CLOSED다")
        void closedWinsOverAvailability() {
            assertThat(SaleState.of("CLOSED", PAST, 100, NOW)).isEqualTo(SaleState.CLOSED);
        }

        @Test
        @DisplayName("오픈 전 회차는 자리가 0이어도 SOLD_OUT이 아니다 — 매진일 수 없다")
        void notYetOpenIsNeverSoldOut() {
            assertThat(SaleState.of("SCHEDULED", PAST, 0, NOW)).isEqualTo(SaleState.NOT_YET_OPEN);
        }

        @Test
        @DisplayName("SCHEDULED와 CLOSED를 하나로 뭉치지 않는다 — 보여 줄 문장이 다르다")
        void scheduledAndClosedAreDistinct() {
            assertThat(SaleState.of("SCHEDULED", null, 10, NOW)).isEqualTo(SaleState.NOT_YET_OPEN);
            assertThat(SaleState.of("CLOSED", null, 10, NOW)).isEqualTo(SaleState.CLOSED);
        }
    }

    @Nested
    @DisplayName("OPEN인 회차 안에서")
    class WhenOpen {

        @Test
        @DisplayName("예약 오픈 시각 전이면 NOT_YET_OPEN — 서버의 RESERVATION_NOT_OPEN을 앞당겨 알린다")
        void beforeReserveOpensAt() {
            assertThat(SaleState.of("OPEN", FUTURE, 10, NOW)).isEqualTo(SaleState.NOT_YET_OPEN);
        }

        @Test
        @DisplayName("오픈 시각 이후 자리가 있으면 ON_SALE")
        void onSale() {
            assertThat(SaleState.of("OPEN", PAST, 1, NOW)).isEqualTo(SaleState.ON_SALE);
        }

        @Test
        @DisplayName("오픈 시각 이후 자리가 없으면 SOLD_OUT")
        void soldOut() {
            assertThat(SaleState.of("OPEN", PAST, 0, NOW)).isEqualTo(SaleState.SOLD_OUT);
        }

        @Test
        @DisplayName("오픈 시각이 없으면 시각 제한이 없는 것으로 본다")
        void nullReserveOpensAtMeansNoLimit() {
            assertThat(SaleState.of("OPEN", null, 5, NOW)).isEqualTo(SaleState.ON_SALE);
        }

        @Test
        @DisplayName("경계: 오픈 시각과 정확히 같은 순간은 이미 열린 것이다")
        void exactlyAtReserveOpensAtIsOpen() {
            // isBefore(now, opensAt)가 거짓이므로 열린 쪽으로 간다. 닫는 쪽으로
            // 잡으면 "오픈 시각인데 아직 못 산다"가 되어 안내 문구와 어긋난다.
            assertThat(SaleState.of("OPEN", NOW, 5, NOW)).isEqualTo(SaleState.ON_SALE);
        }
    }

    @Test
    @DisplayName("좌석을 고를 수 있는 상태는 ON_SALE 하나뿐이다")
    void onlyOnSaleIsSellable() {
        assertThat(SaleState.ON_SALE.sellable()).isTrue();
        assertThat(SaleState.NOT_YET_OPEN.sellable()).isFalse();
        assertThat(SaleState.SOLD_OUT.sellable()).isFalse();
        assertThat(SaleState.CLOSED.sellable()).isFalse();
    }
}
