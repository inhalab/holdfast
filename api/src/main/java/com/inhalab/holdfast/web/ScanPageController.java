package com.inhalab.holdfast.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 검표 화면. 이슈 #81. 게이트 단말에서 QR 토큰을 입력·스캔해
 * {@code POST /api/tickets/scan}(#80)을 호출하는 정적 페이지다.
 *
 * <p>스캔 주체가 티켓 소유자가 아니라 게이트 단말이므로 사용자 식별이 필요
 * 없다 — 이 컨트롤러는 모델도 만들지 않는다.
 */
@Controller
public class ScanPageController {

    @GetMapping("/scan")
    public String show() {
        return "scan/index";
    }
}
