/*
 * 좌석맵 클라이언트 상태 관리.
 *
 * 확정된 전제 (openapi.yaml / concurrency-spec 0.4):
 *  - 좌석 선택 상태와 holdId는 클라이언트가 보관한다. 서버 세션을 쓰지 않는다.
 *  - 상태 변경 요청(hold/reservation)에는 Idempotency-Key(UUID)와 X-User-Id 헤더가 필수다.
 *  - 409는 정상 거절이다. 오류가 아니라 code로 사유를 구분해 사용자에게 보여준다.
 *  - 모든 시각은 서버 기준. 카운트다운은 hold 응답의 expiresInSeconds로 시작한다.
 *  - HELD status는 내 홀드/남 홀드를 구분하지 않는다. 내가 보관한 홀드 좌석 목록과 대조한다.
 *  - HELD_EXPIRED는 "홀드가 걸려 있으나 TTL이 지났다"는 사실이다(#157). 고를 수
 *    있게 두되 잡히는지는 서버가 정한다 — none에서는 409로 거절된다.
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

    // **만료된 홀드는 서버가 알려준다(#157).** 조회가 status='HELD'이면서
    // held_until이 지난 좌석을 HELD_EXPIRED로 계산해 내보낸다.
    //
    // 예전에는 이 자리에 expiredMine이 있었다 — 폴링 응답에 heldUntil이 없어
    // (openapi: 매초 바뀌면 ETag가 무의미해진다) 클라이언트가 **자기 홀드의
    // 만료만** 기억해 그 좌석을 다시 열어 주는 방식이었다(#90~#92).
    // **남의 만료 홀드는 끝내 회색으로 굳었다.** 서버가 남의 것까지 알려주는
    // 지금 그 덧칠은 덮이는 범위가 더 좁아 남길 이유가 없다 — 지웠다.
    // localStorage 키(holdfast:expired:*)도 함께 사라진다.

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

    /**
     * 저장된 홀드를 읽는다. 이미 만료됐으면 버린다.
     *
     * <p>그 좌석을 따로 기억하지 않는다 — 다음 폴링이 서버에서
     * {@code HELD_EXPIRED}를 받아 오고, 그 값이 내 홀드든 남의 홀드든 똑같이
     * 다시 고를 수 있게 만든다(#157).
     */
    function loadSavedHold() {
        try {
            const raw = localStorage.getItem(HOLD_STORAGE_KEY);
            if (!raw) return null;
            const parsed = JSON.parse(raw);
            if (!parsed || !parsed.holdId) {
                localStorage.removeItem(HOLD_STORAGE_KEY);
                return null;
            }
            if (parsed.expiresAt <= Date.now()) {
                // 페이지가 꺼져 있는 동안 만료된 경우. 좌석은 서버가
                // HELD_EXPIRED로 알려주므로 여기서 기억할 것이 없다.
                localStorage.removeItem(HOLD_STORAGE_KEY);
                return null;
            }
            return parsed;
        } catch (e) {
            return null;
        }
    }

    /*
     * **팔지 않는 회차에서는 아무것도 켜지 않는다**(이슈 #108).
     *
     * 서버가 SaleState를 data-sale-state로 내려 준다. 이 값이 ON_SALE이 아니면
     * 오픈 전·매진·종료 중 하나이고, 홀드는 어차피 서버가 거절한다
     * (RESERVATION_NOT_OPEN 등, REQ-08). 화면에서 미리 막는 것은 사용자가 좌석을
     * 고르고 버튼을 눌러야 그 사실을 알게 되는 것을 없애려는 것이다.
     *
     * **막는 것이 판정은 아니다.** 이 플래그를 지워도 서버가 다시 본다.
     */
    const onSale = document.querySelector(".seatmap").dataset.saleState === "ON_SALE";

    // 최초 렌더는 좌석 상태만 보고 그려지므로(grid.html), 팔지 않는 회차에서도
    // AVAILABLE 좌석이 파랗게 눌릴 것처럼 보인다. 클릭은 isSelectable이 막지만
    // **눌리는 줄 알고 누르게 두지 않는다** — 첫 폴링을 기다리지 않고 여기서 끈다.
    if (!onSale) {
        document.querySelectorAll(".seat").forEach((el) => { el.disabled = true; });
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
        holdBtn.disabled = ids.length === 0 || !onSale;
    }

    // --- 서버 status fragment 교체 후, 내 선택/내 홀드 표시를 다시 덧입힌다 ---

    /**
     * 이 좌석을 고를 수 있는가. **만료된 홀드가 걸린 좌석도 포함한다** —
     * 내 것이든 남의 것이든 같다(#157).
     *
     * **화면은 전략을 모른다.** HELD_EXPIRED를 고를 수 있게 두는 것은 "잡힌다"는
     * 뜻이 아니라 "시도할 수 있다"는 뜻이다. 네 전략은 홀드 경로가 만료를
     * 인수하고(erd.md 4.1), none은 회수 경로가 없어 409로 거절한다. 어느 쪽인지는
     * **서버의 응답이 말하고**, 이 파일 머리말대로 409는 오류가 아니라 사유와
     * 함께 화면에 뜬다. 막아 두면 넷에서 잡을 수 있는 좌석을 못 잡는다.
     */
    function isSelectable(el) {
        if (!el) return false;
        if (!onSale) return false;
        const st = el.dataset.status;
        return st === "AVAILABLE" || st === "HELD_EXPIRED";
    }

    function applyOverlay() {
        const g = grid();
        if (!g) return;

        // 남이 가져가 더 이상 고를 수 없게 된 좌석은 선택에서 제거
        for (const id of [...selected]) {
            if (!isSelectable(seatEl(id))) {
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
    const STATUS_CLASS = {
        AVAILABLE: "is-available",
        HELD: "is-held",
        HELD_EXPIRED: "is-held-expired",   // 홀드가 걸려 있으나 TTL이 지났다(#157)
        SOLD: "is-sold",
    };

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
            el.classList.remove("is-available", "is-held", "is-held-expired", "is-sold");
            el.classList.add(STATUS_CLASS[status] || "is-held");
            // 고를 수 있는 상태와 disabled 판정을 한 곳에서 쓴다 — 둘이 갈리면
            // 누를 수 없는데 선택되거나 그 반대가 된다.
            el.disabled = !isSelectable(el);
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
            // 좌석을 기억하지 않는다. 다음 폴링(최대 3초)이 서버에서
            // HELD_EXPIRED를 받아 와 다시 고를 수 있게 만든다(#157).
            showMessage("선점이 만료되었습니다. 같은 좌석을 다시 선택할 수 있습니다.", "warn");
            leaveHold();
            return;
        }
        const s = Math.floor(remainMs / 1000);
        const mm = String(Math.floor(s / 60)).padStart(2, "0");
        const ss = String(s % 60).padStart(2, "0");
        countdownEl.textContent = mm + ":" + ss;
    }

    // --- 해제 (DELETE /api/holds/{holdId}) · 결제 (POST /api/payments) ---
    //
    // 확정은 결제를 거친다(M4 최소 완결 흐름: 선택→선점→확정→결제→QR→검표,
    // 이슈 #81). POST /api/reservations를 직접 부르지 않는다 — 그 경로는
    // k6 측정 하네스가 쓰는 경로로 그대로 남겨둔다(#79 PaymentService 문서).
    // 결제가 승인되면 그 안에서 확정과 티켓 발급까지 한 트랜잭션에 끝난다.

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
        showMessage("결제 처리 중…");
        try {
            const res = await fetch("/api/payments", {
                method: "POST",
                headers: stateHeaders(newIdempotencyKey()),
                body: JSON.stringify({ holdId: hold.holdId }),
            });
            if (res.status === 201) {
                const data = await res.json();
                if (data.status === "APPROVED") {
                    // 결제 승인 시 확정·티켓 발급까지 이미 끝났다. 예약 확인
                    // 화면(#81)으로 이동해 티켓을 보여준다.
                    //
                    // **userId를 함께 싣는다.** 예약 확인 화면은 소유자의 예약만
                    // 보여주므로(ReservationService#get), 이 값이 빠지면 서버
                    // 기본값 1로 열려 사용자 2로 예매한 사람이 자기 티켓 앞에서
                    // 404를 만난다. 홀드·결제 요청은 이미 같은 값을 X-User-Id로
                    // 보내고 있었다 — 들고 있으면서 안 넘긴 것이었다 (#126).
                    clearSavedHold();
                    window.location.href =
                        "/reservations/" + data.reservationId + "?userId=" + userId;
                    return;
                }
                // DECLINED — 예약은 HELD로 남는다. 홀드는 그대로 유지되므로
                // 사용자가 다시 결제를 시도하거나 선점을 해제할 수 있다.
                showMessage("결제가 거절되었습니다. 다시 시도하거나 선점을 해제하세요.", "warn");
                confirmBtn.disabled = false;
            } else if (res.status === 409) {
                const problem = await res.json();
                showMessage((problem && problem.detail) || "결제할 수 없습니다.", "warn");
                confirmBtn.disabled = false;
            } else {
                showMessage("결제에 실패했습니다. (" + res.status + ")", "warn");
                confirmBtn.disabled = false;
            }
        } catch (e) {
            showMessage("네트워크 오류로 결제하지 못했습니다.", "warn");
            confirmBtn.disabled = false;
        }
    }

    // --- 이벤트 배선 ---

    // 좌석 클릭은 위임으로 처리 (격자는 고정, 상태만 폴링으로 patch됨)
    document.addEventListener("click", (e) => {
        const btn = e.target.closest(".seat");
        if (!btn || !grid() || !grid().contains(btn)) return;
        // HELD/SOLD는 무시한다. 단 HELD_EXPIRED는 고를 수 있다 — isSelectable 참고.
        if (!isSelectable(btn)) return;
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
