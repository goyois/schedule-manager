// 계정별 다크/라이트 테마 토글 - 서버 컬럼 없이 브라우저 localStorage에 저장하되, 키에 로그인
// 계정(email)을 넣어 계정별로 분리한다. 예전엔 "sm_theme" 단일 키를 썼는데, 그러면 같은 브라우저에서
// 여러 계정을 번갈아 로그인하는 경우(테스트 계정, 공용 PC 등) 한 계정에서 바꾼 테마가 그대로 다른
// 계정에도 적용되는 것처럼 보였다 - localStorage는 브라우저(오리진) 단위지 계정 단위가 아니기 때문.
// 로그인 전(로그인/회원가입 페이지)에는 계정을 모르므로 "sm_theme_anonymous" 하나를 공유해서 쓴다.
//
// 실제 테마 적용(css/style.css의 :root[data-theme])은 이 파일이 아니라 각 HTML <head> 안의 인라인
// <script>가 담당한다 - 화면이 라이트로 한번 그려졌다가 다크로 바뀌는 깜빡임(FOUC)을 막으려면 CSS가
// 파싱되기 전에 data-theme 속성이 이미 심어져 있어야 하는데, 이 파일처럼 </body> 직전에 로드되는
// 외부 스크립트로는 너무 늦다. 그 인라인 스크립트는 api.js보다도 먼저 실행되므로 계정별 키를 만드는
// 로직(currentUserThemeKey와 동일한 내용)을 각 HTML에 그대로 복제해뒀다 - 한쪽만 고치고 다른 쪽을
// 놓치지 않도록, 이 두 곳의 로직을 바꿀 땐 항상 같이 맞춰야 한다.
// 이 파일은 그 뒤에 이어서 두 UI를 서로 동기화한다: 설정 페이지의 토글 스위치(#dark-mode-toggle)와,
// 로그아웃 버튼 왼쪽의 아이콘 버튼(#theme-toggle-btn, 대시보드/만다라트/리포트/설정 quote-bar) -
// 한쪽에서 바꾸면 같은 페이지에 둘 다 있어도 항상 같이 갱신된다.
const THEME_STORAGE_KEY_PREFIX = "sm_theme_";
const USER_STORAGE_KEY = "sm_current_user"; // api.js의 USER_KEY와 같은 값

function currentUserThemeKey() {
  try {
    const raw = localStorage.getItem(USER_STORAGE_KEY);
    const user = raw ? JSON.parse(raw) : null;
    const identifier = user && (user.email || user.id);
    return THEME_STORAGE_KEY_PREFIX + (identifier || "anonymous");
  } catch (e) {
    return THEME_STORAGE_KEY_PREFIX + "anonymous";
  }
}

function currentEffectiveTheme() {
  const stored = localStorage.getItem(currentUserThemeKey());
  if (stored === "dark" || stored === "light") return stored;
  return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

const THEME_ICON_SUN = `
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
    <circle cx="12" cy="12" r="4.2"></circle>
    <line x1="12" y1="2.5" x2="12" y2="5"></line>
    <line x1="12" y1="19" x2="12" y2="21.5"></line>
    <line x1="4.2" y1="4.2" x2="5.9" y2="5.9"></line>
    <line x1="18.1" y1="18.1" x2="19.8" y2="19.8"></line>
    <line x1="2.5" y1="12" x2="5" y2="12"></line>
    <line x1="19" y1="12" x2="21.5" y2="12"></line>
    <line x1="4.2" y1="19.8" x2="5.9" y2="18.1"></line>
    <line x1="18.1" y1="5.9" x2="19.8" y2="4.2"></line>
  </svg>`;

const THEME_ICON_MOON = `
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
    <path d="M20 14.5A8.5 8.5 0 1 1 9.5 4a6.8 6.8 0 0 0 10.5 10.5Z"></path>
  </svg>`;

// 지금 라이트면 "다크로 바꾸는" 해 아이콘을, 지금 다크면 "라이트로 바꾸는" 달 아이콘을 보여준다 -
// 아이콘이 "지금 상태"가 아니라 "누르면 이렇게 됨"을 나타내는 쪽(title 텍스트와 짝을 맞춤)
function updateThemeUi(theme) {
  const isDark = theme === "dark";

  const toggle = document.getElementById("dark-mode-toggle");
  if (toggle) toggle.checked = isDark;

  const label = isDark ? "라이트 모드로 전환" : "다크 모드로 전환";
  document.querySelectorAll("#theme-toggle-btn").forEach((btn) => {
    btn.innerHTML = isDark ? THEME_ICON_SUN : THEME_ICON_MOON;
    btn.title = label;
    btn.setAttribute("aria-label", label);
  });
}

function applyTheme(theme) {
  document.documentElement.setAttribute("data-theme", theme);
  localStorage.setItem(currentUserThemeKey(), theme);
  updateThemeUi(theme);
}

document.addEventListener("DOMContentLoaded", () => {
  updateThemeUi(currentEffectiveTheme());

  const toggle = document.getElementById("dark-mode-toggle");
  if (toggle) {
    toggle.addEventListener("change", () => {
      applyTheme(toggle.checked ? "dark" : "light");
    });
  }

  document.querySelectorAll("#theme-toggle-btn").forEach((btn) => {
    btn.addEventListener("click", () => {
      applyTheme(currentEffectiveTheme() === "dark" ? "light" : "dark");
    });
  });
});
