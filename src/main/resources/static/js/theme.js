// 사이트 전체 다크/라이트 테마 토글 - 로그인 계정과 무관한 순수 UI 취향이라(sidebar.js의 사이드바
// 접힘 상태와 같은 이유) 서버가 아니라 브라우저 localStorage에 저장한다.
//
// 실제 테마 적용(css/style.css의 :root[data-theme])은 이 파일이 아니라 각 HTML <head> 안의 인라인
// <script>가 담당한다 - 화면이 라이트로 한번 그려졌다가 다크로 바뀌는 깜빡임(FOUC)을 막으려면 CSS가
// 파싱되기 전에 data-theme 속성이 이미 심어져 있어야 하는데, 이 파일처럼 </body> 직전에 로드되는
// 외부 스크립트로는 너무 늦다. 이 파일은 그 뒤에 이어서 두 UI를 서로 동기화한다: 설정 페이지의
// 토글 스위치(#dark-mode-toggle)와, 로그아웃 버튼 왼쪽의 아이콘 버튼(#theme-toggle-btn, 대시보드/
// 만다라트/리포트/설정 quote-bar) - 한쪽에서 바꾸면 같은 페이지에 둘 다 있어도 항상 같이 갱신된다.
const THEME_STORAGE_KEY = "sm_theme";

function currentEffectiveTheme() {
  const stored = localStorage.getItem(THEME_STORAGE_KEY);
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
  localStorage.setItem(THEME_STORAGE_KEY, theme);
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
