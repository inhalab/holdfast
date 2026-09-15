/*
 * 검표 화면. POST /api/tickets/scan(#80)을 호출한다.
 *
 * 거절도 200으로 온다(REJECTED_DUPLICATE 등) — 검표 거절은 오류가 아니라
 * 판정이다. 그래서 fetch의 성공/실패가 아니라 응답의 result 필드로 색을
 * 정한다(ADMITTED만 ok, 나머지는 warn).
 *
 * **DOM을 만들어 붙인다. 문자열을 이어 innerHTML에 넣지 않는다**(#192).
 * 판정에 구역명이 실리는데 그 값은 관리자가 입력한 자유 문자열이다 —
 * 예전처럼 이어 붙이면 배치도 이름에 넣은 태그가 이 화면에서 실행된다.
 */
(function () {
    "use strict";

    const input = document.getElementById("token-input");
    const button = document.getElementById("scan-btn");
    const result = document.getElementById("result");
    const historyCard = document.getElementById("history-card");
    const historyList = document.getElementById("history-list");

    /** 직전 스캔을 몇 건까지 남길지. 게이트에서 되짚는 범위는 이 정도다. */
    const HISTORY_LIMIT = 3;

    const RESULT_LABEL = {
        ADMITTED: "입장 허용",
        REJECTED_DUPLICATE: "이미 사용된 티켓",
        REJECTED_TIME: "입장 가능 시간이 아님",
        REJECTED_INVALID: "유효하지 않은 티켓",
    };

    /** 목록에 들어갈 짧은 배지 글자. 판정 전문은 위 색면이 맡는다. */
    const SHORT_LABEL = { ADMITTED: "허용" };

    function el(tag, className, text) {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined && text !== null) node.textContent = text;
        return node;
    }

    /** "A구역 3열 7번" 같은 자리 표시. 좌석을 특정 못 한 응답이면 빈 문자열이다. */
    function seatLabel(data) {
        if (!data.seatNo) return "";
        return data.zoneName ? data.zoneName + " " + data.seatNo : data.seatNo;
    }

    /** 응답의 scannedAt을 시:분:초로. 서버가 판정한 시각이지 화면이 그린 시각이 아니다. */
    function timeOf(iso, withSeconds) {
        if (!iso) return "";
        const d = new Date(iso);
        if (isNaN(d.getTime())) return "";
        const opts = { hour: "2-digit", minute: "2-digit", hour12: false };
        if (withSeconds) opts.second = "2-digit";
        return d.toLocaleTimeString("ko-KR", opts);
    }

    /* --- 판정 색면 --- */

    function renderVerdict(data) {
        const ok = data.result === "ADMITTED";
        const panel = el("div", "scan-result " + (ok ? "ok" : "warn"));

        const head = el("div", "verdict-head");
        head.appendChild(verdictIcon(ok));

        const text = el("div", "verdict-text");
        text.appendChild(el("span", "result-code", RESULT_LABEL[data.result] || data.result));

        // 좌석 → 티켓 ID 순서다. 검표원이 손에 든 표와 맞춰 보는 값이 좌석이고,
        // 티켓 ID는 나중에 이력을 찾을 때 쓴다.
        const detail = [seatLabel(data), data.ticketId ? "티켓 " + data.ticketId : ""]
            .filter(Boolean)
            .join(" · ");
        if (detail) text.appendChild(el("span", "verdict-detail", detail));

        head.appendChild(text);
        panel.appendChild(head);

        const at = timeOf(data.scannedAt, true);
        if (at) panel.appendChild(el("div", "verdict-time", at + " 확인"));

        result.replaceChildren(panel);
    }

    function verdictIcon(ok) {
        const NS = "http://www.w3.org/2000/svg";
        const svg = document.createElementNS(NS, "svg");
        svg.setAttribute("class", "verdict-icon");
        svg.setAttribute("viewBox", "0 0 24 24");
        svg.setAttribute("aria-hidden", "true");
        const circle = document.createElementNS(NS, "circle");
        circle.setAttribute("cx", "12");
        circle.setAttribute("cy", "12");
        circle.setAttribute("r", "10");
        svg.appendChild(circle);
        const mark = document.createElementNS(NS, "path");
        mark.setAttribute("d", ok ? "M8 12.5l2.5 2.5 5.5-5.5" : "M9 9l6 6M15 9l-6 6");
        svg.appendChild(mark);
        return svg;
    }

    /** 판정이 아닌 실패(네트워크·요청 형식). 좌석도 시각도 없다. */
    function renderFailure(message) {
        const panel = el("div", "scan-result warn");
        const head = el("div", "verdict-head");
        head.appendChild(verdictIcon(false));
        const text = el("div", "verdict-text");
        text.appendChild(el("span", "result-code", message));
        head.appendChild(text);
        panel.appendChild(head);
        result.replaceChildren(panel);
    }

    /* --- 직전 스캔 --- */

    function pushHistory(data) {
        const ok = data.result === "ADMITTED";
        const row = el("li", "history-row");

        row.appendChild(el("span", "history-badge " + (ok ? "ok" : "warn"),
                SHORT_LABEL[data.result] || "거절"));

        const detail = [
            ok ? seatLabel(data) : (data.rejectReason || RESULT_LABEL[data.result] || data.result),
            data.ticketId ? String(data.ticketId) : "",
        ].filter(Boolean).join(" · ");
        row.appendChild(el("span", "history-detail", detail));

        row.appendChild(el("span", "history-time", timeOf(data.scannedAt, false)));

        historyList.prepend(row);
        while (historyList.children.length > HISTORY_LIMIT) {
            historyList.lastElementChild.remove();
        }
        historyCard.hidden = false;
    }

    /* --- 스캔 --- */

    async function scan() {
        const qrToken = input.value.trim();
        if (!qrToken) return;

        button.disabled = true;
        // **직전 판정을 먼저 지운다.** 남겨 두면 다음 사람을 스캔하는 동안 이전
        // 사람의 "입장 허용"이 화면에 그대로 있다 — 게이트에서 가장 위험한 오독이다.
        result.replaceChildren();
        try {
            const res = await fetch("/api/tickets/scan", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ qrToken }),
            });
            if (res.status === 200) {
                const data = await res.json();
                renderVerdict(data);
                pushHistory(data);
            } else {
                renderFailure("요청 형식이 올바르지 않습니다. (" + res.status + ")");
            }
        } catch (e) {
            renderFailure("네트워크 오류로 검표하지 못했습니다.");
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
