/*
 * 좌석맵 클라이언트 상태 관리.
 *
 * 확정된 전제 (openapi.yaml / concurrency-spec 0.4):
 *  - 좌석 선택 상태와 holdId는 클라이언트가 보관한다. 서버 세션을 쓰지 않는다.
 *  - 상태 변경 요청(hold/reservation)에는 Idempotency-Key(UUID)와 X-User-Id 헤더가 필수다.
 *  - 409는 정상 거절이다. 오류가 아니라 code로 사유를 구분해 사용자에게 보여준다.
 *  - 모든 시각은 서버 기준. 카운트다운은 hold 응답의 expiresInSeconds로 시작한다.
 *  - HELD status는 내 홀드/남 홀드를 구분하지 않는다. 내가 보관한 홀드 좌석 목록과 대조한다.
 */
(function () {
    "use strict";

    const root = document.querySelector(".seatmap");
    if (!root) return;

    const sessionId = Number(root.dataset.sessionId);
    const maxPerUser = Number(root.dataset.maxPerUser);
    const userId = root.dataset.userId;

    // 클라이언트 보관 상태
    const selected = new Set();              // 선점 전 선택한 seatId
    let hold = null;                         // { holdId, seatIds:Set<number>, expiresAt:number(ms) }
    let countdownTimer = null;
    let lastEtag = null;                     // 폴링 조건부 요청용

    // openapi: "holdId는 클라이언트가 보관한다"는 페이지 새로고침에도 유효해야
    // 한다. 서버 세션이 아니라 이 브라우저의 localStorage에 두므로 concurrency-spec
    // 0.4(서버 세션 금지)와 충돌하지 않는다.
    const HOLD_STORAGE_KEY = "holdfast:hold:" + sessionId;

    function saveHold() {
        try {
            localStorage.setItem(HOLD_STORAGE_KEY, JSON.stringify({
                holdId: hold.holdId,
                seatIds: [...hold.seatIds],
                expiresAt: hold.expiresAt,
            }));
        } catch (e) { /* 저장 실패해도 화면 동작에는 지장 없음 */ }
    }

    function clearSavedHold() {
        try { localStorage.removeItem(HOLD_STORAGE_KEY); } catch (e) { /* no-op */ }
    }

    function loadSavedHold() {
        try {
            const raw = localStorage.getItem(HOLD_STORAGE_KEY);
            if (!raw) return null;
            const parsed = JSON.parse(raw);
            if (!parsed || !parsed.holdId || parsed.expiresAt <= Date.now()) {
                localStorage.removeItem(HOLD_STORAGE_KEY);
                return null;
            }
            return parsed;
        } catch (e) {
            return null;
        }
    }

    const grid = () => document.getElementById("seat-grid");
    const selectedList = document.getElementById("selected-list");
    const heldList = document.getElementById("held-list");
    const holdBtn = document.getElementById("hold-btn");
    const confirmBtn = document.getElementById("confirm-btn");
    const releaseBtn = document.getElementById("release-btn");
    const selectionPanel = document.getElementById("selection-panel");
    const holdPanel = document.getElementById("hold-panel");
    const countdownEl = document.getElementById("countdown");
    const messageEl = document.getElementById("message");

    // --- 유틸 ---

    function newIdempotencyKey() {
        // 사용자의 새 클릭 = 새 키. 네트워크 재시도(자동)라면 같은 키를 재사용해야 하지만,
        // 이 화면은 자동 재시도를 하지 않으므로 클릭마다 새 키를 만든다 (openapi Idempotency-Key).
        return crypto.randomUUID();
    }

    function stateHeaders(key) {
        return {
            "Content-Type": "application/json",
            "Idempotency-Key": key,
            "X-User-Id": userId,
        };
    }

    function showMessage(text, kind) {
        messageEl.textContent = text || "";
        messageEl.className = "message" + (kind ? " " + kind : "");
    }

    function seatEl(seatId) {
        return grid() ? grid().querySelector('.seat[data-seat-id="' + seatId + '"]') : null;
    }

    function seatNoOf(seatId) {
        const el = seatEl(seatId);
        return el ? el.dataset.seatNo : String(seatId);
    }

    // --- 선택 (선점 전) ---

    function toggleSelect(seatId) {
        if (hold) return; // 선점 중에는 새 선택 불가
        if (selected.has(seatId)) {
            selected.delete(seatId);
        } else {
            if (selected.size >= maxPerUser) {
                showMessage("1인 최대 " + maxPerUser + "매까지 선택할 수 있습니다.", "warn");
                return;
            }
            selected.add(seatId);
        }
        showMessage("");
        renderSelection();
        applyOverlay();
    }

    function renderSelection() {
        const ids = [...selected].sort((a, b) => a - b);
        if (ids.length === 0) {
            selectedList.innerHTML = '<li class="empty">좌석을 선택하세요</li>';
        } else {
            selectedList.innerHTML = ids
                .map((id) => "<li>" + seatNoOf(id) + "</li>")
                .join("");
        }
        holdBtn.disabled = ids.length === 0;
    }

    // --- 서버 status fragment 교체 후, 내 선택/내 홀드 표시를 다시 덧입힌다 ---

    function applyOverlay() {
        const g = grid();
        if (!g) return;

        // 남이 가져가 더 이상 AVAILABLE이 아닌 선택 좌석은 선택에서 제거
        for (const id of [...selected]) {
            const el = seatEl(id);
            if (!el || el.dataset.status !== "AVAILABLE") {
                selected.delete(id);
            }
        }
        g.querySelectorAll(".seat").forEach((el) => {
            const id = Number(el.dataset.seatId);
            el.classList.toggle("is-selected", selected.has(id));
            el.classList.toggle("is-held-mine", !!hold && hold.seatIds.has(id));
        });
        renderSelection();
    }

    // --- 3초 폴링 반영 ---
    //
    // status-feed(hidden)가 받는 것은 seat/조회 API(#37) 담당 fragment
    // (fragments/seat-status.html)다. seatId+status만 있는 <li> 목록이며 좌석
    // 위치·번호는 없다(openapi: 폴링 응답은 6필드 중 2필드만). 격자를 통째로
    // 다시 그리지 않고, 이미 렌더된 좌석 버튼의 상태만 여기서 patch한다.
    const STATUS_CLASS = { AVAILABLE: "is-available", HELD: "is-held", SOLD: "is-sold" };

    function applyStatusFeed() {
        const feed = document.getElementById("status-feed");
        const g = grid();
        if (!feed || !g) return;
        feed.querySelectorAll("li[data-seat-id]").forEach((li) => {
            const id = li.dataset.seatId;
            const match = li.className.match(/status-(\w+)/);
            if (!match) return;
            const status = match[1];
            const el = seatEl(id);
            if (!el) return;
            el.dataset.status = status;
            el.classList.remove("is-available", "is-held", "is-sold");
            el.classList.add(STATUS_CLASS[status] || "is-held");
            el.disabled = status !== "AVAILABLE";
        });
        applyOverlay();
    }

    // --- 선점 (POST /api/holds) ---

    async function requestHold() {
        const seatIds = [...selected].sort((a, b) => a - b);
        if (seatIds.length === 0) return;
        holdBtn.disabled = true;
        showMessage("선점 요청 중…");
        try {
            const res = await fetch("/api/holds", {
                method: "POST",
                headers: stateHeaders(newIdempotencyKey()),
                body: JSON.stringify({ sessionId: sessionId, seatIds: seatIds }),
            });
            if (res.status === 201) {
                const data = await res.json();
                enterHold(data);
                showMessage("좌석을 선점했습니다. 시간 내 확정하세요.", "ok");
            } else if (res.status === 409) {
                const problem = await res.json();
                showMessage(conflictMessage(problem), "warn");
                holdBtn.disabled = false;
            } else {
                showMessage("선점에 실패했습니다. (" + res.status + ")", "warn");
                holdBtn.disabled = false;
            }
        } catch (e) {
            showMessage("네트워크 오류로 선점하지 못했습니다.", "warn");
            holdBtn.disabled = false;
        }
    }

    function conflictMessage(problem) {
        // 409는 정상 거절. detail을 그대로 보여주고, 좌석별 사유가 있으면 덧붙인다.
        let msg = (problem && problem.detail) || "선점할 수 없습니다.";
        if (problem && Array.isArray(problem.conflicts) && problem.conflicts.length) {
            const parts = problem.conflicts.map((c) => seatNoOf(c.seatId) + "(" + c.code + ")");
            msg += " — " + parts.join(", ");
        }
        return msg;
    }

    function showHoldPanel() {
        selected.clear();
        selectionPanel.hidden = true;
        holdPanel.hidden = false;
        heldList.innerHTML = [...hold.seatIds]
            .sort((a, b) => a - b)
            .map((id) => "<li>" + seatNoOf(id) + "</li>")
            .join("");
        startCountdown();
        applyOverlay();
    }

    function enterHold(data) {
        hold = {
            holdId: data.holdId,
            seatIds: new Set(data.seatIds),
            expiresAt: Date.now() + Number(data.expiresInSeconds) * 1000,
        };
        saveHold();
        showHoldPanel();
    }

    // 새로고침 직후 호출: 만료 전 홀드가 저장돼 있으면 그대로 이어받는다.
    // enterHold와 달리 이미 보관된 값을 다시 저장할 필요는 없다.
    function restoreHold(saved) {
        hold = {
            holdId: saved.holdId,
            seatIds: new Set(saved.seatIds),
            expiresAt: saved.expiresAt,
        };
        showHoldPanel();
    }

    function leaveHold() {
        hold = null;
        clearSavedHold();
        stopCountdown();
        holdPanel.hidden = true;
        selectionPanel.hidden = false;
        renderSelection();
        applyOverlay();
    }

    // --- 카운트다운 (서버 기준 만료) ---

    function startCountdown() {
        stopCountdown();
        tickCountdown();
        countdownTimer = setInterval(tickCountdown, 1000);
    }

    function stopCountdown() {
        if (countdownTimer) clearInterval(countdownTimer);
        countdownTimer = null;
    }

    function tickCountdown() {
        if (!hold) return;
        const remainMs = hold.expiresAt - Date.now();
        if (remainMs <= 0) {
            countdownEl.textContent = "00:00";
            showMessage("선점이 만료되었습니다.", "warn");
            leaveHold();
            return;
        }
        const s = Math.floor(remainMs / 1000);
        const mm = String(Math.floor(s / 60)).padStart(2, "0");
        const ss = String(s % 60).padStart(2, "0");
        countdownEl.textContent = mm + ":" + ss;
    }

    // --- 해제 (DELETE /api/holds/{holdId}) · 확정 (POST /api/reservations) ---

    async function releaseHold() {
        if (!hold) return;
        releaseBtn.disabled = true;
        try {
            const res = await fetch("/api/holds/" + hold.holdId, {
                method: "DELETE",
                headers: stateHeaders(newIdempotencyKey()),
            });
            if (res.status === 204) {
                showMessage("선점을 해제했습니다.", "ok");
                leaveHold();
            } else {
                showMessage("해제에 실패했습니다. (" + res.status + ")", "warn");
            }
        } catch (e) {
            showMessage("네트워크 오류로 해제하지 못했습니다.", "warn");
        } finally {
            releaseBtn.disabled = false;
        }
    }

    async function confirmReservation() {
        if (!hold) return;
        confirmBtn.disabled = true;
        showMessage("예약 확정 중…");
        try {
            const res = await fetch("/api/reservations", {
                method: "POST",
                headers: stateHeaders(newIdempotencyKey()),
                body: JSON.stringify({ holdId: hold.holdId }),
            });
            if (res.status === 201) {
                const data = await res.json();
                showMessage("예약이 확정되었습니다. 예약번호 " + data.reservationId, "ok");
                leaveHold();
            } else if (res.status === 409) {
                const problem = await res.json();
                showMessage((problem && problem.detail) || "확정할 수 없습니다.", "warn");
                confirmBtn.disabled = false;
            } else {
                showMessage("확정에 실패했습니다. (" + res.status + ")", "warn");
                confirmBtn.disabled = false;
            }
        } catch (e) {
            showMessage("네트워크 오류로 확정하지 못했습니다.", "warn");
            confirmBtn.disabled = false;
        }
    }

    // --- 이벤트 배선 ---

    // 좌석 클릭은 위임으로 처리 (격자는 고정, 상태만 폴링으로 patch됨)
    document.addEventListener("click", (e) => {
        const btn = e.target.closest(".seat");
        if (!btn || !grid() || !grid().contains(btn)) return;
        if (btn.dataset.status !== "AVAILABLE") return; // HELD/SOLD 무시
        toggleSelect(Number(btn.dataset.seatId));
    });

    holdBtn.addEventListener("click", requestHold);
    releaseBtn.addEventListener("click", releaseHold);
    confirmBtn.addEventListener("click", confirmReservation);

    // htmx 폴링(#status-feed 대상): ETag/If-None-Match → 304 no-op,
    // 200이면 fragment를 받아 좌석 상태를 patch한다.
    document.body.addEventListener("htmx:configRequest", (e) => {
        if (e.detail.elt.id !== "status-feed") return;
        if (lastEtag) e.detail.headers["If-None-Match"] = lastEtag;
    });
    document.body.addEventListener("htmx:beforeSwap", (e) => {
        if (e.detail.target.id !== "status-feed") return;
        if (e.detail.xhr && e.detail.xhr.status === 304) {
            e.detail.shouldSwap = false; // 변경 없음: 이전 상태 유지
        }
    });
    document.body.addEventListener("htmx:afterRequest", (e) => {
        if (e.detail.elt.id !== "status-feed") return;
        const etag = e.detail.xhr && e.detail.xhr.getResponseHeader && e.detail.xhr.getResponseHeader("ETag");
        if (etag) lastEtag = etag;
    });
    document.body.addEventListener("htmx:afterSwap", (e) => {
        if (e.detail.target.id !== "status-feed") return;
        applyStatusFeed();
    });

    // 만료 전 홀드가 저장돼 있으면 새로고침 후에도 이어받는다.
    const saved = loadSavedHold();
    if (saved) {
        restoreHold(saved);
    } else {
        // 최초 렌더 반영
        applyOverlay();
    }
})();
