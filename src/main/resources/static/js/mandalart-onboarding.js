// 만다라트 페이지 가이드 투어 - 계정별로 첫 방문 때 한 번, 사용법을 스포트라이트 + 말풍선으로
// 짧게 소개한다(엔진은 js/tour.js). 대시보드 투어(js/onboarding.js)와 저장 방식은 같지만, 서로 다른
// storageKeyPrefix를 쓰므로 "대시보드는 봤지만 만다라트는 아직 안 봄" 같은 상태도 독립적으로 기억한다.
(function () {
  // mandalart.js의 renderGrid()가 각 칸에 data-row/data-col을 심어둔다 - 정중앙(4,4)을 둘러싼
  // 3x3 중앙 블록(핵심 목표 1칸 + 세부 목표 8칸) 9칸의 셀렉터를 만들어, 이 9칸만 파스텔 초록으로
  // 콕 집어 강조하는 데 쓴다(step.highlightTargets, tour.js)
  const CENTER_BLOCK_SELECTORS = [];
  for (let row = 3; row <= 5; row++) {
    for (let col = 3; col <= 5; col++) {
      CENTER_BLOCK_SELECTORS.push(`#mandalart-grid [data-row="${row}"][data-col="${col}"]`);
    }
  }

  const TOUR_STEPS = [
    {
      target: "#board-list",
      title: "내 만다라트 목록",
      text: "만든 만다라트가 여기 모여요. '적용'을 누르면 AI가 새 일정을 추천할 때 그 목표를 참고해요.",
      placement: "right",
    },
    {
      target: "#add-board-form",
      title: "새 만다라트 만들기",
      text: "제목을 입력하고 추가하면 9x9 빈 격자가 만들어져요.",
      placement: "right",
    },
    {
      target: "#mandalart-grid",
      title: "핵심 목표부터 채우기",
      text: "정중앙 칸이 핵심 목표, 그 주변 8칸이 세부 목표예요. 칸을 눌러 내용을 입력해보세요.",
      placement: "top",
      highlightTargets: CENTER_BLOCK_SELECTORS,
    },
    {
      target: "#ai-fill-board-btn",
      title: "AI로 나머지 채우기",
      text: "중앙 9칸(핵심 목표 + 세부목표)을 채운 뒤 누르면, 나머지 실행 항목 64칸을 AI가 자동으로 채워줘요.",
      placement: "bottom",
    },
  ];

  const tour = createSpotlightTour(TOUR_STEPS, "mandalart-tour-seen");

  // mandalart.js의 init()(보드 목록 조회 + 첫 보드 로딩)이 끝나 격자가 실제로 그려진 뒤에 위치를
  // 재야 "AI로 채우기" 버튼 노출 여부 등이 반영된 상태로 스포트라이트가 맞는다
  window.addEventListener("load", () => setTimeout(tour.maybeStart, 500));

  const restartBtn = document.getElementById("restart-tour-btn");
  if (restartBtn) {
    restartBtn.addEventListener("click", () => {
      if (tour.isRunning()) return;
      tour.start();
    });
  }
})();
