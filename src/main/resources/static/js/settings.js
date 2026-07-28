const toast = document.getElementById("toast");

function showToast(message) {
  toast.textContent = message;
  toast.classList.add("show");
  setTimeout(() => toast.classList.remove("show"), 2600);
}

function requireAuth() {
  if (!API.getToken()) {
    window.location.href = "/login";
    return false;
  }
  return true;
}

function renderUserChip() {
  const user = API.getCurrentUser();
  const email = (user && user.email) || "-";
  const initial = email !== "-" ? email[0].toUpperCase() : "?";
  document.getElementById("user-avatar").textContent = initial;
  document.getElementById("user-name").textContent = (user && user.email) || "사용자";
  document.getElementById("user-email").textContent = email;
}

// 두 토글 모두 같은 모양이라(체크박스 하나 + 저장 API 하나) 공용 헬퍼로 묶는다.
// 저장 실패 시 체크박스를 원래 값으로 되돌리는 것까지 동일하게 처리한다
function bindSettingToggle(checkboxEl, { getValue, path, applyResponse }) {
  checkboxEl.addEventListener("change", async () => {
    const enabled = checkboxEl.checked;
    try {
      const updated = await API.put(path, { enabled });
      const current = API.getCurrentUser() || {};
      API.setCurrentUser(Object.assign({}, current, applyResponse(updated)));
      showToast("설정을 저장했습니다.");
    } catch (err) {
      checkboxEl.checked = !enabled; // 저장 실패 시 토글을 원래 상태로 되돌린다
      showToast(`설정을 저장하지 못했습니다. ${err.message}`);
    }
  });
}

const autoStatusToggleEl = document.getElementById("auto-status-toggle");
bindSettingToggle(autoStatusToggleEl, {
  path: "/api/users/me/auto-status-mode",
  applyResponse: (updated) => ({ autoStatusMode: updated.autoStatusMode }),
});

const aiAutoRegisterToggleEl = document.getElementById("ai-auto-register-toggle");
bindSettingToggle(aiAutoRegisterToggleEl, {
  path: "/api/users/me/ai-auto-register",
  applyResponse: (updated) => ({ aiAutoRegisterEnabled: updated.aiAutoRegisterEnabled }),
});

// ---------- 반복 일정 관리 ----------

function escapeHtml(str) {
  return String(str ?? "").replace(/[&<>"']/g, (m) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  }[m]));
}

const WEEKDAY_ORDER = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"];
const WEEKDAY_LABELS = { MONDAY: "월", TUESDAY: "화", WEDNESDAY: "수", THURSDAY: "목", FRIDAY: "금", SATURDAY: "토", SUNDAY: "일" };

function formatDays(days) {
  if (days.length === 7) return "매일";
  return WEEKDAY_ORDER.filter((d) => days.includes(d)).map((d) => WEEKDAY_LABELS[d]).join(", ");
}

function formatTimeRange(startTime, endTime) {
  const short = (t) => t.slice(0, 5); // "HH:mm:ss" -> "HH:mm"
  return endTime ? `${short(startTime)} ~ ${short(endTime)}` : short(startTime);
}

const recurringScheduleListEl = document.getElementById("recurring-schedule-list");

function renderRecurringSchedules(rules) {
  if (rules.length === 0) {
    recurringScheduleListEl.innerHTML = `<li class="recurring-schedule-empty">등록된 반복 일정이 없습니다.</li>`;
    return;
  }
  recurringScheduleListEl.innerHTML = rules.map((r) => `
    <li class="recurring-schedule-item">
      <div>
        <div class="recurring-schedule-title">${escapeHtml(r.title)}</div>
        <div class="recurring-schedule-meta">${escapeHtml(formatDays(r.daysOfWeek))} · ${escapeHtml(formatTimeRange(r.startTime, r.endTime))} · ${escapeHtml(r.categoryName)}</div>
      </div>
      <button type="button" class="btn btn-ghost btn-sm" style="width: auto" data-delete-recurring="${r.id}">중단</button>
    </li>
  `).join("");
}

async function loadRecurringSchedules() {
  try {
    const rules = await API.get("/api/recurring-schedules");
    renderRecurringSchedules(rules);
  } catch (err) {
    recurringScheduleListEl.innerHTML = `<li class="recurring-schedule-empty">반복 일정을 불러오지 못했습니다. ${escapeHtml(err.message)}</li>`;
  }
}

recurringScheduleListEl.addEventListener("click", async (e) => {
  const btn = e.target.closest("button[data-delete-recurring]");
  if (!btn) return;
  if (!confirm("이 반복 일정을 중단할까요? 아직 지나지 않은 일정만 정리되고, 이미 지난/진행 중인 기록은 남습니다.")) return;

  btn.disabled = true;
  try {
    await API.del(`/api/recurring-schedules/${btn.dataset.deleteRecurring}`);
    showToast("반복 일정을 중단했습니다.");
    await loadRecurringSchedules();
  } catch (err) {
    showToast(`반복 일정 중단에 실패했습니다. ${err.message}`);
    btn.disabled = false;
  }
});

document.getElementById("logout-btn").addEventListener("click", async () => {
  try {
    await API.post("/api/auth/logout", {});
  } catch (e) {
    // 로그아웃 API 실패해도 로컬 세션은 정리하고 로그인 화면으로 보낸다
  }
  API.clearSession();
  window.location.href = "/login";
});

(async function init() {
  if (!requireAuth()) return;
  renderUserChip();

  try {
    const me = await API.get("/api/users/me");
    const current = API.getCurrentUser() || {};
    API.setCurrentUser(Object.assign({}, current, {
      id: me.id,
      email: me.email,
      userType: me.userType,
      autoStatusMode: me.autoStatusMode,
      aiAutoRegisterEnabled: me.aiAutoRegisterEnabled,
    }));
    autoStatusToggleEl.checked = !!me.autoStatusMode;
    aiAutoRegisterToggleEl.checked = !!me.aiAutoRegisterEnabled;
  } catch (err) {
    showToast(`설정을 불러오지 못했습니다. ${err.message}`);
  }

  await loadRecurringSchedules();
})();
