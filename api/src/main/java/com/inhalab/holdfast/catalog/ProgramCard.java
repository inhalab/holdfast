package com.inhalab.holdfast.catalog;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/**
 * 프로그램 목록의 카드 한 장(#192).
 *
 * <p><b>{@link SessionCard} 여러 장을 프로그램 하나로 접은 것이다.</b> 회차
 * 목록이 회차마다 보여 주는 것(판매 상태·잔여)을 프로그램 목록에서는 대표값
 * 하나로 보여 준다 — 그래야 <b>들어가 보지 않고도</b> 지금 살 수 있는지 안다.
 *
 * <p>접는 규칙은 {@link #of}에 있다.
 *
 * @param nextStartsAt 앞으로 시작할 회차 중 가장 이른 것. 남은 회차가 모두
 *                     지났거나 회차가 없으면 {@code null}이고, 화면은 그때
 *                     시각을 감춘다.
 * @param sessionCount 이 프로그램의 회차 수. 0이면 아직 회차를 안 만든 것이다.
 * @param available    <b>파는 회차들의</b> 잔여 좌석 합. 닫힌 회차의 남은 좌석을
 *                     더하면 "잔여 40석인데 살 수 없다"가 된다.
 * @param saleState    프로그램 전체의 대표 상태.
 */
public record ProgramCard(
        Long programId,
        String name,
        String description,
        Instant nextStartsAt,
        int sessionCount,
        long available,
        SaleState saleState
) {

    /**
     * 화면에 찍는 시각의 기준. {@code AdminCatalogPageController.FORM_ZONE}과 같은
     * 값이어야 한다 — 관리자가 넣은 시각과 사용자가 보는 시각이 갈리면 안 된다.
     */
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private static final DateTimeFormatter NEXT_FORMAT = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    /** 회차 목록으로 들어갈 이유가 있는가. 회차가 없으면 빈 화면을 보여줄 뿐이다. */
    public boolean hasSessions() {
        return sessionCount > 0;
    }

    /**
     * 다음 회차 시각을 사람이 읽는 꼴로. 없으면 빈 문자열이고 화면은 그 줄을 감춘다.
     *
     * <p><b>서버에서 만든다.</b> 템플릿에는 {@code Instant}를 포맷할 수단이 없어
     * 그대로 찍으면 {@code 2026-12-01T10:00:00Z}가 화면에 나온다 — 회차 목록
     * 화면이 지금 그렇다.
     */
    public String nextStartsAtText() {
        return nextStartsAt == null ? "" : NEXT_FORMAT.format(nextStartsAt.atZone(ZONE));
    }

    /**
     * 회차 카드들을 프로그램 카드 한 장으로 접는다.
     *
     * <h3>대표 상태는 «사용자가 할 수 있는 일»의 순서로 고른다</h3>
     *
     * <p>하나라도 팔고 있으면 {@link SaleState#ON_SALE}이다 — 목록에서 사용자가
     * 묻는 것은 <b>"지금 들어가면 살 수 있나"</b>이지 "모든 회차가 열렸나"가
     * 아니다. 파는 회차가 없으면 곧 열리는 것이 있는지({@code NOT_YET_OPEN}),
     * 그것도 아니면 자리가 없어서인지({@code SOLD_OUT}), 끝난 것인지
     * ({@code CLOSED}) 순으로 본다.
     *
     * <h3>엔티티가 아니라 값을 받는다</h3>
     *
     * <p>{@code seat.Program}을 받으면 이 규칙을 시험하려고 JPA 엔티티를 만들어야
     * 하는데 그 기본 생성자는 {@code protected}다. 규칙과 저장 방식은 갈라 두는
     * 편이 맞다 — {@link SaleState#of}를 순수 함수로 뽑은 것과 같은 이유다.
     *
     * <p><b>«매진»이 «종료»보다 앞선다.</b> 취소분이 돌아올 수 있는 쪽이 사용자에게
     * 더 쓸모 있는 정보이고, 종료된 회차는 어차피 아무것도 바뀌지 않는다.
     *
     * <p>회차가 하나도 없으면 {@code CLOSED}다. 팔 것이 없다는 점에서는 끝난 것과
     * 같다 — 화면은 이 경우를 {@link #hasSessions()}로 따로 알린다.
     *
     * <h3>다음 회차는 «아직 시작 안 한» 것 중 가장 이른 것이다</h3>
     *
     * <p>이미 시작한 회차를 적으면 지난 시각이 "다음 회차"로 찍힌다. 판매 상태와
     * 무관하게 고른다 — 매진이어도 언제 하는 프로그램인지는 알려 줘야 한다.
     */
    public static ProgramCard of(Long programId, String name, String description,
                                List<SessionCard> sessions, Instant now) {
        SaleState state = foldStates(sessions);
        long available = sessions.stream()
                .filter(SessionCard::isReservable)
                .mapToLong(SessionCard::available)
                .sum();
        Instant next = sessions.stream()
                .map(SessionCard::startsAt)
                .filter(at -> at != null && !at.isBefore(now))
                .min(Comparator.naturalOrder())
                .orElse(null);

        return new ProgramCard(programId, name, description, next, sessions.size(), available, state);
    }

    private static SaleState foldStates(List<SessionCard> sessions) {
        SaleState best = SaleState.CLOSED;
        for (SessionCard s : sessions) {
            SaleState state = s.saleState();
            if (state == SaleState.ON_SALE) {
                return SaleState.ON_SALE;
            }
            if (state == SaleState.NOT_YET_OPEN) {
                best = SaleState.NOT_YET_OPEN;
            } else if (state == SaleState.SOLD_OUT && best != SaleState.NOT_YET_OPEN) {
                best = SaleState.SOLD_OUT;
            }
        }
        return best;
    }
}
