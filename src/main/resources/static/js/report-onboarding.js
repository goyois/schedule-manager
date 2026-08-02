// 리포트 페이지 가이드 투어 - 계정별로 첫 방문 때 한 번, 사용법을 스포트라이트 + 말풍선으로 짧게
// 소개한다(엔진은 js/tour.js). 대시보드/만다라트 투어(js/onboarding.js, js/mandalart-onboarding.js)와
// 저장 방식은 같지만, 서로 다른 storageKeyPrefix를 쓰므로 "다른 페이지는 봤지만 리포트는 아직 안 봄"
// 같은 상태도 독립적으로 기억한다.
(function () {
  const TOUR_STEPS = [
    {
      target: "#report-period-switcher",
      title: "기간 단위 고르기",
      text: "주・월・년 단위로 통계를 골라볼 수 있어요.",
      placement: "bottom",
    },
    {
      target: ".report-date-nav",
      title: "기간 이동하기",
      text: "이전・다음 버튼으로 지난 기간을 살펴보거나, '오늘' 버튼으로 바로 돌아올 수 있어요.",
      placement: "bottom",
    },
    {
      target: ".report-trend-card",
      title: "카테고리별 추이・비율",
      text: "날짜별로 카테고리마다 몇 건씩 등록했는지, 전체 비율은 어떤지 한눈에 볼 수 있어요. 파이 조각에 마우스를 올리면 자세히 보여요.",
      placement: "bottom",
    },
    {
      target: ".report-summary-card",
      title: "요약 통계",
      text: "총 일정 수와 완료율, 직전 동일 기간과 비교한 변화를 볼 수 있어요.",
      placement: "top",
    },
    {
      target: ".report-insight-card",
      title: "AI 코멘트 받기",
      text: "이번 기간 통계와 일정 기록을 바탕으로 AI가 잘한 점/아쉬운 점, 행동 패턴을 분석해줘요. 한 번 생성하면 저장되고, 이후 이 기간 일정이 바뀌었을 때만 다시 생성할 수 있어요.",
      placement: "top",
    },
  ];

  const tour = createSpotlightTour(TOUR_STEPS, "report-tour-seen");

  // report.js의 비동기 초기 로딩(통계/추이 조회 등)이 끝나 레이아웃이 안정된 뒤에 위치를 재야
  // 스포트라이트가 어긋나지 않는다 - load 이벤트 뒤로 약간의 여유를 둔다
  window.addEventListener("load", () => setTimeout(tour.maybeStart, 500));

  const restartBtn = document.getElementById("restart-tour-btn");
  if (restartBtn) {
    restartBtn.addEventListener("click", () => {
      if (tour.isRunning()) return;
      tour.start();
    });
  }
})();
