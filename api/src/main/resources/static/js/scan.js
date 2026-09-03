/*
 * 검표 화면. POST /api/tickets/scan(#80)을 호출한다.
 *
 * 거절도 200으로 온다(REJECTED_DUPLICATE 등) — 검표 거절은 오류가 아니라
 * 판정이다. 그래서 fetch의 성공/실패가 아니라 응답의 result 필드로 색을
 * 정한다(ADMITTED만 ok, 나머지는 warn).
 */
(function () {
    "use strict";

    const input = document.getElementById("token-input");
    const button = document.getElementById("scan-btn");
    const result = document.getElementById("result");

    const RESULT_LABEL = {
        ADMITTED: "입장 허용",
        REJECTED_DUPLICATE: "거절 — 이미 사용된 티켓",
        REJECTED_TIME: "거절 — 입장 가능 시간이 아님",
        REJECTED_INVALID: "거절 — 유효하지 않은 티켓",
    };

    async function scan() {
        const qrToken = input.value.trim();
        if (!qrToken) return;

        button.disabled = true;
        result.innerHTML = "";
        try {
            const res = await fetch("/api/tickets/scan", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ qrToken }),
            });
            if (res.status === 200) {
                const data = await res.json();
                const ok = data.result === "ADMITTED";
                const label = RESULT_LABEL[data.result] || data.result;
                result.innerHTML =
                    '<div class="scan-result ' + (ok ? "ok" : "warn") + '">' +
                    '<span class="result-code">' + label + "</span>" +
                    (data.rejectReason ? data.rejectReason : "티켓 ID " + data.ticketId) +
                    "</div>";
            } else {
                result.innerHTML = '<div class="scan-result warn">요청 형식이 올바르지 않습니다. (' + res.status + ")</div>";
            }
        } catch (e) {
            result.innerHTML = '<div class="scan-result warn">네트워크 오류로 검표하지 못했습니다.</div>';
        } finally {
            button.disabled = false;
            input.value = "";
            input.focus();
        }
    }

    button.addEventListener("click", scan);
    input.addEventListener("keydown", (e) => {
        if (e.key === "Enter") scan();
    });
})();
