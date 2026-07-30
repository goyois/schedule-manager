// 좌측 카테고리 사이드바 접기/펼치기: .sidebar가 있는 페이지(대시보드/만다라트/설정)에서 공통으로
// 쓴다. 페이지 이동 후에도 상태가 유지되도록 브라우저 localStorage에 저장해두고(로그인 계정과 무관한
// 순수 레이아웃 취향이라 이메일별로 나눌 필요는 없다), 다음에 페이지를 열 때 그대로 복원한다.
const SIDEBAR_COLLAPSED_KEY = "sidebar-collapsed";
// .sidebar의 width 트랜지션(css style.css) 시간과 맞춰둔다 - 트랜지션이 끝나기 전에 두 번째
// 클릭(더블클릭, 트랙패드 중복 입력 등)이 들어오면 "아직 애니메이션 중인" classList 상태를 기준으로
// next를 계산해 펼쳐지다 말고 곧바로 다시 접히는 것처럼 보이는 문제가 있었다 - 트랜지션이 끝날
// 때까지 재클릭을 무시해 한 번의 물리적 더블클릭이 두 번 토글되지 않게 한다
const SIDEBAR_TRANSITION_MS = 220;

function applySidebarCollapsed(appShell, toggleBtn, collapsed) {
  appShell.classList.toggle("sidebar-collapsed", collapsed);
  toggleBtn.title = collapsed ? "사이드바 펼치기" : "사이드바 접기";
}

(function initSidebarToggle() {
  const appShell = document.querySelector(".app-shell");
  const toggleBtn = document.getElementById("sidebar-toggle-btn");
  if (!appShell || !toggleBtn) return;

  const collapsed = localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === "true";
  applySidebarCollapsed(appShell, toggleBtn, collapsed);

  let transitioning = false;
  toggleBtn.addEventListener("click", () => {
    if (transitioning) return;
    transitioning = true;
    setTimeout(() => {
      transitioning = false;
    }, SIDEBAR_TRANSITION_MS);

    const next = !appShell.classList.contains("sidebar-collapsed");
    applySidebarCollapsed(appShell, toggleBtn, next);
    localStorage.setItem(SIDEBAR_COLLAPSED_KEY, String(next));
  });
})();
