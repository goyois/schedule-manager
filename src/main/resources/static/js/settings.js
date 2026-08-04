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

// ---------- 왼쪽 메뉴바 - 패널 전환 ----------
// 항목이 늘어나도 data-settings-panel 값과 #settings-panel-{key} id만 맞추면 되도록 위임 처리한다

const settingsMenuEl = document.getElementById("settings-menu");
const settingsPanelEls = document.querySelectorAll(".settings-panel");

function showSettingsPanel(key) {
  settingsMenuEl.querySelectorAll(".settings-menu-item").forEach((btn) => {
    btn.classList.toggle("active", btn.dataset.settingsPanel === key);
  });
  settingsPanelEls.forEach((panel) => {
    panel.classList.toggle("active", panel.id === `settings-panel-${key}`);
  });
}

settingsMenuEl.addEventListener("click", (e) => {
  const btn = e.target.closest("button[data-settings-panel]");
  if (!btn) return;
  showSettingsPanel(btn.dataset.settingsPanel);
});

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
  showSettingsPanel("automation"); // 기본으로 열리는 패널

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
})();
