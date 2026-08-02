// 사이트 전체 다크/라이트 테마 토글 - 로그인 계정과 무관한 순수 UI 취향이라(sidebar.js의 사이드바
// 접힘 상태와 같은 이유) 서버가 아니라 브라우저 localStorage에 저장한다.
//
// 실제 테마 적용(css/style.css의 :root[data-theme])은 이 파일이 아니라 각 HTML <head> 안의 인라인
// <script>가 담당한다 - 화면이 라이트로 한번 그려졌다가 다크로 바뀌는 깜빡임(FOUC)을 막으려면 CSS가
// 파싱되기 전에 data-theme 속성이 이미 심어져 있어야 하는데, 이 파일처럼 </body> 직전에 로드되는
// 외부 스크립트로는 너무 늦다. 이 파일은 그 뒤에 이어서 설정 페이지의 토글 스위치(#dark-mode-toggle,
// settings.html) 상태 동기화만 담당한다 - 처음엔 quote-bar에 아이콘 버튼으로 넣었지만, 페이지마다
// 메뉴 아이콘이 늘어나는 걸 원치 않는다는 피드백을 받아 다른 자동화 설정들과 같은 자리(설정 페이지의
// 토글 스위치)로 옮겼다.
const THEME_STORAGE_KEY = "sm_theme";

function currentEffectiveTheme() {
  const stored = localStorage.getItem(THEME_STORAGE_KEY);
  if (stored === "dark" || stored === "light") return stored;
  return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

function applyTheme(theme) {
  document.documentElement.setAttribute("data-theme", theme);
  localStorage.setItem(THEME_STORAGE_KEY, theme);
  const toggle = document.getElementById("dark-mode-toggle");
  if (toggle) toggle.checked = theme === "dark";
}

document.addEventListener("DOMContentLoaded", () => {
  const toggle = document.getElementById("dark-mode-toggle");
  if (!toggle) return;
  toggle.checked = currentEffectiveTheme() === "dark";
  toggle.addEventListener("change", () => {
    applyTheme(toggle.checked ? "dark" : "light");
  });
});
