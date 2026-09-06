package com.inhalab.holdfast.web;

import com.inhalab.holdfast.api.ApiException;
import com.inhalab.holdfast.lookup.ReservationLookupRepository;
import com.inhalab.holdfast.lookup.ReservationSummary;
import com.inhalab.holdfast.reservation.ReservationService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 예약 조회 — 비회원 조회와 마이페이지. 이슈 #103.
 *
 * <p>RFP 원문이 <b>"비회원 예약 조회 및 마이페이지 조회 화면"</b>을 PER-002 대상
 * 한 줄로 묶었다({@code rfp-scope.md} 2.3). 그래서 한 이슈이고 한 컨트롤러다.
 *
 * <h2>두 화면이 소유권 검사를 나눠 쓰는 방식</h2>
 *
 * <p><b>검사는 하나다. 우회 경로를 만들지 않는다.</b>
 * {@link ReservationService#get}이 남의 예약을 404로 막는 그 검사를 두 화면이
 * 그대로 탄다 — 다른 것은 <b>{@code userId}를 어디서 얻느냐</b>뿐이다.
 *
 * <ul>
 *   <li><b>마이페이지</b> — {@code ?userId=}가 직접 준다. 그 사용자의 예약
 *       <b>목록</b>을 보여주고, 각 줄이 기존 상세 화면으로 이어진다</li>
 *   <li><b>비회원 조회</b> — 예약번호와 예약자 번호를 폼으로 받아
 *       <b>같은 검사에 태운다.</b> 통과하면 상세 화면으로 보낸다</li>
 * </ul>
 *
 * <p>이슈가 "관리자 예외를 두지 않은 것과 같은 기준으로 판단한다"고 적었다.
 * 그 기준이 여기서는 <b>비회원 조회가 소유권 검사를 대체하지 않고 그대로
 * 쓴다</b>는 것이다. 대체하면 그 경로를 아무나 쓴다.
 *
 * <h2>확인 정보로 쓸 것이 도메인에 없다</h2>
 *
 * <p>이슈는 "예약번호만으로는 부족하니 하나를 더 요구한다(예약자명 등)"고
 * 적었다. <b>그 컬럼이 없다.</b> {@code reservation}에는
 * {@code session_id}·{@code user_id}·{@code hold_id}·{@code status}·시각뿐이고,
 * {@code erd.md} 4절이 <b>"사용자 테이블은 만들지 않는다"</b>를 정해 두었다.
 *
 * <p>넣으려면 마이그레이션을 더하고 <b>홀드·확정 경로가 그 값을 받아야</b>
 * 한다 — 그것이 측정 경로다. 이 이슈에서 건드릴 수 없다.
 *
 * <p>그래서 <b>예약번호 + 예약자 번호</b> 두 값을 요구한다. 그것이 지금 도메인이
 * 가진 전부이고, 무엇보다 <b>기존 검사를 그대로 쓰는 유일한 조합</b>이다.
 *
 * <h2>이것은 접근 제어가 아니다</h2>
 *
 * <p><b>예약자 번호는 추측할 수 있다.</b> 그래서 이 화면은 남의 예약을 막지
 * 못한다. 막으려면 인증이 필요하고 인증은 배제했다({@code rfp-scope.md} 4.3 —
 * 측정 하네스가 깨진다).
 *
 * <p><b>화면에 자물쇠를 다는 흉내를 내지 않는다.</b> {@code scope-m4.md} 6절이
 * 같은 판단을 이미 했다 — API 수준에서 누구나 {@code X-User-Id}를 바꿔 보낼 수
 * 있으므로 <b>화면만 고정하는 것은 아무것도 막지 못하면서 시연만 불가능하게
 * 한다.</b> 확인 코드를 화면에만 걸어도 {@code GET /api/reservations/{id}}로
 * 그대로 우회된다.
 *
 * <p>그 한계를 화면에 적고 {@code requirements.md} REQ-14의 상태에도 적었다.
 */
@Controller
public class ReservationLookupPageController {

    /** 인증 미구현 — 다른 화면과 같은 기본값. */
    private static final long DEFAULT_USER_ID = 1L;

    private final ReservationService reservationService;
    private final ReservationLookupRepository lookupRepository;

    public ReservationLookupPageController(ReservationService reservationService,
                                           ReservationLookupRepository lookupRepository) {
        this.reservationService = reservationService;
        this.lookupRepository = lookupRepository;
    }

    /**
     * 비회원 예약 조회. 예약번호와 예약자 번호를 받아 상세 화면으로 보낸다.
     *
     * <p><b>둘 다 없으면 폼만 그린다.</b> 이 화면 자체가 "들어가는 문"이고,
     * 조회는 그 문을 지나는 것이다.
     *
     * <p><b>실패는 리다이렉트하지 않는다.</b> 그 자리에서 폼을 다시 그리고 사유를
     * 띄운다 — 앱이 2대라 플래시가 로드밸런서를 못 넘는다
     * ({@code design-spec.md} 5.5). <b>성공만 리다이렉트한다</b>(PRG), 그리고
     * 상대 경로다(5.4).
     */
    @GetMapping("/reservations/lookup")
    public String lookup(@RequestParam(required = false) Long reservationId,
                         @RequestParam(required = false) Long userId,
                         Model model) {
        if (reservationId == null || userId == null) {
            return "reservations/lookup";
        }

        model.addAttribute("reservationId", reservationId);
        model.addAttribute("userId", userId);
        try {
            // **검사를 우회하지 않는다.** 예약 확인 화면이 쓰는 그 메서드다.
            reservationService.get(reservationId, userId);
        } catch (ApiException e) {
            model.addAttribute("error", e.getMessage());
            return "reservations/lookup";
        }
        return "redirect:/reservations/" + reservationId + "?userId=" + userId;
    }

    /**
     * 마이페이지 — 한 사용자의 예약 목록.
     *
     * <p><b>남의 {@code userId}로 열리는 것은 의도된 동작이다.</b>
     * {@code scope-m4.md} 6절이 정한 자세이며, 이 화면만 고정해도 API가 그대로
     * 열려 있어 아무것도 막지 못한다. 시연은 사용자를 바꿔 가며 보여주는 것이
     * 수단이기도 하다.
     *
     * <p>목록이 비어도 오류가 아니다 — 그 사용자에게 예약이 없는 것이다.
     */
    @GetMapping("/my/reservations")
    public String myReservations(@RequestParam(name = "userId", defaultValue = "" + DEFAULT_USER_ID)
                                 long userId,
                                 Model model) {
        List<ReservationSummary> reservations = lookupRepository.summariesOf(userId);
        model.addAttribute("userId", userId);
        model.addAttribute("reservations", reservations);
        return "reservations/my";
    }
}
