// 로그인 없이 대시보드에 들어온 방문자에게 보여줄 가짜 데이터. api.js의 API.request()가 데모 모드일
// 때(js/dashboard.js의 requireAuth 참고) GET 요청을 가로채 여기서 만든 데이터를 그대로 돌려준다 -
// 실제 백엔드 DTO(ScheduleResponseDto/CategoryResponseDto/PageResponseDto/UserResponseDto/
// MandalartBoardSummaryDto/MandalartBoardResponseDto)와 필드 이름이 같아야 대시보드 렌더링 코드를
// 하나도 안 건드리고 그대로 재사용할 수 있다
(function () {
  const CATEGORIES = [
    { id: 9001, name: "자기계발" },
    { id: 9002, name: "식단" },
    { id: 9003, name: "운동" },
    { id: 9004, name: "업무" },
    { id: 9005, name: "일상" },
  ];
  const CATEGORY_ID_BY_NAME = Object.fromEntries(CATEGORIES.map((c) => [c.name, c.id]));

  function pad(n) {
    return String(n).padStart(2, "0");
  }

  // 백엔드가 내려주는 LocalDateTime과 같은 포맷(타임존 없는 "YYYY-MM-DDTHH:mm:ss")으로 맞춘다 -
  // 대시보드 코드가 이 문자열을 new Date(...)로 그대로 파싱해 로컬 시간처럼 다루기 때문
  function iso(date) {
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:00`;
  }

  // dayOffset: 오늘(0) 기준 며칠 뒤/전. 방문 시점과 무관하게 "오늘"을 기준으로 계산해, 언제 열어도
  // 보드/시계/레이더 위젯이 항상 그럴듯하게 채워져 보이게 한다
  function at(dayOffset, hour, minute) {
    const d = new Date();
    d.setDate(d.getDate() + dayOffset);
    d.setHours(hour, minute, 0, 0);
    return iso(d);
  }

  let nextScheduleId = 80001;
  function sc(title, content, categoryName, dayOffset, startH, startM, endH, endM, status) {
    const startAt = at(dayOffset, startH, startM);
    const endAt = endH == null ? null : at(dayOffset, endH, endM);
    return {
      id: nextScheduleId++,
      title,
      content,
      startAt,
      endAt,
      status,
      username: "데모 사용자",
      categoryName,
      updatedAt: startAt,
      _categoryId: CATEGORY_ID_BY_NAME[categoryName], // DTO엔 없는 필드 - 아래 stripInternal()이 응답 직전에 걷어낸다
    };
  }

  const SCHEDULES = [
    // 오늘 - 대기/진행중/완료/취소가 고루 섞이게 (보드 4개 컬럼 + 오늘 시계 + 성취도 위젯용)
    sc("아침 러닝 6km", "동네 한바퀴, 페이스 5:30", "운동", 0, 6, 30, 7, 15, "COMPLETED"),
    sc("단백질 오트밀", "오트밀 + 프로틴 파우더 + 바나나", "식단", 0, 7, 30, 7, 45, "COMPLETED"),
    sc("팀 스탠드업 미팅", "어제 진행상황 공유 및 오늘 할 일 정리", "업무", 0, 9, 0, 9, 15, "COMPLETED"),
    sc("포트폴리오 리팩토링", "대시보드 위젯 코드 정리", "업무", 0, 10, 0, 12, 0, "IN_PROGRESS"),
    sc("점심 - 샐러드 & 닭가슴살", "", "식단", 0, 12, 30, 13, 0, "PENDING"),
    sc("독서 30분: 원씽", "5장까지", "자기계발", 0, 20, 0, 20, 30, "PENDING"),
    sc("저녁 스트레칭", "유튜브 15분 루틴", "운동", 0, 21, 30, 21, 50, "PENDING"),
    sc("장보기 (다이소)", "생필품 구매 예정이었으나 다음으로 미룸", "일상", 0, 18, 0, 18, 30, "CANCELLED"),

    // 어제/내일
    sc("헬스장 - 하체 운동", "스쿼트 5세트", "운동", -1, 7, 0, 8, 0, "COMPLETED"),
    sc("이력서 업데이트", "최근 프로젝트 반영", "자기계발", -1, 20, 0, 21, 0, "COMPLETED"),
    sc("스터디 모임", "알고리즘 스터디 3주차", "자기계발", 1, 10, 0, 12, 0, "PENDING"),
    sc("치과 예약", "정기 검진", "일상", 1, 15, 0, 15, 30, "PENDING"),

    // 이번 주 나머지
    sc("친구 생일 저녁 약속", "홍대 맛집 예약함", "일상", 3, 19, 0, 21, 0, "PENDING"),
    sc("주간 회고 작성", "이번 주 업무 회고 노션에 정리", "업무", 5, 17, 0, 17, 30, "PENDING"),

    // 이번 달 나머지 (달력/워드클라우드 뷰가 비어 보이지 않게)
    sc("명상 10분", "아침 명상 루틴", "자기계발", -6, 6, 30, 6, 40, "COMPLETED"),
    sc("프로젝트 킥오프 미팅", "신규 기능 기획 논의", "업무", -10, 14, 0, 15, 0, "COMPLETED"),
    sc("분기 목표 점검", "이번 분기 OKR 리뷰", "업무", -15, 9, 0, 10, 0, "COMPLETED"),
    sc("식단 정리: 주간 밀프렙", "일요일 오전에 5일치 준비", "식단", 8, 11, 0, 12, 30, "PENDING"),
    sc("온라인 강의 수강", "인프런 - 시스템 디자인", "자기계발", 10, 21, 0, 22, 0, "PENDING"),
    sc("러닝 10km 챌린지", "한강공원", "운동", 14, 7, 0, 8, 30, "PENDING"),

    // 지난달/다음달 (년간 뷰에서도 뭔가 보이도록)
    sc("신년 목표 설정", "올해 갓생 목표 브레인스토밍", "자기계발", -35, 10, 0, 11, 0, "COMPLETED"),
    sc("헬스 PT 첫 세션", "체성분 측정 + OT", "운동", 35, 19, 0, 20, 0, "PENDING"),
  ];

  const USER = {
    id: 90001,
    username: "데모 사용자",
    email: "demo@example.com",
    userType: "USER",
    autoStatusMode: true,
    aiAutoRegisterEnabled: true,
  };

  const MANDALART_BOARD_ID = 97001;
  const MANDALART_BOARD_TITLE = "2026년 갓생 만다라트";

  // 클래식 만다라트 좌표계(CLAUDE.md 참고): 중심 (4,4)=핵심 목표, 그 주변 8칸=세부목표,
  // 각 세부목표를 자기 바깥 3x3 블록의 중심 칸에 그대로 복사한다. 실행항목(나머지 56칸)은 비워둬
  // "이제 막 시작한 보드"처럼 자연스럽게 보이게 한다
  function mandalartCells() {
    const subGoals = [
      { row: 3, col: 3, text: "운동 습관화" },
      { row: 3, col: 4, text: "커리어 성장" },
      { row: 3, col: 5, text: "식단 관리" },
      { row: 4, col: 3, text: "독서 50권" },
      { row: 4, col: 5, text: "저축 500만원" },
      { row: 5, col: 3, text: "인간관계" },
      { row: 5, col: 4, text: "취미 생활" },
      { row: 5, col: 5, text: "수면 관리" },
    ];
    const cellsByCoord = new Map();
    const put = (row, col, content) => cellsByCoord.set(`${row}-${col}`, content);

    put(4, 4, "2026 갓생 살기");
    subGoals.forEach((g) => put(g.row, g.col, g.text));

    // 세부목표 상대위치 -> 바깥 블록 중심 좌표로 직접 매핑 (top-left/top/top-right/left/right/bottom-left/bottom/bottom-right)
    const mirrorMap = [
      { sub: [3, 3], center: [1, 1] },
      { sub: [3, 4], center: [1, 4] },
      { sub: [3, 5], center: [1, 7] },
      { sub: [4, 3], center: [4, 1] },
      { sub: [4, 5], center: [4, 7] },
      { sub: [5, 3], center: [7, 1] },
      { sub: [5, 4], center: [7, 4] },
      { sub: [5, 5], center: [7, 7] },
    ];
    mirrorMap.forEach(({ sub, center }) => {
      put(center[0], center[1], cellsByCoord.get(`${sub[0]}-${sub[1]}`));
    });

    const cells = [];
    for (let row = 0; row < 9; row++) {
      for (let col = 0; col < 9; col++) {
        cells.push({ row, col, content: cellsByCoord.get(`${row}-${col}`) || "" });
      }
    }
    return cells;
  }

  function stripInternal(schedule) {
    const { _categoryId, ...dto } = schedule;
    return dto;
  }

  function filterByCategoryParam(list, params) {
    const categoryId = params.get("categoryId");
    if (!categoryId) return list;
    return list.filter((s) => String(s._categoryId) === String(categoryId));
  }

  // "/api/x/y?a=1&b=2" -> { path: "/api/x/y", params: URLSearchParams }
  function splitPath(pathWithQuery) {
    const [path, qs] = pathWithQuery.split("?");
    return { path, params: new URLSearchParams(qs || "") };
  }

  // 알려진 경로면 실제 API 응답과 같은 모양의 데이터를 돌려주고, 모르는 경로면 undefined를 돌려줘
  // 호출부(api.js)가 평소처럼 실제 fetch로 흘려보내게 한다(그러면 인증 없이 401을 받고, 기존 catch가
  // 이미 조용히 넘어가도록 되어 있다)
  function resolve(pathWithQuery) {
    const { path, params } = splitPath(pathWithQuery);

    if (path === "/api/categories") return CATEGORIES.slice();

    if (path === "/api/users/me") return Object.assign({}, USER);

    if (path === "/api/ai/chat/messages") return [];

    if (path === "/api/schedules") {
      return filterByCategoryParam(SCHEDULES, params).map(stripInternal);
    }

    if (path === "/api/schedules/board") {
      const status = params.get("status");
      const date = params.get("date"); // YYYY-MM-DD
      const size = Number(params.get("size") || "5");

      let list = filterByCategoryParam(SCHEDULES, params).filter((s) => s.status === status);
      if (date) {
        const dayStart = new Date(`${date}T00:00:00`);
        const dayEnd = new Date(dayStart);
        dayEnd.setDate(dayEnd.getDate() + 1);
        list = list.filter((s) => {
          const start = new Date(s.startAt);
          const end = s.endAt ? new Date(s.endAt) : start;
          return end > dayStart && start < dayEnd;
        });
      }

      return {
        content: list.slice(0, size).map(stripInternal),
        page: 0,
        size,
        totalElements: list.length,
        totalPages: Math.max(1, Math.ceil(list.length / size)),
        hasNext: list.length > size,
      };
    }

    if (path === "/api/mandalart") {
      return [{ id: MANDALART_BOARD_ID, title: MANDALART_BOARD_TITLE, createdAt: at(-40, 9, 0), active: true }];
    }

    if (path === `/api/mandalart/${MANDALART_BOARD_ID}`) {
      return { id: MANDALART_BOARD_ID, title: MANDALART_BOARD_TITLE, active: true, cells: mandalartCells() };
    }

    return undefined;
  }

  window.DemoFixtures = { resolve };
})();
