// 첫 로그인 온보딩 투어: 계정별로 딱 한 번, 대시보드에 처음 들어왔을 때 주요 기능을 스포트라이트 +
// 말풍선으로 짧게 소개한다(엔진은 js/tour.js). 사이드바 접힘(sidebar.js)/AI 인사 말풍선(dashboard.js)과
// 같은 이유로 로그인 계정과 무관한 순수 UI 상태라 서버에는 저장하지 않고, 브라우저 localStorage 에
// 이메일별로 "봤다" 여부만 기억해둔다.
(function () {
  const TOUR_STEPS = [
    {
      target: "#category-list",
      title: "카테고리로 정리하기",
      text: "카테고리를 만들고 선택하면 그 카테고리의 일정만 모아볼 수 있어요.",
      placement: "right",
    },
    {
      target: "#view-switcher",
      title: "보기 방식 바꾸기",
      text: "보드・일・주・월・년 중 원하는 방식으로 일정을 볼 수 있어요.",
      placement: "bottom",
    },
    {
      target: "#wordcloud-widget",
      title: "자주 쓴 단어 한눈에 보기",
      text: "내가 등록한 일정 제목에서 자주 쓴 단어를 크기로 보여줘요. 단어에 마우스를 올리면 확대돼요.",
      placement: "bottom",
    },
    {
      target: "#mandalart-widget",
      title: "만다라트로 목표 세우기",
      text: "핵심 목표부터 실행 항목까지 만다라트로 정리하고, AI에게 자동으로 채워달라고 할 수도 있어요. 눌러보면 미리보기가 떠요.",
      placement: "bottom",
    },
    {
      target: "#achievement-widget",
      title: "오늘의 진행률",
      text: "오늘 등록한 일정 중 완료한 비율을 재미있는 비유로 보여드려요. 눌러보면 비유가 바뀌어요.",
      placement: "bottom",
    },
    {
      target: "#today-clock",
      title: "오늘 일정 한눈에 보기",
      text: "오늘 일정을 시계 모양으로 볼 수 있어요. 톱니바퀴를 누르면 카테고리별로 필터링할 수 있어요.",
      placement: "left",
    },
    {
      target: "#open-create-modal",
      title: "새 일정 추가",
      text: "여기를 눌러 새 일정을 만들 수 있어요. 모달 위쪽 탭에서 '반복 일정'을 고르면 요일마다 반복되는 일정도 한 번에 등록할 수 있어요.",
      placement: "bottom",
    },
    {
      target: "#open-recurring-modal",
      title: "반복 일정 관리",
      text: "지금 등록해둔 반복 일정 목록을 볼 수 있어요. 여기서 중단하거나, '+ 새 반복 일정'으로 새로 등록할 수도 있어요.",
      placement: "bottom",
    },
    {
      target: "#board",
      title: "드래그로 상태 바꾸기",
      text: "카드를 다른 칸으로 끌어놓거나 상태를 선택해서 대기・진행중・완료・취소를 바꿀 수 있어요.",
      placement: "top",
    },
    {
      target: "#open-ai-suggest-modal",
      title: "AI에게 일정 추천받기",
      text: "무엇을 하고 싶은지 말하면 AI가 어울리는 일정을 추천해드려요.",
      placement: "left",
    },
    {
      target: "#report-link",
      title: "기간별 리포트 보기",
      text: "이번 주・이번 달・올해 등록한 일정을 통계와 그래프로 모아보고, AI에게 잘한 점/아쉬운 점을 물어볼 수 있어요.",
      placement: "bottom",
    },
    {
      target: "#settings-link",
      title: "자동화 설정",
      text: "자동 상태 전환, AI 추천 자동 등록 같은 기능을 여기서 켜고 끌 수 있어요.",
      placement: "bottom",
    },
  ];

  const tour = createSpotlightTour(TOUR_STEPS, "onboarding-tour-seen");

  // dashboard.js의 비동기 초기 로딩(카테고리/일정 조회 등)이 끝나 레이아웃이 안정된 뒤에 위치를
  // 재야 스포트라이트가 어긋나지 않는다 - load 이벤트 뒤로 약간의 여유를 둔다
  window.addEventListener("load", () => setTimeout(tour.maybeStart, 500));

  // 왼쪽 하단 "?" 버튼(help-widget, dashboard.html) - hasSeen() 여부와 무관하게 언제든 다시
  // 볼 수 있어야 하므로 maybeStart가 아니라 start를 직접 부른다
  const restartBtn = document.getElementById("restart-tour-btn");
  if (restartBtn) {
    restartBtn.addEventListener("click", () => {
      if (tour.isRunning()) return;
      tour.start();
    });
  }
})();
