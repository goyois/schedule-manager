const STATUS_COLUMNS = [
  { key: "PENDING", label: "대기" },
  { key: "IN_PROGRESS", label: "진행중" },
  { key: "COMPLETED", label: "완료" },
  { key: "CANCELLED", label: "취소" },
];

let categories = []; // [{id, name}]
let schedules = [];  // ScheduleResponseDto[] (전체 일정, 통계 카드용)
let categorySchedules = null; // 카테고리 선택 시 서버에서 받아온 해당 카테고리 일정, null = 미선택
let activeCategoryId = ""; // "" = 전체
const BOARD_COLUMN_VISIBLE_LIMIT = 5;
const boardColumnVisibleCount = new Map(); // status key -> 현재까지 서버에 요청할 개수(size). "더보기"를 누를 때마다 5씩 늘어나고, 재렌더링(상태 변경 등) 후에도 유지된다
const boardColumnData = new Map(); // status key -> { items: ScheduleResponseDto[], totalElements } - GET /api/schedules/board 응답 캐시
let viewMode = "board"; // "board" | "day" | "week" | "month" | "year"
let viewDate = new Date(); // 일/주/월/년 뷰의 기준(anchor) 날짜
// 이번 세션에서 생성/수정한 일정의 categoryId, userId 를 기억해 수정 모달을 정확히 채워준다
// (서버 응답인 ScheduleResponseDto 에는 categoryName/username 문자열만 있고 id 가 없기 때문)
const scheduleMeta = new Map(); // scheduleId -> { categoryId, userId }

const board = document.getElementById("board");
const toast = document.getElementById("toast");
const categoryListEl = document.getElementById("category-list");
const categoryCountEl = document.getElementById("category-count");
const categorySelect = document.getElementById("category-select");

// 보드 카드 접기: 제목만 남기고 본문/카테고리/시간/상태변경 영역을 감춘다. 카드별 .collapsed 클래스는
// scheduleCardHtml()이 board.compact 여부(+ forceExpandedCardIds)를 보고 렌더링 시점에 계산하므로,
// 이 토글이 바뀌면 재렌더링을 해줘야 카드들이 새 기본값을 반영한다(isCardVisuallyCollapsed 참고).
// 사이드바 접힘(sidebar.js)과 같은 이유로 로그인 계정과 무관한 순수 레이아웃 취향이라 이메일별로
// 나누지 않고 브라우저 localStorage에 그대로 저장해둔다
const BOARD_COMPACT_KEY = "board-cards-compact";
(function initBoardCompactToggle() {
  const toggleBtn = document.getElementById("board-compact-toggle-btn");
  if (!toggleBtn) return;

  const applyCompact = (compact) => {
    board.classList.toggle("compact", compact);
    toggleBtn.textContent = compact ? "▤ 펼치기" : "▤ 접기";
    toggleBtn.title = compact ? "일정 카드 펼치기" : "일정 카드 접기";
  };

  applyCompact(localStorage.getItem(BOARD_COMPACT_KEY) === "true");

  toggleBtn.addEventListener("click", () => {
    const next = !board.classList.contains("compact");
    applyCompact(next);
    localStorage.setItem(BOARD_COMPACT_KEY, String(next));
    renderBoard();
  });
})();

// 일정 카드 하나씩 개별로 접기: 전체 접기(위 BOARD_COMPACT_KEY)와 달리 카드별로 켜고 끌 수 있다.
// renderBoard()가 SSE 이벤트 등으로 board.innerHTML을 통째로 다시 그리기 때문에, 접힘 여부를 DOM
// 클래스만으로 들고 있으면 재렌더링될 때마다 사라진다 - 접힌 일정 id를 따로 기억해두고
// scheduleCardHtml()이 카드를 만들 때마다 다시 반영한다
const CARD_COLLAPSED_KEY = "board-card-collapsed-ids";

function loadCollapsedCardIds() {
  try {
    return new Set(JSON.parse(localStorage.getItem(CARD_COLLAPSED_KEY)) || []);
  } catch (err) {
    return new Set();
  }
}

const collapsedCardIds = loadCollapsedCardIds();

function saveCollapsedCardIds() {
  localStorage.setItem(CARD_COLLAPSED_KEY, JSON.stringify([...collapsedCardIds]));
}

// 전체 접기(board.compact)가 켜진 상태에서 카드 하나만 예외로 펼쳐두고 싶을 때 쓴다. collapsedCardIds와
// 반대 의미(집합에 있으면 "펼침")로 따로 둔 이유: 전체 접기가 꺼져 있을 땐 기본이 펼침이라 collapsedCardIds
// 하나로 충분했지만, 전체 접기가 켜지면 기본이 접힘으로 뒤집히므로 "이 카드는 예외"를 표현하려면
// 별도의 집합이 필요하다 - scheduleCardHtml()이 board.compact 여부에 따라 둘 중 하나만 참조한다
const CARD_FORCE_EXPANDED_KEY = "board-card-force-expanded-ids";

function loadForceExpandedCardIds() {
  try {
    return new Set(JSON.parse(localStorage.getItem(CARD_FORCE_EXPANDED_KEY)) || []);
  } catch (err) {
    return new Set();
  }
}

const forceExpandedCardIds = loadForceExpandedCardIds();

function saveForceExpandedCardIds() {
  localStorage.setItem(CARD_FORCE_EXPANDED_KEY, JSON.stringify([...forceExpandedCardIds]));
}

function showToast(message) {
  toast.textContent = message;
  toast.classList.add("show");
  setTimeout(() => toast.classList.remove("show"), 2600);
}

function formatDateTime(value) {
  if (!value) return "";
  const d = new Date(value);
  return d.toLocaleString("ko-KR", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

// 종료 시각이 없는(알림형) 일정은 시작 시각만 보여준다
function formatTimeRange(startAt, endAt) {
  return endAt ? `${formatDateTime(startAt)} → ${formatDateTime(endAt)}` : formatDateTime(startAt);
}

// 월간 뷰처럼 좁은 칸에 "시:분"만 짧게 보여줄 때 쓴다
function formatTimeOnly(value) {
  if (!value) return "";
  const d = new Date(value);
  const pad = (n) => String(n).padStart(2, "0");
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function toDatetimeLocalValue(value) {
  if (!value) return "";
  const d = new Date(value);
  const pad = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

// 네이티브 datetime-local input의 시:분 표시는 결국 브라우저/OS 로케일을 따라가서(예: 한국어
// 로케일은 오전/오후 12시간제), lang 속성 같은 트릭으로도 모든 브라우저에서 24시간제를 보장할 수
// 없었다. 그래서 시작/종료 입력을 date + 시(0~23) + 분(0~59) 숫자 입력 3개로 직접 구성해 완전히
// 우리가 표시 형식을 통제한다. 기존 로직(제출·유효성 검사·시작 변경 시 종료 자동 이동 등)은 전부
// "YYYY-MM-DDTHH:MM" 문자열을 담은 hidden input(#startAt/#endAt)을 기준으로 동작하므로 그대로 두고,
// 이 hidden input과 화면에 보이는 date/시/분 입력 3개 사이를 양방향으로 동기화하는 역할만 추가한다
function composeDateTimeValue(dateStr, hourStr, minuteStr) {
  if (!dateStr) return "";
  const hour = Math.min(23, Math.max(0, parseInt(hourStr, 10) || 0));
  const minute = Math.min(59, Math.max(0, parseInt(minuteStr, 10) || 0));
  const pad = (n) => String(n).padStart(2, "0");
  return `${dateStr}T${pad(hour)}:${pad(minute)}`;
}

function decomposeDateTimeValue(value) {
  if (!value) return { date: "", hour: "", minute: "" };
  const [datePart, timePart] = value.split("T");
  const [hour, minute] = (timePart || "").split(":");
  return { date: datePart || "", hour: hour || "", minute: minute || "" };
}

// hiddenInput(#startAt/#endAt)과 화면에 보이는 date/시/분 입력 3개를 묶어 동기화한다.
// 사용자가 date/시/분 중 하나라도 바꾸면 hidden input의 값을 다시 계산해 채우고, 기존 코드가
// hidden input에 걸어둔 change 리스너(예: 시작 시각 변경 시 종료 시각 자동 이동)가 그대로 반응할
// 수 있도록 change 이벤트를 직접 발생시킨다. 반대로 코드가 hidden input.value를 프로그램적으로
// 바꿨을 때는(모달 열 때 초기값 채우기 등) syncVisibleFromHidden()을 호출해 화면 쪽을 맞춘다
function bindDateTimeGroup(hiddenInput, dateInput, hourInput, minuteInput) {
  function syncHiddenFromVisible() {
    const next = composeDateTimeValue(dateInput.value, hourInput.value, minuteInput.value);
    if (hiddenInput.value === next) return;
    hiddenInput.value = next;
    hiddenInput.dispatchEvent(new Event("change", { bubbles: true }));
  }

  function syncVisibleFromHidden() {
    const { date, hour, minute } = decomposeDateTimeValue(hiddenInput.value);
    dateInput.value = date;
    hourInput.value = hour;
    minuteInput.value = minute;
  }

  [dateInput, hourInput, minuteInput].forEach((el) => {
    el.addEventListener("input", syncHiddenFromVisible);
  });

  return { syncVisibleFromHidden };
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

// 보드 뷰에서는 today-nav 화살표로 옮겨다니는 viewDate 기준 날짜를, 그 외 뷰에서는 실제 오늘
// 날짜를 보여준다(일/주/월/년 뷰는 같은 자리에 뜨는 view-nav가 이미 자기 날짜/범위를 보여주고
// 있어서, 여기서는 원래 의미인 "오늘"을 그대로 유지한다)
function renderToday() {
  const d = viewMode === "board" ? viewDate : new Date();
  document.getElementById("today-label").textContent = d.toLocaleDateString("ko-KR", {
    year: "numeric",
    month: "long",
    day: "numeric",
    weekday: "long",
  });
}

// 사이드바에서 선택한 카테고리 필터도 새로고침하면 "전체 일정"으로 돌아가지 않도록 로그인
// 이메일별로 localStorage에 저장해둔다 - 카테고리 순서와 같은 이유로 서버에는 저장하지 않는다
function activeCategoryStorageKey() {
  const user = API.getCurrentUser();
  const email = (user && user.email) || "anonymous";
  return `active-category:${email}`;
}

function loadStoredActiveCategoryId() {
  return localStorage.getItem(activeCategoryStorageKey()) || "";
}

function saveActiveCategoryId(id) {
  localStorage.setItem(activeCategoryStorageKey(), id || "");
}

// 카테고리 순서는 서버에 저장하지 않는다 - 사용자마다(같은 카테고리를 보고 있어도) 자기 화면에서만
// 순서를 다르게 두고 싶을 수 있어서, 브라우저 localStorage에 로그인 이메일별로 순서(카테고리 id 배열)를
// 따로 저장해두고 목록을 받아올 때마다 그 순서로 재정렬한다
function categoryOrderStorageKey() {
  const user = API.getCurrentUser();
  const email = (user && user.email) || "anonymous";
  return `category-order:${email}`;
}

function loadStoredCategoryOrder() {
  try {
    return JSON.parse(localStorage.getItem(categoryOrderStorageKey())) || [];
  } catch (err) {
    return [];
  }
}

function saveStoredCategoryOrder(orderIds) {
  localStorage.setItem(categoryOrderStorageKey(), JSON.stringify(orderIds));
}

// 저장된 순서를 우선 적용하고, 저장된 순서에 없는 카테고리(새로 생겼거나 처음 로그인)는 서버가 내려준
// 순서 그대로 뒤에 붙인다. 이미 삭제된 카테고리 id는 목록에 없으니 자연히 걸러진다
function applyStoredCategoryOrder(list) {
  const order = loadStoredCategoryOrder();
  if (!order.length) return list;
  const byId = new Map(list.map((c) => [String(c.id), c]));
  const ordered = [];
  order.forEach((id) => {
    const c = byId.get(String(id));
    if (c) {
      ordered.push(c);
      byId.delete(String(id));
    }
  });
  list.forEach((c) => {
    if (byId.has(String(c.id))) ordered.push(c);
  });
  return ordered;
}

// 드래그한 카테고리(srcId)를 드롭 대상(targetId)의 앞 또는 뒤로 옮긴다. placeAfter가 없으면(=항상 앞)
// 마지막 항목 위에 놓아도 그 앞자리로만 들어가서 "맨 밑으로 내리기"가 불가능했다 - 그래서 드롭 시점의
// 마우스 위치(대상 항목의 위쪽 절반/아래쪽 절반)로 앞/뒤를 정한다. srcId를 먼저 배열에서 빼낸 뒤
// targetId의 새 인덱스를 다시 찾아 그 자리(+1)에 끼워 넣는 방식이라 별도 인덱스 보정이 필요 없다
function reorderCategory(srcId, targetId, placeAfter) {
  if (String(srcId) === String(targetId)) return;
  const srcIdx = categories.findIndex((c) => String(c.id) === String(srcId));
  if (srcIdx === -1) return;
  const [moved] = categories.splice(srcIdx, 1);
  const targetIdx = categories.findIndex((c) => String(c.id) === String(targetId));
  const insertAt = targetIdx === -1 ? categories.length : targetIdx + (placeAfter ? 1 : 0);
  categories.splice(insertAt, 0, moved);
  saveStoredCategoryOrder(categories.map((c) => c.id));
  renderCategorySidebar();
  renderCategorySelectOptions();
}

function isBelowMidpoint(li, clientY) {
  const rect = li.getBoundingClientRect();
  return clientY - rect.top > rect.height / 2;
}

async function loadCategories() {
  try {
    categories = applyStoredCategoryOrder(await API.get("/api/categories"));
  } catch (err) {
    categories = [];
    showToast(`카테고리를 불러오지 못했습니다. ${err.message}`);
  }
  // 시계/레이더 필터에 저장돼 있던 카테고리 id 중 삭제되어 더 이상 없는 건 걸러낸다
  const validIds = clockCategoryFilter.filter((id) => categories.some((c) => String(c.id) === id));
  if (validIds.length !== clockCategoryFilter.length) {
    clockCategoryFilter = validIds;
    saveClockCategoryFilter(clockCategoryFilter);
  }
  clockFilterBtn.classList.toggle("active", clockCategoryFilter.length > 0);
  renderClockFilterOptions();

  const validRadarIds = radarCategoryFilter.filter((id) => categories.some((c) => String(c.id) === id));
  if (validRadarIds.length !== radarCategoryFilter.length) {
    radarCategoryFilter = validRadarIds;
    saveRadarCategoryFilter(radarCategoryFilter);
  }
  radarFilterBtn.classList.toggle("active", radarCategoryFilter.length > 0);
  renderRadarFilterOptions();

  renderCategorySidebar();
  renderCategorySelectOptions();
}

function renderCategorySidebar() {
  categoryCountEl.textContent = categories.length ? `(${categories.length})` : "";

  const allItem = `
    <li data-category-id="" class="${activeCategoryId === "" ? "active" : ""}" title="전체 일정">
      <span class="cat-icon">전</span>
      <span><span class="dot"></span>전체 일정</span>
      <span class="cat-count" data-count-for="">0</span>
    </li>`;

  const items = categories
    .map(
      (c) => `
      <li draggable="true" data-category-id="${c.id}" class="${String(activeCategoryId) === String(c.id) ? "active" : ""}" title="${escapeHtml(c.name)}">
        <span class="cat-icon">${escapeHtml(c.name.slice(0, 1))}</span>
        <span><span class="drag-handle">&#8942;&#8942;</span><span class="dot"></span>${escapeHtml(c.name)}</span>
        <span class="cat-right">
          <span class="cat-actions">
            <span class="remove-cat" data-remove-category="${c.id}">&times;</span>
          </span>
          <span class="cat-count" data-count-for="${c.id}">0</span>
        </span>
      </li>`
    )
    .join("");

  categoryListEl.innerHTML = allItem + items;

  categoryListEl.querySelectorAll("li").forEach((li) => {
    li.addEventListener("click", async (e) => {
      if (e.target.dataset.removeCategory) return;
      activeCategoryId = li.dataset.categoryId;
      saveActiveCategoryId(activeCategoryId);
      renderCategorySidebar();
      renderBoardTitle();
      await loadBoardForActiveCategory();
    });
  });

  // 카테고리 항목을 마우스로 눌러 끌어서 순서를 바꾼다("전체 일정" 항목은 draggable이 아니라 대상에서
  // 빠진다). dragover에서 매번 preventDefault를 해줘야 그 요소가 드롭 대상으로 인정된다
  categoryListEl.querySelectorAll("li[draggable='true']").forEach((li) => {
    li.addEventListener("dragstart", (e) => {
      e.dataTransfer.effectAllowed = "move";
      e.dataTransfer.setData("text/plain", li.dataset.categoryId); // Firefox는 setData 없으면 드래그 자체가 시작되지 않는다
      li.classList.add("dragging");
    });
    li.addEventListener("dragend", () => li.classList.remove("dragging"));
    li.addEventListener("dragover", (e) => {
      e.preventDefault();
      e.dataTransfer.dropEffect = "move";
      const placeAfter = isBelowMidpoint(li, e.clientY);
      li.classList.toggle("drag-over-top", !placeAfter);
      li.classList.toggle("drag-over-bottom", placeAfter);
    });
    li.addEventListener("dragleave", () => li.classList.remove("drag-over-top", "drag-over-bottom"));
    li.addEventListener("drop", (e) => {
      e.preventDefault();
      const placeAfter = isBelowMidpoint(li, e.clientY);
      li.classList.remove("drag-over-top", "drag-over-bottom");
      const srcId = e.dataTransfer.getData("text/plain");
      reorderCategory(srcId, li.dataset.categoryId, placeAfter);
    });
  });

  categoryListEl.querySelectorAll("[data-remove-category]").forEach((btn) => {
    btn.addEventListener("click", async (e) => {
      e.stopPropagation();
      const id = btn.dataset.removeCategory;
      if (!confirm("이 카테고리를 삭제할까요?")) return;
      try {
        await API.del(`/api/categories/${id}`);
        if (String(activeCategoryId) === String(id)) {
          activeCategoryId = "";
          saveActiveCategoryId("");
        }
        await loadCategories();
        renderBoardTitle();
        await loadBoardForActiveCategory();
        showToast("카테고리를 삭제했습니다.");
      } catch (err) {
        // 403(예: 기본 설정 카테고리 삭제 시도)은 서버 메시지 자체가 이미 사용자에게 보여줄 완결된 안내문이라
        // "삭제하지 못했습니다" 접두어 없이 그대로 보여준다
        showToast(err.status === 403 ? err.message : `카테고리를 삭제하지 못했습니다. ${err.message}`);
      }
    });
  });

  renderCategoryCounts();
}

// 카테고리 목록 각 항목 옆에 보여줄 일정 개수 - 상단에 따로 있던 통계 카드(전체 일정/대기/진행중/완료)를
// 없애고 이 숫자로 대체했다. getViewScopedSchedules() 를 써서 레이더·시계 위젯과 같은 원칙으로
// 현재 뷰(보드/일/주/월/년)의 집계 범위에 맞춰 센다 - 카테고리 필터와는 무관하게 항상 전체 일정 기준
function renderCategoryCounts() {
  const list = getViewScopedSchedules();
  const totalEl = categoryListEl.querySelector('[data-count-for=""]');
  if (totalEl) totalEl.textContent = list.length;
  categories.forEach((c) => {
    const el = categoryListEl.querySelector(`[data-count-for="${c.id}"]`);
    if (el) el.textContent = list.filter((s) => s.categoryName === c.name).length;
  });
}

function renderCategorySelectOptions() {
  categorySelect.innerHTML = categories
    .map((c) => `<option value="${c.id}">${escapeHtml(c.name)}</option>`)
    .join("");
}

function renderBoardTitle() {
  const title =
    activeCategoryId === ""
      ? "전체 일정"
      : (categories.find((c) => String(c.id) === String(activeCategoryId)) || {}).name || "일정";
  document.getElementById("board-title").textContent = title;
}

// 서버가 Authorization 헤더의 JWT 로 로그인한 유저를 식별해 role 에 맞는 결과를 돌려주므로
// 클라이언트에서 userId 를 알아야만 호출 가능한 게 아니다 (USER 는 본인 것만, ADMIN 은 전체)
async function loadSchedules() {
  try {
    schedules = await API.get("/api/schedules");
  } catch (err) {
    schedules = [];
    showToast(`일정을 불러오지 못했습니다. ${err.message}`);
  }
  refreshVisibleView(); // 통계 카드/레이더 모두 여기서 함께 갱신된다
}

// 사이드바에서 카테고리를 선택하면 서버에 categoryId 를 실어 보내 해당 카테고리의 일정만 조회한다
async function loadBoardForActiveCategory() {
  if (activeCategoryId === "") {
    categorySchedules = null;
    refreshVisibleView();
    return;
  }

  try {
    categorySchedules = await API.get(`/api/schedules?categoryId=${encodeURIComponent(activeCategoryId)}`);
  } catch (err) {
    categorySchedules = [];
    showToast(`카테고리별 일정을 불러오지 못했습니다. ${err.message}`);
  }
  refreshVisibleView();
}

// 목록을 변경(생성/수정/삭제/상태 변경)한 뒤 통계 카드와 현재 보드(전체 or 선택된 카테고리)를 함께 새로고침한다
async function refreshAll() {
  await loadSchedules();
  await loadBoardForActiveCategory();
}

// 일정 생성/수정/삭제/상태변경 실패 시 상태코드별로 다른 UX를 보여준다:
// 404(이미 삭제·존재하지 않는 일정)는 로컬 상태가 서버와 어긋난 것이므로 목록을 새로고침해 맞추고,
// 403(소유자 아님)은 재시도로 해결되지 않으므로 전용 안내를, 그 외는 서버 메시지를 그대로 보여준다.
// 목록을 새로고침한 경우 true 를 반환 — 호출부가 모달을 닫거나 로컬 상태를 되돌릴지 판단하는 데 쓴다
async function notifyScheduleMutationError(err, actionLabel) {
  if (err.status === 404) {
    showToast("이미 삭제되었거나 존재하지 않는 일정입니다. 목록을 새로고침합니다.");
    await refreshAll();
    return true;
  }
  if (err.status === 403) {
    showToast(`본인의 일정만 ${actionLabel}할 수 있습니다.`);
    return false;
  }
  showToast(`${actionLabel}하지 못했습니다. ${err.message}`);
  return false;
}

function visibleSchedules() {
  if (activeCategoryId === "") return schedules;
  return categorySchedules ?? [];
}

// ---------- 일정 통계 레이더 - 보드/일/주/월/년 뷰에 맞춰 집계 범위가 바뀐다.
// 축은 상태(대기/진행중/완료/취소) 고정이 아니라 카테고리 기반이고, 오른쪽 위 ⚙ 버튼으로 어떤
// 카테고리를 축으로 보여줄지 고를 수 있다(today-clock의 카테고리 필터 팝오버와 동일한 패턴) ----------

const RADAR_CENTER = 124;
const RADAR_MAX_R = 69;

// 축 개수가 선택한 카테고리 수만큼 매번 달라지므로(고정 5축이 아님), 각도별로 라벨의 세로 위치를
// 일반화된 규칙으로 보정한다 - 글자가 baseline 기준으로 그려지다 보니 축이 거의 정확히 위/아래를
// 향할 때는 dy를 크게, 옆(수평에 가까울수록)을 향할 때는 작게 줘야 꼭짓점과의 시각적 간격이 고르게
// 맞는다. |sin(angle)|이 1에 가까울수록(=옆쪽) dy를 줄이는 식으로 근사한다
function radarLabelDy(angleDeg) {
  const rad = (angleDeg * Math.PI) / 180;
  return 10 - 6 * Math.abs(Math.sin(rad));
}

const radarSvgEl = document.getElementById("month-radar-svg");

// angle 0 = 12시 방향, 시계 방향으로 증가 (today-clock 의 polarPoint 와 동일한 규칙)
function radarPolarPoint(r, angleDeg) {
  const rad = ((angleDeg - 90) * Math.PI) / 180;
  return { x: RADAR_CENTER + r * Math.cos(rad), y: RADAR_CENTER + r * Math.sin(rad) };
}

// 현재 뷰(보드/일/주/월/년)에 맞는 집계 범위를 돌려준다. 보드는 오늘 하루로 좁힌다(renderBoard와
// 동일 기준). 레이더뿐 아니라 카테고리별 카운트(renderCategoryCounts)도 이 범위를 공유한다
function getViewScheduleWindow() {
  if (viewMode === "day") {
    const start = startOfDay(viewDate);
    return { start, end: addDays(start, 1) };
  }
  if (viewMode === "week") {
    const start = startOfWeek(viewDate);
    return { start, end: addDays(start, 7) };
  }
  if (viewMode === "year") {
    return { start: new Date(viewDate.getFullYear(), 0, 1), end: new Date(viewDate.getFullYear() + 1, 0, 1) };
  }
  if (viewMode === "month") {
    return {
      start: new Date(viewDate.getFullYear(), viewDate.getMonth(), 1),
      end: new Date(viewDate.getFullYear(), viewDate.getMonth() + 1, 1),
    };
  }
  // board: 카드 목록(renderBoard)도 viewDate 하루치만 보여주므로(today-nav 화살표로 전날/다음날
  // 이동 가능), 레이더/카테고리별 카운트도 같은 기준으로 맞춘다
  const boardDayStart = startOfDay(viewDate);
  return { start: boardDayStart, end: addDays(boardDayStart, 1) };
}

// 카테고리 필터와 무관하게 오늘 시계와 같은 원칙으로 항상 전체 일정(schedules) 기준으로 집계하되,
// 현재 뷰(보드/일/주/월/년)의 날짜 범위로만 좁힌다
function getViewScopedSchedules() {
  const window = getViewScheduleWindow();
  return window ? schedulesOverlappingRange(schedules, window.start, window.end) : schedules;
}

// 레이더 축으로 보여줄 카테고리 필터 - today-clock 카테고리 필터와 같은 이유로 서버가 아니라
// 사용자 이메일별 localStorage에 저장한다. 비어있으면 전체 카테고리를 축으로 쓰고, 하나 이상
// 선택하면 그 카테고리들만 축으로 쓴다
function radarCategoryFilterStorageKey() {
  const user = API.getCurrentUser();
  const email = (user && user.email) || "anonymous";
  return `radar-category-filter:${email}`;
}

function loadStoredRadarCategoryFilter() {
  try {
    return JSON.parse(localStorage.getItem(radarCategoryFilterStorageKey())) || [];
  } catch (err) {
    return [];
  }
}

function saveRadarCategoryFilter(ids) {
  localStorage.setItem(radarCategoryFilterStorageKey(), JSON.stringify(ids));
}

let radarCategoryFilter = loadStoredRadarCategoryFilter();

function radarAxisCategories() {
  if (radarCategoryFilter.length === 0) return categories;
  return categories.filter((c) => radarCategoryFilter.includes(String(c.id)));
}

function renderScheduleRadar() {
  const rangeSchedules = getViewScopedSchedules();
  const axisCategories = radarAxisCategories();

  radarSvgEl.textContent = "";
  if (axisCategories.length === 0) return; // 카테고리가 아예 없거나 필터에서 전부 해제하면 그릴 게 없다

  const counts = Object.fromEntries(
    axisCategories.map((c) => [c.id, rangeSchedules.filter((s) => s.categoryName === c.name).length])
  );
  // 축마다 다른 스케일을 쓰면 모양 비교가 무의미해지므로, 가장 큰 값을 기준으로 전체 축이 같은 스케일을 쓴다
  const maxValue = Math.max(1, ...axisCategories.map((c) => counts[c.id]));

  // 그리드: 25/50/75/100% 위치에 다각형 링을 그린다
  [0.25, 0.5, 0.75, 1].forEach((ratio) => {
    const pts = axisCategories.map((_, i) => {
      const p = radarPolarPoint(RADAR_MAX_R * ratio, (i / axisCategories.length) * 360);
      return `${p.x},${p.y}`;
    }).join(" ");
    radarSvgEl.appendChild(svgEl("polygon", { class: "radar-grid-ring", points: pts }));
  });

  // 축 선 + 라벨(카테고리 이름)
  axisCategories.forEach((category, i) => {
    const angle = (i / axisCategories.length) * 360;
    const outer = radarPolarPoint(RADAR_MAX_R, angle);
    radarSvgEl.appendChild(
      svgEl("line", { class: "radar-axis-line", x1: RADAR_CENTER, y1: RADAR_CENTER, x2: outer.x, y2: outer.y })
    );
    const labelPt = radarPolarPoint(RADAR_MAX_R + 22, angle);
    const label = svgEl("text", {
      class: "radar-axis-label",
      x: labelPt.x,
      y: labelPt.y + radarLabelDy(angle),
      "text-anchor": "middle",
    });
    label.textContent = category.name;
    radarSvgEl.appendChild(label);
  });

  // 데이터 다각형
  const dataPoints = axisCategories.map((category, i) => {
    const value = counts[category.id];
    const r = (value / maxValue) * RADAR_MAX_R;
    return radarPolarPoint(r, (i / axisCategories.length) * 360);
  });
  const dataShape = svgEl("polygon", {
    class: "radar-data-shape",
    points: dataPoints.map((p) => `${p.x},${p.y}`).join(" "),
  });
  // CSS 의 radar-grow 애니메이션이 중심(RADAR_CENTER)을 기준으로 퍼지도록 origin 을 직접 지정한다
  dataShape.style.transformBox = "view-box";
  dataShape.style.transformOrigin = `${RADAR_CENTER}px ${RADAR_CENTER}px`;
  radarSvgEl.appendChild(dataShape);
}

const radarFilterBtn = document.getElementById("radar-filter-btn");
const radarFilterPopover = document.getElementById("radar-filter-popover");
const radarFilterOptionsEl = document.getElementById("radar-filter-options");

function renderRadarFilterOptions() {
  radarFilterOptionsEl.innerHTML = categories
    .map(
      (c) => `
      <label class="clock-filter-option">
        <input type="checkbox" data-radar-filter-id="${c.id}" ${radarCategoryFilter.includes(String(c.id)) ? "checked" : ""} />
        <span class="swatch" style="background:${categoryColorFor(c.name)}"></span>
        ${escapeHtml(c.name)}
      </label>`
    )
    .join("");

  radarFilterOptionsEl.querySelectorAll("[data-radar-filter-id]").forEach((input) => {
    input.addEventListener("change", () => {
      const id = input.dataset.radarFilterId;
      radarCategoryFilter = input.checked
        ? [...radarCategoryFilter, id]
        : radarCategoryFilter.filter((existing) => existing !== id);
      saveRadarCategoryFilter(radarCategoryFilter);
      radarFilterBtn.classList.toggle("active", radarCategoryFilter.length > 0);
      renderScheduleRadar();
    });
  });
}

radarFilterBtn.addEventListener("click", (e) => {
  e.stopPropagation();
  radarFilterPopover.classList.toggle("show");
});

document.addEventListener("click", (e) => {
  if (!radarFilterPopover.contains(e.target) && e.target !== radarFilterBtn) {
    radarFilterPopover.classList.remove("show");
  }
});

// ---------- 오늘 24시간 시계 (카테고리 필터와 무관하게 항상 전체 일정 기준) ----------

const STATUS_LABELS = Object.fromEntries(STATUS_COLUMNS.map((c) => [c.key, c.label]));
const STATUS_COLOR_VAR = {
  PENDING: "var(--color-pending)",
  IN_PROGRESS: "var(--color-progress)",
  COMPLETED: "var(--color-completed)",
  CANCELLED: "var(--color-cancelled)",
};
// 캘린더 뷰(월/주/일)의 일정 칩·블록 배경에 쓰는 옅은 버전 (STATUS_COLOR_VAR 와 짝을 이룬다)
const STATUS_BG_VAR = {
  PENDING: "var(--color-pending-bg)",
  IN_PROGRESS: "var(--color-progress-bg)",
  COMPLETED: "var(--color-completed-bg)",
  CANCELLED: "var(--color-cancelled-bg)",
};

const CLOCK_CENTER = 80;
const CLOCK_FACE_R = 74;
// 겹치는 일정은 최대 4개 레인까지 동심원으로 분리하고, 그 이상 겹치면 가장 먼저 비는 레인에 합친다
const CLOCK_LANE_R = [64, 54, 44, 34];
const CLOCK_ARC_WIDTH = 8;
const CLOCK_MIN_ARC_MINUTES = 8; // 아주 짧은 일정도 호가 보이도록 최소 폭을 보장한다

const clockSvgEl = document.getElementById("today-clock-svg");
const clockTooltipEl = document.getElementById("clock-tooltip");
const clockLegendEl = document.getElementById("today-clock-legend");
const clockFilterBtn = document.getElementById("clock-filter-btn");
const clockFilterPopover = document.getElementById("clock-filter-popover");
const clockFilterOptionsEl = document.getElementById("clock-filter-options");

// 카테고리별로 선택해서 보기 모드에서 쓰는 고정 순서 팔레트(색맹 안전성 검증된 8색, 밝은 배경 기준).
// 카테고리 id 오름차순으로 슬롯을 배정해, 사이드바에서 드래그로 순서를 바꿔도 색은 그대로 유지된다
const CATEGORY_COLOR_PALETTE = [
  "#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#4a3aa7", "#e34948",
];

function categoryColorFor(categoryName) {
  const sorted = [...categories].sort((a, b) => a.id - b.id);
  const idx = sorted.findIndex((c) => c.name === categoryName);
  if (idx === -1) return "var(--color-text-muted)";
  return CATEGORY_COLOR_PALETTE[idx % CATEGORY_COLOR_PALETTE.length];
}

// 시계 위젯의 카테고리 필터는 사용자 이메일별로 localStorage에 저장한다(카테고리 순서와 같은 이유로
// 서버에는 저장하지 않는다) - 선택된 카테고리 id 배열이며, 비어 있으면 "전체 보기 + 상태별 색상"
// (기존 동작), 하나 이상 선택되면 "그 카테고리만 표시 + 카테고리별 색상"으로 바뀐다
function clockCategoryFilterStorageKey() {
  const user = API.getCurrentUser();
  const email = (user && user.email) || "anonymous";
  return `clock-category-filter:${email}`;
}

function loadStoredClockCategoryFilter() {
  try {
    return JSON.parse(localStorage.getItem(clockCategoryFilterStorageKey())) || [];
  } catch (err) {
    return [];
  }
}

function saveClockCategoryFilter(ids) {
  localStorage.setItem(clockCategoryFilterStorageKey(), JSON.stringify(ids));
}

let clockCategoryFilter = loadStoredClockCategoryFilter();

function renderClockFilterOptions() {
  clockFilterOptionsEl.innerHTML = categories
    .map(
      (c) => `
      <label class="clock-filter-option">
        <input type="checkbox" data-clock-filter-id="${c.id}" ${clockCategoryFilter.includes(String(c.id)) ? "checked" : ""} />
        <span class="swatch" style="background:${categoryColorFor(c.name)}"></span>
        ${escapeHtml(c.name)}
      </label>`
    )
    .join("");

  clockFilterOptionsEl.querySelectorAll("[data-clock-filter-id]").forEach((input) => {
    input.addEventListener("change", () => {
      const id = input.dataset.clockFilterId;
      clockCategoryFilter = input.checked
        ? [...clockCategoryFilter, id]
        : clockCategoryFilter.filter((existing) => existing !== id);
      saveClockCategoryFilter(clockCategoryFilter);
      clockFilterBtn.classList.toggle("active", clockCategoryFilter.length > 0);
      renderTodayClock();
    });
  });
}

clockFilterBtn.addEventListener("click", (e) => {
  e.stopPropagation();
  clockFilterPopover.classList.toggle("show");
});

document.addEventListener("click", (e) => {
  if (!clockFilterPopover.contains(e.target) && e.target !== clockFilterBtn) {
    clockFilterPopover.classList.remove("show");
  }
});

function svgEl(tag, attrs) {
  const el = document.createElementNS("http://www.w3.org/2000/svg", tag);
  Object.entries(attrs || {}).forEach(([key, value]) => el.setAttribute(key, value));
  return el;
}

// angle 0 = 00:00(자정, 12시 방향), 시계 방향으로 증가
function polarPoint(r, angleDeg) {
  const rad = ((angleDeg - 90) * Math.PI) / 180;
  return { x: CLOCK_CENTER + r * Math.cos(rad), y: CLOCK_CENTER + r * Math.sin(rad) };
}

function describeClockArc(r, startAngle, endAngle) {
  const start = polarPoint(r, startAngle);
  const end = polarPoint(r, endAngle);
  const largeArc = endAngle - startAngle <= 180 ? 0 : 1;
  return `M ${start.x} ${start.y} A ${r} ${r} 0 ${largeArc} 1 ${end.x} ${end.y}`;
}

// 오늘(00:00~24:00)과 겹치는 일정만 골라 오늘 범위로 잘라내고 분 단위 구간으로 변환한다
function getTodaysScheduleWindows(list) {
  const now = new Date();
  const dayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const dayEnd = new Date(dayStart.getTime() + 24 * 60 * 60 * 1000);

  return list
    // 종료 시각이 없는(알림형) 일정은 시작 시각과 같은 시점으로 취급한다 - new Date(null)은 NaN이 아니라
    // 1970년으로 해석돼 버려서, endAt이 없으면 항상 오늘 범위 밖으로 걸러져 아예 표시되지 않는 문제가 있었다
    .map((s) => ({ ...s, start: new Date(s.startAt), end: new Date(s.endAt || s.startAt) }))
    .filter(
      (s) => !Number.isNaN(s.start.getTime()) && !Number.isNaN(s.end.getTime()) && s.end > dayStart && s.start < dayEnd
    )
    .map((s) => {
      const clampedStart = s.start < dayStart ? dayStart : s.start;
      const clampedEnd = s.end > dayEnd ? dayEnd : s.end;
      const startMin = (clampedStart - dayStart) / 60000;
      // 정확히 자정(1440분)까지 닿으면 시작점과 끝점이 겹쳐 호가 사라지므로 살짝 못 미치게 잘라낸다
      const endMin = Math.min(Math.max(startMin + CLOCK_MIN_ARC_MINUTES, (clampedEnd - dayStart) / 60000), 1439);
      return { ...s, startMin, endMin };
    })
    .sort((a, b) => a.startMin - b.startMin);
}

// 겹치는 일정을 동심원 레인으로 분리한다 (구간 그래프 그리디 채색). 레인이 다 차면 마지막 레인에
// 몰아넣지 않고 가장 먼저 비는 레인을 골라 합친다
function assignClockLanes(events) {
  const laneEndMin = [];
  return events.map((e) => {
    let lane = laneEndMin.findIndex((end) => e.startMin >= end);
    if (lane === -1) {
      if (laneEndMin.length < CLOCK_LANE_R.length) {
        lane = laneEndMin.length;
      } else {
        lane = laneEndMin.indexOf(Math.min(...laneEndMin));
      }
    }
    laneEndMin[lane] = Math.max(laneEndMin[lane] ?? -Infinity, e.endMin);
    return { ...e, lane };
  });
}

function hideClockTooltip() {
  clockTooltipEl.classList.remove("show");
}

// 라벨은 신뢰할 수 없는 데이터이므로 textContent 로만 채운다 (innerHTML 금지)
function showClockTooltip(anchorX, anchorY, schedule) {
  clockTooltipEl.textContent = "";

  const titleEl = document.createElement("div");
  titleEl.className = "tt-title";
  titleEl.textContent = schedule.title;

  const timeEl = document.createElement("div");
  timeEl.className = "tt-time";
  timeEl.textContent = formatTimeRange(schedule.startAt, schedule.endAt);

  const metaEl = document.createElement("div");
  metaEl.className = "tt-meta";
  const metaParts = [STATUS_LABELS[schedule.status] || schedule.status];
  if (schedule.categoryName) metaParts.push(schedule.categoryName);
  metaEl.textContent = metaParts.join(" · ");

  clockTooltipEl.appendChild(titleEl);
  clockTooltipEl.appendChild(timeEl);
  clockTooltipEl.appendChild(metaEl);

  clockTooltipEl.style.left = `${anchorX}px`;
  clockTooltipEl.style.top = `${anchorY}px`;
  clockTooltipEl.classList.add("show");
}

// 실제로 등장한 상태만 범례에 표시한다 (2개 이상 시리즈에는 항상 범례가 있어야 함)
function renderTodayClockLegend(usedStatuses) {
  clockLegendEl.textContent = "";
  STATUS_COLUMNS.forEach((col) => {
    if (!usedStatuses.has(col.key) || col.key === "COMPLETED") return;
    const item = document.createElement("div");
    item.className = "today-clock-legend-item";

    const dot = document.createElement("span");
    dot.className = `today-clock-legend-dot ${col.key}`;

    const label = document.createElement("span");
    label.textContent = col.label;

    item.appendChild(dot);
    item.appendChild(label);
    clockLegendEl.appendChild(item);
  });
}

// 카테고리 필터가 하나 이상 선택돼 있으면 그 카테고리만 표시한다 (usedNames만 범례에 남긴다)
function renderTodayClockCategoryLegend(usedNames) {
  clockLegendEl.textContent = "";
  categories
    .filter((c) => usedNames.has(c.name))
    .forEach((c) => {
      const item = document.createElement("div");
      item.className = "today-clock-legend-item";

      const dot = document.createElement("span");
      dot.className = "today-clock-legend-dot";
      dot.style.background = categoryColorFor(c.name);

      const label = document.createElement("span");
      label.textContent = c.name;

      item.appendChild(dot);
      item.appendChild(label);
      clockLegendEl.appendChild(item);
    });
}

function renderTodayClock() {
  // 카테고리 필터가 켜져 있으면(하나 이상 선택) 그 카테고리의 일정만 골라 카테고리별 색으로,
  // 꺼져 있으면(기본) 전체 일정을 상태별 색으로 보여준다
  const filterActive = clockCategoryFilter.length > 0;
  const selectedNames = new Set(
    categories.filter((c) => clockCategoryFilter.includes(String(c.id))).map((c) => c.name)
  );
  const sourceList = filterActive ? schedules.filter((s) => selectedNames.has(s.categoryName)) : schedules;
  const todays = assignClockLanes(getTodaysScheduleWindows(sourceList));

  const now = new Date();
  const isDaytime = now.getHours() >= 6 && now.getHours() < 18;

  clockSvgEl.textContent = "";
  // 낮/밤을 배경색으로도 바로 눈에 띄게 보여준다 - 눈금/바늘 등 나머지 색은 .clock-tick 등의
  // CSS에서 이 클래스로 스코프한 어두운 배경용 색으로 함께 바뀐다(style.css 참고)
  clockSvgEl.classList.toggle("is-night", !isDaytime);
  hideClockTooltip();

  clockSvgEl.appendChild(
    svgEl("circle", { class: "clock-face-bg", cx: CLOCK_CENTER, cy: CLOCK_CENTER, r: CLOCK_FACE_R })
  );
  clockSvgEl.appendChild(
    svgEl("circle", { class: "clock-face-ring", cx: CLOCK_CENTER, cy: CLOCK_CENTER, r: CLOCK_FACE_R })
  );

  // 12시간 눈금: 일반 시계처럼 1~12 전부 라벨을 붙인다(0시/12시는 "12"로 표시)
  for (let h = 0; h < 12; h++) {
    const angle = (h / 12) * 360;
    const inner = polarPoint(66, angle);
    const outer = polarPoint(CLOCK_FACE_R, angle);
    clockSvgEl.appendChild(
      svgEl("line", { class: "clock-tick", x1: inner.x, y1: inner.y, x2: outer.x, y2: outer.y })
    );
    const labelPt = polarPoint(56, angle);
    const label = svgEl("text", {
      class: "clock-tick-label",
      x: labelPt.x,
      y: labelPt.y + 3,
      "text-anchor": "middle",
    });
    label.textContent = String(h === 0 ? 12 : h);
    clockSvgEl.appendChild(label);
  }

  const usedStatuses = new Set();
  const usedCategoryNames = new Set();

  // 레인 배정(assignClockLanes)은 실제 24시간 기준 겹침으로 이미 끝난 뒤이므로, 각도만 12시간
  // 기준(720분 = 12*60)으로 접어 넣는다 - 오전/오후에 같은 시각의 일정이 있으면 각도가 겹칠 수 있는데
  // (일반 아날로그 시계와 같은 한계), 중앙의 해/달 아이콘으로 지금이 오전인지 오후인지 구분해준다
  todays.forEach((s) => {
    const r = CLOCK_LANE_R[s.lane];
    const startAngle = ((s.startMin % 720) / 720) * 360;
    let endAngle = ((s.endMin % 720) / 720) * 360;
    if (endAngle <= startAngle) endAngle += 360; // 11시대 -> 12시대처럼 12시간 경계를 넘어가는 구간 보정
    const d = describeClockArc(r, startAngle, endAngle);
    usedStatuses.add(s.status);
    if (s.categoryName) usedCategoryNames.add(s.categoryName);

    // 실제 마크보다 넓은 투명 stroke 를 히트 영역으로 써서 hover/focus 를 받는다
    const hit = svgEl("path", {
      class: "clock-arc-hit",
      d,
      "stroke-width": 18,
      tabindex: "0",
      role: "img",
      "aria-label": scheduleAriaLabel(s),
    });
    const arc = svgEl("path", {
      class: "clock-arc",
      d,
      stroke: filterActive ? categoryColorFor(s.categoryName) : (STATUS_COLOR_VAR[s.status] || "var(--color-text-muted)"),
      "stroke-width": CLOCK_ARC_WIDTH,
    });

    const midAngle = (startAngle + endAngle) / 2;
    const tooltipAnchor = polarPoint(r, midAngle);

    const onEnter = () => showClockTooltip(tooltipAnchor.x, tooltipAnchor.y - 6, s);
    hit.addEventListener("pointerenter", onEnter);
    hit.addEventListener("pointerleave", hideClockTooltip);
    hit.addEventListener("focus", onEnter);
    hit.addEventListener("blur", hideClockTooltip);

    clockSvgEl.appendChild(hit);
    clockSvgEl.appendChild(arc);
  });

  if (filterActive) {
    renderTodayClockCategoryLegend(usedCategoryNames);
  } else {
    renderTodayClockLegend(usedStatuses);
  }

  // 중앙: 해/달 아이콘만 크게 (06~18시는 해, 그 외는 달) - 12시간 다이얼은 오전/오후를 구분하지
  // 못하므로 이 아이콘이 그 역할을 한다. 원래 있던 "오늘 일정 개수" 숫자는 시계 중앙의 날짜창처럼
  // 보여 혼동을 줘서 뺐다
  const centerIcon = svgEl("text", {
    class: "clock-center-icon",
    x: CLOCK_CENTER,
    y: CLOCK_CENTER + 8,
    "text-anchor": "middle",
  });
  centerIcon.textContent = isDaytime ? "☀️" : "🌙";
  clockSvgEl.appendChild(centerIcon);

  // 현재 시각 표시선 - 12시간을 한 바퀴로 돈다(일반 아날로그 시계와 동일)
  const nowAngle = (((now.getHours() % 12) * 60 + now.getMinutes()) / 720) * 360;
  const nowInner = polarPoint(30, nowAngle);
  const nowOuter = polarPoint(CLOCK_FACE_R, nowAngle);
  clockSvgEl.appendChild(
    svgEl("line", { class: "clock-now-line", x1: nowInner.x, y1: nowInner.y, x2: nowOuter.x, y2: nowOuter.y })
  );
  clockSvgEl.appendChild(svgEl("circle", { class: "clock-now-dot", cx: nowOuter.x, cy: nowOuter.y, r: 3 }));
}

function scheduleAriaLabel(s) {
  return `${s.title}, ${formatTimeRange(s.startAt, s.endAt)}, ${STATUS_LABELS[s.status] || s.status}`;
}

function escapeHtml(str) {
  return String(str ?? "").replace(/[&<>"']/g, (m) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;",
  }[m]));
}

// ---------- 오늘 성취도 위젯 (진행률/사계절/12개월/산 높이 중 랜덤한 비유로 보여준다) ----------
// 시계 위젯과 같은 원칙으로 뷰 모드와 무관하게 항상 "오늘" 기준으로 계산한다
function getTodayCompletionStats() {
  const todayStart = startOfDay(new Date());
  const todayEnd = addDays(todayStart, 1);
  const todays = schedulesOverlappingRange(schedules, todayStart, todayEnd);
  const total = todays.length;
  const completed = todays.filter((s) => s.status === "COMPLETED").length;
  const percent = total > 0 ? Math.round((completed / total) * 100) : 0;
  return { total, completed, percent };
}

const ACHIEVEMENT_SEASONS = [
  { emoji: "🌱", label: "봄" },
  { emoji: "☀️", label: "여름" },
  { emoji: "🍁", label: "가을" },
  { emoji: "❄️", label: "겨울" },
];

// 산마다 실제 특징에 맞는 이모지를 따로 둔다(한라산은 화산, 에베레스트/지리산은 만년설 봉우리,
// 관악산/북한산은 그냥 동네 산 느낌의 일반 산 모양)
const ACHIEVEMENT_MOUNTAINS = [
  { name: "관악산", height: 632, emoji: "⛰️" },
  { name: "북한산", height: 836, emoji: "⛰️" },
  { name: "지리산 천왕봉", height: 1915, emoji: "🏔️" },
  { name: "한라산", height: 1947, emoji: "🌋" },
  { name: "에베레스트", height: 8849, emoji: "🏔️" },
];

// 1월~12월 각각의 계절감에 맞는 이모지
const ACHIEVEMENT_MONTH_EMOJIS = ["❄️", "💝", "🌸", "🌷", "🌳", "🌊", "🏖️", "🌻", "🍂", "🎃", "🍁", "🎄"];

// 서울 ~ 부산(경부고속도로, 약 400km) 구간을 진행률에 따라 11개 구간으로 나눠 위치를 고른다.
// 구간마다 그 지점다운 이모지를 따로 둔다(출발=도시, 최고지점=산, 경주=고도시, 도착=바다·항구)
const ACHIEVEMENT_BUSAN_ROUTE_TOTAL_KM = 400;
const ACHIEVEMENT_BUSAN_ROUTE = [
  { name: "서울 출발", road: "경부고속도로 기점 · 한남IC 인근", emoji: "🏙️" },
  { name: "기흥휴게소", road: "경부고속도로 · 경기 용인", emoji: "🚗" },
  { name: "안성휴게소", road: "경부고속도로 · 경기 안성", emoji: "🚗" },
  { name: "천안삼거리휴게소", road: "경부고속도로 · 충남 천안", emoji: "🚗" },
  { name: "옥산휴게소", road: "경부고속도로 · 충북 청주", emoji: "🚗" },
  { name: "추풍령휴게소", road: "경부고속도로 최고지점 · 충북·경북 경계", emoji: "⛰️" },
  { name: "김천(구미)휴게소", road: "경부고속도로 · 경북 김천", emoji: "🚗" },
  { name: "칠곡휴게소", road: "경부고속도로 · 경북 칠곡(대구 인근)", emoji: "🚗" },
  { name: "경주휴게소", road: "경부고속도로 · 경북 경주", emoji: "🏯" },
  { name: "양산휴게소", road: "경부고속도로 · 경남 양산", emoji: "🚗" },
  { name: "부산 도착", road: "경부고속도로 종점 · 부산", emoji: "🌊" },
];

// 위젯을 클릭할 때마다 이 중 하나를 새로 뽑는다. 산 비유는 "어떤 산인지"도 이때 같이 새로 뽑아
// 고정해둔다(같은 산을 유지해야 다음 렌더에서도 말이 되므로 - buildAchievementMetaphors()를
// 매번 새로 호출해 클로저에 새 산을 담아둔다)
function buildAchievementMetaphors() {
  const mountain = ACHIEVEMENT_MOUNTAINS[Math.floor(Math.random() * ACHIEVEMENT_MOUNTAINS.length)];
  return [
    (percent) => ({ emoji: "🔋", headline: `${percent}%`, detail: "오늘 일정 완료율" }),
    (percent) => {
      const season = ACHIEVEMENT_SEASONS[Math.min(3, Math.floor(percent / 25))];
      return { emoji: season.emoji, headline: season.label, detail: "사계절로 치면 지금 이맘때" };
    },
    (percent) => {
      const month = percent <= 0 ? 1 : Math.min(12, Math.ceil((percent / 100) * 12));
      return { emoji: ACHIEVEMENT_MONTH_EMOJIS[month - 1], headline: `${month}월`, detail: "1년 12개월로 치면" };
    },
    (percent) => {
      const meters = Math.round((mountain.height * percent) / 100);
      return { emoji: mountain.emoji, headline: `${meters}m`, detail: `${mountain.name}(${mountain.height}m) 등반 기준` };
    },
    (percent) => {
      const km = ((42.195 * percent) / 100).toFixed(1);
      return { emoji: "🏃", headline: `${km}km`, detail: "풀코스 마라톤(42.195km) 기준" };
    },
    (percent) => {
      const idx = Math.min(ACHIEVEMENT_BUSAN_ROUTE.length - 1, Math.round((percent / 100) * (ACHIEVEMENT_BUSAN_ROUTE.length - 1)));
      const spot = ACHIEVEMENT_BUSAN_ROUTE[idx];
      const km = Math.round((ACHIEVEMENT_BUSAN_ROUTE_TOTAL_KM * percent) / 100);
      return { emoji: spot.emoji, headline: spot.name, detail: `서울→부산 약 ${ACHIEVEMENT_BUSAN_ROUTE_TOTAL_KM}km 중 ${km}km · ${spot.road}` };
    },
  ];
}

function pickRandomAchievementMetaphor() {
  const metaphors = buildAchievementMetaphors();
  return metaphors[Math.floor(Math.random() * metaphors.length)];
}

let achievementMetaphor = pickRandomAchievementMetaphor();

function renderAchievementWidget() {
  const { total, percent } = getTodayCompletionStats();
  const view = achievementMetaphor(percent);
  document.getElementById("achievement-emoji").textContent = view.emoji;
  document.getElementById("achievement-headline").textContent = view.headline;
  document.getElementById("achievement-detail").textContent = total > 0 ? view.detail : "오늘 등록된 일정이 없어요";
  document.getElementById("achievement-fill").style.height = `${percent}%`;
  document.getElementById("achievement-emoji").style.bottom = `${percent}%`;
  document.getElementById("achievement-widget").classList.toggle("complete", total > 0 && percent === 100);
}

document.getElementById("achievement-widget").addEventListener("click", () => {
  achievementMetaphor = pickRandomAchievementMetaphor();
  renderAchievementWidget();
});

// 전체 접기(board.compact)가 꺼져 있으면 기본 펼침 + collapsedCardIds(개별로 접은 것만) 반영,
// 켜져 있으면 기본 접힘 + forceExpandedCardIds(개별로 펼친 것만) 반영 - 두 모드에서 기본값이
// 반대라 어느 쪽 예외 집합을 볼지도 바뀐다
function isCardVisuallyCollapsed(id) {
  const key = String(id);
  return board.classList.contains("compact") ? !forceExpandedCardIds.has(key) : collapsedCardIds.has(key);
}

function scheduleCardHtml(s) {
  const isCollapsed = isCardVisuallyCollapsed(s.id);
  return `
    <div class="schedule-card ${s.status}${isCollapsed ? " collapsed" : ""}" data-id="${s.id}" draggable="true">
      <div class="card-top">
        <div class="card-title">${escapeHtml(s.title)}</div>
        <button type="button" class="card-collapse-btn" data-card-collapse="${s.id}" title="${isCollapsed ? "일정 카드 펼치기" : "일정 카드 접기"}">${isCollapsed ? "▸" : "▾"}</button>
      </div>
      ${s.content ? `<p class="card-content">${escapeHtml(s.content)}</p>` : ""}
      <div class="card-meta">
        ${s.categoryName ? `<span class="tag category-tag">${escapeHtml(s.categoryName)}</span>` : ""}
      </div>
      <div class="card-time">${formatTimeRange(s.startAt, s.endAt)}${s.username ? ` · ${escapeHtml(s.username)}` : ""}</div>
      <div class="card-actions">
        <select data-status-for="${s.id}">
          ${STATUS_COLUMNS.map(
            (col) => `<option value="${col.key}" ${col.key === s.status ? "selected" : ""}>${col.label}</option>`
          ).join("")}
        </select>
        <button type="button" class="icon-btn" data-edit="${s.id}" title="수정">✎</button>
        <button type="button" class="icon-btn" data-delete="${s.id}" title="삭제">🗑</button>
      </div>
    </div>`;
}

// 보드 상태 컬럼 하나를 서버에서 페이징 조회한다 - 하루 범위(date)/카테고리 필터는 서버가 담당하고
// (ScheduleService.getBoardSchedules 참고), size 는 boardColumnVisibleCount(컬럼별로 "더보기"를 누른
// 만큼 커지는 값)를 그대로 실어 보낸다. offset 은 항상 0으로 두고 size 만 키우는 방식이라 별도의 클라이언트
// 배열 이어붙이기 없이 매번 "지금까지 펼친 만큼"을 정확히 다시 받아온다. date 는 today-nav 화살표로
// 옮겨다니는 viewDate를 그대로 실어 보낸다(전날/다음날 이동)
function toDateOnlyValue(d) {
  const pad = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

async function fetchBoardColumnPage(statusKey, size) {
  const params = new URLSearchParams({ status: statusKey, page: "0", size: String(size), date: toDateOnlyValue(viewDate) });
  if (activeCategoryId !== "") params.set("categoryId", activeCategoryId);
  return API.get(`/api/schedules/board?${params.toString()}`);
}

async function loadBoardColumns() {
  await Promise.all(
    STATUS_COLUMNS.map(async (col) => {
      const size = boardColumnVisibleCount.get(col.key) ?? BOARD_COLUMN_VISIBLE_LIMIT;
      try {
        const res = await fetchBoardColumnPage(col.key, size);
        boardColumnData.set(col.key, { items: res.content, totalElements: res.totalElements });
      } catch (err) {
        boardColumnData.set(col.key, { items: [], totalElements: 0 });
        showToast(`일정을 불러오지 못했습니다. ${err.message}`);
      }
    })
  );
}

function renderBoard() {
  board.innerHTML = STATUS_COLUMNS.map((col) => {
    const data = boardColumnData.get(col.key) ?? { items: [], totalElements: 0 };
    const items = data.items;
    const hiddenCount = data.totalElements - items.length;

    return `
      <div class="board-column ${col.key}" data-status-column="${col.key}">
        <div class="board-column-header">
          <div class="title"><span class="status-dot ${col.key}"></span>${col.label}</div>
          <div class="board-column-header-right">
            <span class="count-badge">${data.totalElements}</span>
            <button type="button" class="board-column-add-btn" data-create-in-column="${col.key}" title="${col.label}에 새 일정 추가">+</button>
          </div>
        </div>
        <div class="board-column-body">
          ${items.length ? items.map(scheduleCardHtml).join("") : `<div class="empty-hint">일정이 없습니다</div>`}
        </div>
        ${
          hiddenCount > 0
            ? `<button type="button" class="board-more-btn" data-toggle-more="${col.key}">더보기 (${hiddenCount})</button>`
            : ""
        }
      </div>`;
  }).join("");

  board.querySelectorAll("[data-toggle-more]").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const key = btn.dataset.toggleMore;
      const current = boardColumnVisibleCount.get(key) ?? BOARD_COLUMN_VISIBLE_LIMIT;
      boardColumnVisibleCount.set(key, current + BOARD_COLUMN_VISIBLE_LIMIT);
      const size = boardColumnVisibleCount.get(key);
      try {
        const res = await fetchBoardColumnPage(key, size);
        boardColumnData.set(key, { items: res.content, totalElements: res.totalElements });
      } catch (err) {
        showToast(`더 불러오지 못했습니다. ${err.message}`);
        return;
      }
      renderBoard();
    });
  });

  board.querySelectorAll("[data-create-in-column]").forEach((btn) => {
    btn.addEventListener("click", () => openCreateModal(btn.dataset.createInColumn));
  });

  board.querySelectorAll("[data-status-for]").forEach((sel) => {
    sel.addEventListener("change", async () => {
      const id = sel.dataset.statusFor;
      await updateScheduleStatus(id, sel.value);
    });
  });

  board.querySelectorAll("[data-card-collapse]").forEach((btn) => {
    btn.addEventListener("click", () => {
      const id = String(btn.dataset.cardCollapse);
      const card = btn.closest(".schedule-card");
      const collapsed = !card.classList.contains("collapsed");
      card.classList.toggle("collapsed", collapsed);
      btn.textContent = collapsed ? "▸" : "▾";
      btn.title = collapsed ? "일정 카드 펼치기" : "일정 카드 접기";
      // 전체 접기(board.compact)가 켜져 있을 땐 기본이 접힘이라 "펼침 예외"를 forceExpandedCardIds에,
      // 꺼져 있을 땐 기본이 펼침이라 "접힘 예외"를 collapsedCardIds에 반영한다(isCardVisuallyCollapsed와 동일 기준)
      if (board.classList.contains("compact")) {
        if (collapsed) forceExpandedCardIds.delete(id);
        else forceExpandedCardIds.add(id);
        saveForceExpandedCardIds();
      } else {
        if (collapsed) collapsedCardIds.add(id);
        else collapsedCardIds.delete(id);
        saveCollapsedCardIds();
      }
    });
  });

  board.querySelectorAll("[data-edit]").forEach((btn) => {
    btn.addEventListener("click", () => openEditModal(btn.dataset.edit));
  });

  board.querySelectorAll("[data-delete]").forEach((btn) => {
    btn.addEventListener("click", () => deleteSchedule(btn.dataset.delete));
  });

  // 카드를 마우스로 눌러 다른 상태 컬럼으로 끌어놓으면 상태 select와 같은 updateScheduleStatus()를
  // 그대로 재사용해 상태를 바꾼다 - 드롭 대상 컬럼은 카드 사이 좁은 틈이 아니라 컬럼 전체(헤더/더보기
  // 버튼 영역 포함)로 넉넉하게 잡는다
  board.querySelectorAll(".schedule-card").forEach((card) => {
    card.addEventListener("dragstart", (e) => {
      e.dataTransfer.effectAllowed = "move";
      e.dataTransfer.setData("text/plain", card.dataset.id); // Firefox는 setData 없으면 드래그 자체가 시작되지 않는다
      card.classList.add("dragging");
    });
    card.addEventListener("dragend", () => card.classList.remove("dragging"));

    // 카드를 클릭하면(상태 select·수정·삭제 버튼이 아닌 부분) 상세보기 모달을 띄운다 - 수정 폼이
    // 아니라 읽기 전용 화면이고, 거기서 "수정"을 눌러야 기존 입력 폼(openEditModal)이 열린다
    card.addEventListener("click", (e) => {
      if (e.target.closest("select, button")) return;
      openDetailModal(card.dataset.id);
    });
  });

  board.querySelectorAll("[data-status-column]").forEach((columnEl) => {
    columnEl.addEventListener("dragover", (e) => {
      e.preventDefault();
      e.dataTransfer.dropEffect = "move";
      columnEl.classList.add("drop-target");
    });
    columnEl.addEventListener("dragleave", (e) => {
      // 컬럼 내부 자식 요소 사이를 이동할 때마다 dragleave가 계속 발생해 깜빡이지 않도록,
      // 실제로 컬럼 바깥으로 나갈 때만 하이라이트를 지운다
      if (!columnEl.contains(e.relatedTarget)) columnEl.classList.remove("drop-target");
    });
    columnEl.addEventListener("drop", async (e) => {
      e.preventDefault();
      columnEl.classList.remove("drop-target");
      const id = e.dataTransfer.getData("text/plain");
      const targetStatus = columnEl.dataset.statusColumn;
      const schedule = schedules.find((x) => String(x.id) === String(id));
      if (!schedule || schedule.status === targetStatus) return;
      await updateScheduleStatus(id, targetStatus);
    });
  });
}

// ---------- 일 / 주 / 월 / 년 캘린더 뷰 ----------

const WEEKDAY_LABELS_KO = ["일", "월", "화", "수", "목", "금", "토"];
const HOUR_PX = 48; // 타임그리드(일/주간)에서 1시간이 차지하는 픽셀 높이

const viewSwitcherEl = document.getElementById("view-switcher");
const viewNavEl = document.getElementById("view-nav");
const viewRangeLabelEl = document.getElementById("view-range-label");
const calendarViewEl = document.getElementById("calendar-view");
const todayNavEl = document.getElementById("today-nav");

function startOfDay(d) {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate());
}

function addDays(d, n) {
  const r = new Date(d);
  r.setDate(r.getDate() + n);
  return r;
}

function addMonths(d, n) {
  const r = new Date(d);
  r.setMonth(r.getMonth() + n);
  return r;
}

function addYears(d, n) {
  const r = new Date(d);
  r.setFullYear(r.getFullYear() + n);
  return r;
}

function isSameDay(a, b) {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}

// 일요일 시작 주의 첫 날
function startOfWeek(d) {
  const r = startOfDay(d);
  r.setDate(r.getDate() - r.getDay());
  return r;
}

// [rangeStart, rangeEnd) 와 겹치는 일정만 골라 Date 객체를 함께 붙여 반환한다
function schedulesOverlappingRange(list, rangeStart, rangeEnd) {
  return list
    // getTodaysScheduleWindows와 같은 이유로 종료 시각이 없으면 시작 시각과 같은 시점으로 취급한다
    .map((s) => ({ ...s, start: new Date(s.startAt), end: new Date(s.endAt || s.startAt) }))
    .filter(
      (s) => !Number.isNaN(s.start.getTime()) && !Number.isNaN(s.end.getTime()) && s.end > rangeStart && s.start < rangeEnd
    );
}

// 겹치는 일정을 좌우 레인으로 나눈다 (구간 그래프 그리디 채색) - 오늘 시계 위젯의 레인 로직과 동일한 방식
function assignHorizontalLanes(events) {
  const laneEndMin = [];
  return events.map((e) => {
    let lane = laneEndMin.findIndex((end) => e.startMin >= end);
    if (lane === -1) lane = laneEndMin.length;
    laneEndMin[lane] = e.endMin;
    return { ...e, lane };
  });
}

function switchView(mode) {
  viewMode = mode;
  viewSwitcherEl.querySelectorAll(".view-tab").forEach((btn) => {
    btn.classList.toggle("active", btn.dataset.view === mode);
  });
  viewNavEl.classList.toggle("show", mode !== "board");
  todayNavEl.classList.toggle("show", mode === "board");
  board.classList.toggle("hide", mode !== "board");
  calendarViewEl.classList.toggle("show", mode !== "board");
  refreshVisibleView();
}

function navigateView(dir) {
  if (viewMode === "day") viewDate = addDays(viewDate, dir);
  else if (viewMode === "week") viewDate = addDays(viewDate, dir * 7);
  else if (viewMode === "month") viewDate = addMonths(viewDate, dir);
  else if (viewMode === "year") viewDate = addYears(viewDate, dir);
  refreshVisibleView();
}

// 현재 활성화된 뷰(보드 또는 캘린더)만 다시 그린다 - 데이터 새로고침 후에도 이 함수를 통해 화면을 갱신한다
function refreshVisibleView() {
  // 레이더/카테고리별 카운트/시계 위젯 모두 뷰·날짜 전환마다 그 시점의 집계 범위로 다시 그려야 한다.
  // 성취도 위젯은 뷰 모드와 무관하게 항상 "오늘" 기준이지만, 일정이 새로 생기거나 상태가 바뀔 때(=
  // refreshVisibleView가 호출될 때)마다 최신 완료율을 반영해야 하므로 여기서 같이 갱신한다
  renderToday();
  renderScheduleRadar();
  renderCategoryCounts();
  renderTodayClock();
  renderAchievementWidget();
  if (viewMode === "board") {
    loadBoardColumns().then(renderBoard);
    return;
  }
  updateViewRangeLabel();
  if (viewMode === "day") renderDayOrWeekView(1);
  else if (viewMode === "week") renderDayOrWeekView(7);
  else if (viewMode === "month") renderMonthView();
  else if (viewMode === "year") renderYearView();
}

// 뷰 단위에 맞춰 화살표 title(hover 툴팁)도 "전날/다음날", "지난주/다음주"처럼 바뀐다
const VIEW_NAV_ARROW_TITLES = {
  day: ["전날", "다음날"],
  week: ["지난주", "다음주"],
  month: ["지난달", "다음달"],
  year: ["작년", "내년"],
};

function updateViewRangeLabel() {
  const [prevTitle, nextTitle] = VIEW_NAV_ARROW_TITLES[viewMode] || ["이전", "다음"];
  document.getElementById("view-nav-prev").title = prevTitle;
  document.getElementById("view-nav-next").title = nextTitle;

  if (viewMode === "day") {
    viewRangeLabelEl.textContent = viewDate.toLocaleDateString("ko-KR", {
      year: "numeric",
      month: "long",
      day: "numeric",
      weekday: "short",
    });
  } else if (viewMode === "week") {
    const start = startOfWeek(viewDate);
    const end = addDays(start, 6);
    viewRangeLabelEl.textContent =
      start.getMonth() === end.getMonth()
        ? `${start.getFullYear()}년 ${start.getMonth() + 1}월 ${start.getDate()}일 - ${end.getDate()}일`
        : `${start.getMonth() + 1}월 ${start.getDate()}일 - ${end.getMonth() + 1}월 ${end.getDate()}일`;
  } else if (viewMode === "month") {
    viewRangeLabelEl.textContent = `${viewDate.getFullYear()}년 ${viewDate.getMonth() + 1}월`;
  } else if (viewMode === "year") {
    viewRangeLabelEl.textContent = `${viewDate.getFullYear()}년`;
  }
}

// -- 월간: 7x6 날짜 그리드 --

function renderMonthView() {
  const year = viewDate.getFullYear();
  const month = viewDate.getMonth();
  const gridStart = startOfWeek(new Date(year, month, 1));
  const today = new Date();
  const list = visibleSchedules();
  const MAX_VISIBLE_PER_DAY = 3;

  const weekdayRow = document.createElement("div");
  weekdayRow.className = "cal-weekday-row";
  WEEKDAY_LABELS_KO.forEach((w) => {
    const cell = document.createElement("div");
    cell.className = "cal-weekday";
    cell.textContent = w;
    weekdayRow.appendChild(cell);
  });

  const grid = document.createElement("div");
  grid.className = "cal-month-grid";

  for (let i = 0; i < 42; i++) {
    const cellDate = addDays(gridStart, i);
    const cell = document.createElement("div");
    cell.className = "cal-month-cell";
    if (cellDate.getMonth() !== month) cell.classList.add("other-month");

    const dateNum = document.createElement("div");
    dateNum.className = "cal-date-num";
    if (isSameDay(cellDate, today)) dateNum.classList.add("is-today");
    dateNum.textContent = String(cellDate.getDate());
    dateNum.addEventListener("click", () => {
      viewDate = cellDate;
      switchView("day");
    });
    cell.appendChild(dateNum);

    const dayStart = startOfDay(cellDate);
    const dayEnd = addDays(dayStart, 1);
    const dayEvents = schedulesOverlappingRange(list, dayStart, dayEnd).sort((a, b) => a.start - b.start);

    dayEvents.slice(0, MAX_VISIBLE_PER_DAY).forEach((s) => {
      const chip = document.createElement("div");
      chip.className = "cal-event-chip";
      chip.style.background = STATUS_BG_VAR[s.status] || "var(--color-bg)";
      chip.style.color = STATUS_COLOR_VAR[s.status] || "var(--color-text-muted)";
      chip.style.borderLeftColor = STATUS_COLOR_VAR[s.status] || "var(--color-text-muted)";
      chip.textContent = `${formatTimeOnly(s.startAt)} ${s.title}`;
      chip.addEventListener("click", () => openDetailModal(s.id));
      cell.appendChild(chip);
    });

    if (dayEvents.length > MAX_VISIBLE_PER_DAY) {
      const more = document.createElement("div");
      more.className = "cal-more-link";
      more.textContent = `+${dayEvents.length - MAX_VISIBLE_PER_DAY}개`;
      more.addEventListener("click", () => {
        viewDate = cellDate;
        switchView("day");
      });
      cell.appendChild(more);
    }

    grid.appendChild(cell);
  }

  calendarViewEl.textContent = "";
  calendarViewEl.appendChild(weekdayRow);
  calendarViewEl.appendChild(grid);
}

// -- 주간 / 일간: 0~24시 타임그리드 (numDays = 1 이면 일간, 7이면 주간) --

function renderDayOrWeekView(numDays) {
  const rangeStart = numDays === 1 ? startOfDay(viewDate) : startOfWeek(viewDate);
  const days = Array.from({ length: numDays }, (_, i) => addDays(rangeStart, i));
  const today = new Date();
  const list = visibleSchedules();

  const wrap = document.createElement("div");
  wrap.className = "cal-time-grid-wrap";

  const header = document.createElement("div");
  header.className = "cal-time-header";
  header.appendChild(Object.assign(document.createElement("div"), { className: "cal-time-header-spacer" }));
  days.forEach((d) => {
    const dayHeader = document.createElement("div");
    dayHeader.className = "cal-time-header-day";
    if (isSameDay(d, today)) dayHeader.classList.add("is-today");
    const dow = document.createElement("span");
    dow.className = "dow";
    dow.textContent = WEEKDAY_LABELS_KO[d.getDay()];
    dayHeader.appendChild(dow);
    dayHeader.appendChild(document.createTextNode(String(d.getDate())));
    header.appendChild(dayHeader);
  });

  const body = document.createElement("div");
  body.className = "cal-time-body";

  const axis = document.createElement("div");
  axis.className = "cal-time-axis";
  axis.style.height = `${24 * HOUR_PX}px`;
  for (let h = 0; h < 24; h++) {
    const label = document.createElement("div");
    label.className = "cal-hour-label";
    label.style.top = `${h * HOUR_PX}px`;
    label.textContent = `${String(h).padStart(2, "0")}:00`;
    axis.appendChild(label);
  }

  const columnsWrap = document.createElement("div");
  columnsWrap.className = "cal-time-columns";
  columnsWrap.style.height = `${24 * HOUR_PX}px`;

  days.forEach((d) => {
    const col = document.createElement("div");
    col.className = "cal-day-column";

    for (let h = 0; h < 24; h++) {
      const line = document.createElement("div");
      line.className = "cal-hour-line";
      line.style.top = `${h * HOUR_PX}px`;
      col.appendChild(line);
    }

    const dayStart = startOfDay(d);
    const dayEnd = addDays(dayStart, 1);
    const dayEvents = schedulesOverlappingRange(list, dayStart, dayEnd)
      .map((s) => {
        const clampedStart = s.start < dayStart ? dayStart : s.start;
        const clampedEnd = s.end > dayEnd ? dayEnd : s.end;
        const startMin = (clampedStart - dayStart) / 60000;
        const endMin = Math.max(startMin + 20, (clampedEnd - dayStart) / 60000); // 최소 20분 높이 보장
        return { ...s, startMin, endMin };
      })
      .sort((a, b) => a.startMin - b.startMin);

    const laned = assignHorizontalLanes(dayEvents);
    const laneCount = laned.reduce((max, e) => Math.max(max, e.lane + 1), 1);

    laned.forEach((s) => {
      const block = document.createElement("div");
      block.className = "cal-event-block";
      block.style.top = `${(s.startMin / 60) * HOUR_PX}px`;
      block.style.height = `${((s.endMin - s.startMin) / 60) * HOUR_PX - 2}px`;
      const width = 100 / laneCount;
      block.style.left = `${s.lane * width}%`;
      block.style.width = `calc(${width}% - 3px)`;
      block.style.background = STATUS_BG_VAR[s.status] || "var(--color-bg)";
      block.style.color = STATUS_COLOR_VAR[s.status] || "var(--color-text-muted)";
      block.style.borderLeftColor = STATUS_COLOR_VAR[s.status] || "var(--color-text-muted)";

      const titleEl = document.createElement("div");
      titleEl.textContent = s.title;
      const timeEl = document.createElement("span");
      timeEl.className = "cal-event-time";
      timeEl.textContent = formatTimeRange(s.startAt, s.endAt);
      block.appendChild(titleEl);
      block.appendChild(timeEl);

      block.addEventListener("click", () => openDetailModal(s.id));
      col.appendChild(block);
    });

    if (isSameDay(d, today)) {
      const nowMin = today.getHours() * 60 + today.getMinutes();
      const nowLine = document.createElement("div");
      nowLine.className = "cal-now-line";
      nowLine.style.top = `${(nowMin / 60) * HOUR_PX}px`;
      col.appendChild(nowLine);
    }

    columnsWrap.appendChild(col);
  });

  body.appendChild(axis);
  body.appendChild(columnsWrap);
  wrap.appendChild(header);
  wrap.appendChild(body);

  calendarViewEl.textContent = "";
  calendarViewEl.appendChild(wrap);

  // 기본 스크롤 위치를 업무시간대(07:00) 근처로 맞춘다
  body.scrollTop = Math.max(0, 7 * HOUR_PX - 40);
}

// -- 연간: 12개월 미니 달력 --

function renderYearView() {
  const year = viewDate.getFullYear();
  const today = new Date();
  const list = visibleSchedules();

  const grid = document.createElement("div");
  grid.className = "cal-year-grid";

  for (let m = 0; m < 12; m++) {
    const monthStart = new Date(year, m, 1);
    const gridStart = startOfWeek(monthStart);

    const box = document.createElement("div");
    box.className = "cal-mini-month";

    const title = document.createElement("div");
    title.className = "cal-mini-month-title";
    title.textContent = `${m + 1}월`;
    title.addEventListener("click", () => {
      viewDate = monthStart;
      switchView("month");
    });
    box.appendChild(title);

    const miniGrid = document.createElement("div");
    miniGrid.className = "cal-mini-grid";

    for (let i = 0; i < 42; i++) {
      const cellDate = addDays(gridStart, i);
      const cell = document.createElement("div");
      cell.className = "cal-mini-cell";

      if (cellDate.getMonth() !== m) {
        cell.classList.add("other-month");
      } else {
        cell.textContent = String(cellDate.getDate());
        if (isSameDay(cellDate, today)) cell.classList.add("is-today");
        const dayStart = startOfDay(cellDate);
        const dayEnd = addDays(dayStart, 1);
        if (schedulesOverlappingRange(list, dayStart, dayEnd).length > 0) cell.classList.add("has-events");
        cell.addEventListener("click", () => {
          viewDate = cellDate;
          switchView("day");
        });
      }
      miniGrid.appendChild(cell);
    }

    box.appendChild(miniGrid);
    grid.appendChild(box);
  }

  calendarViewEl.textContent = "";
  calendarViewEl.appendChild(grid);
}

viewSwitcherEl.querySelectorAll(".view-tab").forEach((btn) => {
  btn.addEventListener("click", () => switchView(btn.dataset.view));
});
document.getElementById("view-nav-prev").addEventListener("click", () => navigateView(-1));
document.getElementById("view-nav-next").addEventListener("click", () => navigateView(1));

// 보드 뷰 전용 전날/다음날 이동 - viewDate를 하루씩 옮기고 나머지는 refreshVisibleView()가
// (오늘 라벨/레이더/카테고리 카운트/보드 컬럼 재조회까지) 그대로 처리한다
document.getElementById("today-nav-prev").addEventListener("click", () => {
  viewDate = addDays(viewDate, -1);
  refreshVisibleView();
});
document.getElementById("today-nav-next").addEventListener("click", () => {
  viewDate = addDays(viewDate, 1);
  refreshVisibleView();
});

async function updateScheduleStatus(id, status) {
  const s = schedules.find((x) => String(x.id) === String(id));
  if (!s) return;
  const meta = scheduleMeta.get(String(id)) || {};
  const cat = categories.find((c) => c.name === s.categoryName);
  try {
    await API.put(`/api/schedules/${id}`, {
      title: s.title,
      content: s.content,
      startAt: s.startAt,
      endAt: s.endAt,
      status,
      userId: meta.userId ?? null,
      categoryId: meta.categoryId ?? (cat ? cat.id : null),
    });
    showToast("상태를 변경했습니다.");
    await refreshAll();
  } catch (err) {
    const refreshed = await notifyScheduleMutationError(err, "상태 변경");
    if (!refreshed) renderBoard();
  }
}

async function deleteSchedule(id) {
  if (!confirm("이 일정을 삭제할까요?")) return;
  try {
    await API.del(`/api/schedules/${id}`);
    scheduleMeta.delete(String(id));
    showToast("일정을 삭제했습니다.");
    await refreshAll();
  } catch (err) {
    await notifyScheduleMutationError(err, "삭제");
  }
}

// ---------- 생성/수정 모달 ----------

const modalOverlay = document.getElementById("schedule-modal-overlay");
const scheduleForm = document.getElementById("schedule-form");
const modalTitle = document.getElementById("modal-title");
const startAtInput = document.getElementById("startAt");
const endAtInput = document.getElementById("endAt");
const noEndTimeToggle = document.getElementById("no-end-time-toggle");
const endTimeField = document.getElementById("end-time-field");
const scheduleTimeRow = document.getElementById("schedule-time-row");

const startAtSync = bindDateTimeGroup(
  startAtInput,
  document.getElementById("startAt-date"),
  document.getElementById("startAt-hour"),
  document.getElementById("startAt-minute")
);
const endAtSync = bindDateTimeGroup(
  endAtInput,
  document.getElementById("endAt-date"),
  document.getElementById("endAt-hour"),
  document.getElementById("endAt-minute")
);
const endAtVisibleInputs = [
  document.getElementById("endAt-date"),
  document.getElementById("endAt-hour"),
  document.getElementById("endAt-minute"),
];

// 알림형(종료 시간 없음) 토글 상태를 화면에 반영한다: 종료 필드를 숨기고 required를 풀어서
// "시작 시간만"으로도 제출이 가능하게 한다. 다시 껐을 때 종료 필드가 비어 있으면
// syncEndWithStart()로 시작+1시간 기본값을 채워준다
function applyEndTimeToggleUI() {
  const noEnd = noEndTimeToggle.checked;
  endTimeField.style.display = noEnd ? "none" : "";
  scheduleTimeRow.classList.toggle("single-col", noEnd);
  // endAtInput은 hidden이라 required가 있어도 브라우저 폼 검증에서 애초에 제외되므로(hidden input은
  // constraint validation 대상이 아님), 실제 눈에 보이는 date/시/분 입력 쪽에 required를 걸어야 한다
  endAtVisibleInputs.forEach((el) => { el.required = !noEnd; });
  if (noEnd) {
    endAtInput.value = "";
    endAtSync.syncVisibleFromHidden();
  } else if (!endAtInput.value) {
    syncEndWithStart();
  }
}
noEndTimeToggle.addEventListener("change", applyEndTimeToggleUI);

// 시작 시각이 바뀔 때마다 기존 지속시간(종료-시작)을 그대로 유지한 채 종료 시각을 같이 옮겨준다 -
// 매번 종료 시각까지 따로 맞출 필요 없이 시작 시각만 조정하면 되도록. lastStartValue 는 모달을 열
// 때마다(생성/수정) 초기 시작값으로 리셋해서, 그 다음 change 부터 상대적인 이동량을 계산한다
let lastStartValue = null;

function syncEndWithStart() {
  const newStart = new Date(startAtInput.value);
  if (!startAtInput.value || Number.isNaN(newStart.getTime())) return;

  const prevStart = lastStartValue ? new Date(lastStartValue) : null;
  const oldEnd = endAtInput.value ? new Date(endAtInput.value) : null;

  if (prevStart && oldEnd && !Number.isNaN(oldEnd.getTime())) {
    const durationMs = oldEnd.getTime() - prevStart.getTime();
    endAtInput.value = toDatetimeLocalValue(new Date(newStart.getTime() + Math.max(durationMs, 0)));
  } else {
    endAtInput.value = toDatetimeLocalValue(new Date(newStart.getTime() + 60 * 60 * 1000));
  }
  endAtSync.syncVisibleFromHidden();
  lastStartValue = startAtInput.value;
}

startAtInput.addEventListener("change", () => {
  if (!noEndTimeToggle.checked) syncEndWithStart();
});

// 현재 시각을 15분 단위로 올림한다 - 모달을 열었을 때 기본값이 10:00, 10:15처럼 깔끔한 시각으로
// 시작하도록 하기 위한 편의 기본값일 뿐, input의 step은 60(1분 단위)이라 사용자가 임의의 분으로
// 직접 바꾸는 건 자유롭다
function roundUpToQuarterHour(date) {
  const ms = 15 * 60 * 1000;
  return new Date(Math.ceil(date.getTime() / ms) * ms);
}

// initialStatus: 보드 컬럼 헤더의 "+"(data-create-in-column, renderBoard())로 열면 그 컬럼의 상태를
// 미리 선택해둔다 - 상단 "+ 새 일정"처럼 전체 어디서든 열 수 있는 경로는 인자 없이 호출되어 기본값
// PENDING("대기")으로 열린다
function openCreateModal(initialStatus) {
  // AI 채팅에서 "수동 등록"으로 열었을 때만 openCreateModalFromAiSuggestion()이 바로 뒤에서 다시
  // 채워준다 - "+"로 직접 연 경우처럼 그 외의 모든 경로는 여기서 항상 초기화해 이전에 남아있던
  // 값이 엉뚱한 일정에 잘못 연결되지 않게 한다
  pendingAiChatRegisterMessageId = null;
  pendingAiChatRegisterItemId = null;
  modalTitle.textContent = "새 일정";
  scheduleForm.reset();
  document.getElementById("schedule-id").value = "";
  document.getElementById("status-select").value = initialStatus || "PENDING";
  document.getElementById("user-id-input").value = (API.getCurrentUser() && API.getCurrentUser().id) || "";
  // 사이드바에서 특정 카테고리로 필터링해둔 채로 "+"를 눌러 새 일정을 만들면, 그 필터와 무관하게
  // 항상 목록 맨 위 카테고리로 기본 선택돼 있어서 저장 후 필터된 화면엔 안 보이는(전체 일정에서만
  // 보이는) 문제가 있었다 - 지금 보고 있는 카테고리가 있으면 그걸 기본값으로 쓴다
  const activeCat = categories.find((c) => String(c.id) === String(activeCategoryId));
  if (activeCat) {
    categorySelect.value = String(activeCat.id);
  } else if (categories.length) {
    categorySelect.value = String(categories[0].id);
  }

  const defaultStart = roundUpToQuarterHour(new Date());
  const defaultEnd = new Date(defaultStart.getTime() + 60 * 60 * 1000);
  startAtInput.value = toDatetimeLocalValue(defaultStart);
  endAtInput.value = toDatetimeLocalValue(defaultEnd);
  startAtSync.syncVisibleFromHidden();
  endAtSync.syncVisibleFromHidden();
  lastStartValue = startAtInput.value;
  noEndTimeToggle.checked = false;
  applyEndTimeToggleUI();

  modalOverlay.classList.add("show");
}

// ---------- 상세보기 모달 ----------
// 일정을 클릭하면 바로 입력 폼(openEditModal)이 아니라 읽기 전용으로 제목/내용/시간/카테고리/상태를
// 보여주는 이 모달이 먼저 뜬다. "수정"을 눌러야 기존 입력 폼으로 넘어가고, "삭제"는 바로 이 화면에서
// 처리한다(기존 deleteSchedule의 confirm()을 그대로 재사용 - 취소하면 상세 모달은 그대로 열려있는다)

const detailModalOverlay = document.getElementById("schedule-detail-modal-overlay");
const detailTitleEl = document.getElementById("detail-title");
const detailCategoryEl = document.getElementById("detail-category");
const detailStatusDotEl = document.getElementById("detail-status-dot");
const detailStatusLabelEl = document.getElementById("detail-status-label");
const detailTimeEl = document.getElementById("detail-time");
const detailContentEl = document.getElementById("detail-content");
const detailMetaEl = document.getElementById("detail-meta");

function openDetailModal(id) {
  const s = schedules.find((x) => String(x.id) === String(id));
  if (!s) return;

  detailModalOverlay.dataset.scheduleId = id;
  detailTitleEl.textContent = s.title;
  detailCategoryEl.textContent = s.categoryName || "";
  detailStatusDotEl.className = `status-dot ${s.status}`;
  detailStatusLabelEl.textContent = STATUS_LABELS[s.status] || s.status;
  detailTimeEl.textContent = formatTimeRange(s.startAt, s.endAt);
  detailContentEl.textContent = s.content || "";
  detailMetaEl.textContent = s.username ? `작성자: ${s.username}` : "";

  detailModalOverlay.classList.add("show");
}

function closeDetailModal() {
  detailModalOverlay.classList.remove("show");
}

document.getElementById("close-detail-modal-btn").addEventListener("click", closeDetailModal);
detailModalOverlay.addEventListener("click", (e) => {
  if (e.target === detailModalOverlay) closeDetailModal();
});

document.getElementById("detail-edit-btn").addEventListener("click", () => {
  const id = detailModalOverlay.dataset.scheduleId;
  closeDetailModal();
  openEditModal(id);
});

document.getElementById("detail-delete-btn").addEventListener("click", async () => {
  const id = detailModalOverlay.dataset.scheduleId;
  await deleteSchedule(id);
  // confirm()에서 취소했거나 삭제가 실패했다면 목록에 그대로 남아있으므로 상세 모달을 닫지 않는다
  if (!schedules.some((s) => String(s.id) === String(id))) closeDetailModal();
});

function openEditModal(id) {
  const s = schedules.find((x) => String(x.id) === String(id));
  if (!s) return;
  const meta = scheduleMeta.get(String(id)) || {};

  modalTitle.textContent = "일정 수정";
  document.getElementById("schedule-id").value = s.id;
  document.getElementById("title").value = s.title;
  document.getElementById("content").value = s.content || "";
  startAtInput.value = toDatetimeLocalValue(s.startAt);
  endAtInput.value = toDatetimeLocalValue(s.endAt);
  startAtSync.syncVisibleFromHidden();
  endAtSync.syncVisibleFromHidden();
  lastStartValue = startAtInput.value;
  noEndTimeToggle.checked = !s.endAt;
  applyEndTimeToggleUI();
  document.getElementById("status-select").value = s.status;

  const cat = categories.find((c) => c.name === s.categoryName);
  categorySelect.value = String(meta.categoryId ?? (cat ? cat.id : ""));

  const currentUser = API.getCurrentUser();
  document.getElementById("user-id-input").value = meta.userId ?? (currentUser && currentUser.id) ?? "";

  modalOverlay.classList.add("show");
}

function closeModal() {
  modalOverlay.classList.remove("show");
}

// openCreateModal(event)로 그대로 넘기면 클릭 Event 객체가 initialStatus 자리에 들어가버리므로
// (진리값이라 "PENDING" 기본값 폴백도 안 먹는다) 인자 없이 명시적으로 호출한다
document.getElementById("open-create-modal").addEventListener("click", () => openCreateModal());
document.getElementById("cancel-modal-btn").addEventListener("click", closeModal);
modalOverlay.addEventListener("click", (e) => {
  if (e.target === modalOverlay) closeModal();
});

scheduleForm.addEventListener("submit", async (e) => {
  e.preventDefault();

  const id = document.getElementById("schedule-id").value;
  const categoryId = Number(categorySelect.value);
  const userId = Number(document.getElementById("user-id-input").value);

  const payload = {
    title: document.getElementById("title").value.trim(),
    content: document.getElementById("content").value.trim(),
    startAt: document.getElementById("startAt").value,
    endAt: noEndTimeToggle.checked ? null : document.getElementById("endAt").value,
    status: document.getElementById("status-select").value,
    userId,
    categoryId,
  };

  try {
    let saved;
    if (id) {
      saved = await API.put(`/api/schedules/${id}`, payload);
    } else {
      saved = await API.post("/api/schedules", payload);
    }
    const savedId = String((saved && saved.id) || id);
    scheduleMeta.set(savedId, { categoryId, userId });
    if (API.getCurrentUser() && !API.getCurrentUser().id) {
      const user = API.getCurrentUser();
      API.setCurrentUser(Object.assign({}, user, { id: userId }));
    }
    // AI 채팅의 "수동 등록"으로 채워 넣은 폼이었다면(id 없이 새로 만든 경우만), 방금 저장된 일정과
    // 그 채팅 메시지를 서버에 연결해둔다 - 실패해도 일정 저장 자체는 이미 끝난 뒤라 조용히 무시한다
    if (!id && pendingAiChatRegisterMessageId) {
      await linkAiChatMessageToSchedule(pendingAiChatRegisterMessageId, Number(savedId), pendingAiChatRegisterItemId);
      pendingAiChatRegisterMessageId = null;
      pendingAiChatRegisterItemId = null;
    }
    closeModal();
    showToast(id ? "일정을 수정했습니다." : "일정을 추가했습니다.");
    await refreshAll();
  } catch (err) {
    const refreshed = await notifyScheduleMutationError(err, id ? "수정" : "저장");
    if (refreshed) closeModal();
  }
});

// ---------- 반복 일정 ----------
// 영양제 먹기/운동/식단처럼 매번 손으로 새 일정을 만들기 귀찮은 것들을 위한 기능 - 여기서는 규칙(요일/
// 시간/기간)만 한 번 서버에 등록하면, 서버(RecurringScheduleService)가 실제 일정을 미리 여러 개
// 만들어둔다. 그렇게 만들어진 일정 하나하나는 그냥 평범한 일정이라 상세보기/수정/삭제/상태변경 모두
// 기존 기능을 그대로 쓸 수 있고, 반복 자체를 그만두는 것만 /settings 페이지에서 따로 한다

const recurringModalOverlay = document.getElementById("recurring-modal-overlay");
const recurringForm = document.getElementById("recurring-form");
const recurringWeekdayPicker = document.getElementById("recurring-weekday-picker");
const recurringCategorySelect = document.getElementById("recurring-category-select");

function openRecurringModal() {
  recurringForm.reset();
  recurringWeekdayPicker.querySelectorAll(".weekday-btn").forEach((btn) => btn.classList.remove("active"));
  document.getElementById("recurring-start-date").value = toDatetimeLocalValue(new Date()).slice(0, 10);
  // 이미 렌더링된 카테고리 셀렉트를 그대로 재사용한다 - 다시 fetch할 필요 없음
  recurringCategorySelect.innerHTML = categorySelect.innerHTML;
  const activeCat = categories.find((c) => String(c.id) === String(activeCategoryId));
  if (activeCat) recurringCategorySelect.value = String(activeCat.id);
  else if (categories.length) recurringCategorySelect.value = String(categories[0].id);
  recurringModalOverlay.classList.add("show");
}

function closeRecurringModal() {
  recurringModalOverlay.classList.remove("show");
}

document.getElementById("open-recurring-modal").addEventListener("click", openRecurringModal);
document.getElementById("cancel-recurring-modal-btn").addEventListener("click", closeRecurringModal);
recurringModalOverlay.addEventListener("click", (e) => {
  if (e.target === recurringModalOverlay) closeRecurringModal();
});

recurringWeekdayPicker.querySelectorAll(".weekday-btn").forEach((btn) => {
  btn.addEventListener("click", () => btn.classList.toggle("active"));
});

document.getElementById("recurring-select-daily").addEventListener("click", () => {
  recurringWeekdayPicker.querySelectorAll(".weekday-btn").forEach((btn) => btn.classList.add("active"));
});

recurringForm.addEventListener("submit", async (e) => {
  e.preventDefault();
  const days = Array.from(recurringWeekdayPicker.querySelectorAll(".weekday-btn.active")).map((btn) => btn.dataset.day);
  if (days.length === 0) {
    showToast("반복할 요일을 하나 이상 선택해주세요.");
    return;
  }

  const payload = {
    title: document.getElementById("recurring-title").value.trim(),
    content: document.getElementById("recurring-content").value.trim(),
    startTime: document.getElementById("recurring-start-time").value,
    endTime: document.getElementById("recurring-end-time").value || null,
    daysOfWeek: days,
    startDate: document.getElementById("recurring-start-date").value,
    endDate: document.getElementById("recurring-end-date").value || null,
    categoryId: Number(recurringCategorySelect.value),
  };

  const submitBtn = document.getElementById("recurring-submit-btn");
  submitBtn.disabled = true;
  try {
    await API.post("/api/recurring-schedules", payload);
    closeRecurringModal();
    showToast("반복 일정을 추가했습니다.");
    await refreshAll();
  } catch (err) {
    showToast(`반복 일정 추가에 실패했습니다. ${err.message}`);
  } finally {
    submitBtn.disabled = false;
  }
});

// ---------- AI 일정 추천 챗봇 ----------
// 예전엔 프롬프트 한 번 → 결과 한 번으로 끝나는 단발성 모달이었는데, 이제는 대화가 계속 이어지고(서버가
// AiChatMessage로 기록을 남겨 다음 턴에도 맥락을 기억한다) 각 답변마다 수동/자동 등록을 할 수 있는
// 채팅창 형태로 바뀌었다. 전체 화면을 덮는 모달이 아니라 로봇 아이콘 위에서 펼쳐지는 작은 패널이라,
// 열어둔 채로 보드/사이드바 등 페이지의 다른 부분을 그대로 조작할 수 있다(배경 클릭으로 닫히지 않음)

const aiChatPanel = document.getElementById("ai-chat-panel");
const aiChatbotBubble = document.getElementById("ai-chatbot-bubble");
const aiSuggestForm = document.getElementById("ai-suggest-form");
const aiSuggestPromptInput = document.getElementById("ai-suggest-prompt");
const aiSuggestLoadingField = document.getElementById("ai-suggest-loading");
const aiChatMessagesEl = document.getElementById("ai-chat-messages");
const aiSuggestSubmitBtn = document.getElementById("ai-suggest-submit-btn");
const aiChatClearBtn = document.getElementById("ai-chat-clear-btn");

// 현재 대화창에 표시 중인 메시지 목록(서버 AiChatMessageDto 모양 그대로) - 모달을 열 때마다 서버에서 새로 받아온다
let aiChatMessages = [];

// AI 채팅의 "수동 등록"으로 "일정 추가" 폼을 열었을 때만 값이 채워진다 - 그 폼에서 실제로 저장되면
// (scheduleForm submit 핸들러) 이 값으로 방금 만든 일정과 채팅 메시지를 서버에 연결해 "등록됨" 표시를 남긴다
let pendingAiChatRegisterMessageId = null;

// SCHEDULE_RECOMMENDATION이 여러 항목을 제안했을 때, 그중 어느 항목을 "수동 등록"으로 열었는지 -
// message 단위(하위호환 경로)로 열었으면 null로 남는다
let pendingAiChatRegisterItemId = null;

// 자동 등록이 가능하려면 제목/시작시각/카테고리가 전부 있어야 한다(ScheduleRequestDto의 필수값과 동일) -
// 하나라도 없으면(AI가 형식을 어겼거나 적절한 카테고리를 못 찾은 경우) 자동 등록 버튼을 비활성화하고
// 수동 등록으로 유도한다. canAutoRegisterItem은 suggestedItems 원소 하나에 대한 같은 판단이다
function canAutoRegister(message) {
  return !!(message && message.suggestedTitle && message.suggestedStartAt && message.suggestedCategoryId);
}

function canAutoRegisterItem(item) {
  return !!(item && item.title && item.startAt && item.categoryId);
}

// /settings 페이지의 "AI 추천 일정 자동 등록" 토글 - 꺼져 있으면 "자동 등록" 버튼 자체를 보여주지 않고
// 항상 수동 등록(검토 후 저장)만 쓰게 한다. 데이터가 다 갖춰졌는지(canAutoRegister)와는 별개 조건이다
function isAiAutoRegisterSettingEnabled() {
  const user = API.getCurrentUser();
  return !!(user && user.aiAutoRegisterEnabled);
}

function aiChatUserBubbleHtml(message) {
  return `<div class="ai-chat-bubble ai-chat-bubble-user">${escapeHtml(message.message)}</div>`;
}

// SCHEDULE_RECOMMENDATION 말풍선 안에서 제안 항목 하나(AiChatMessageDto.SuggestedScheduleItemDto)를
// 카드 하나로 그린다 - 여러 개를 한 번에 추천받아도 항목마다 독립적으로 수동/자동 등록할 수 있게 한다
function aiChatSuggestionItemHtml(message, item) {
  const parts = [`<div class="ai-chat-bubble-title">${escapeHtml(item.title)}</div>`];
  if (item.startAt) {
    parts.push(`<div class="ai-chat-bubble-time">${escapeHtml(formatTimeRange(item.startAt, item.endAt))}</div>`);
  }
  if (item.content) {
    parts.push(`<div class="ai-chat-bubble-content">${escapeHtml(item.content)}</div>`);
  }
  if (item.registeredScheduleId) {
    parts.push(`<div class="ai-chat-bubble-actions"><span class="ai-chat-registered-badge">✅ 일정으로 등록됨</span></div>`);
  } else {
    const autoEligible = canAutoRegisterItem(item);
    const autoTitle = autoEligible ? "" : "AI가 시작 시각/카테고리 등 충분한 정보를 주지 않아 자동 등록할 수 없어요.";
    const autoBtnHtml = isAiAutoRegisterSettingEnabled()
      ? `<button type="button" class="btn btn-primary btn-sm" data-action="auto-item" data-message-id="${message.id}" data-item-id="${item.id}" ${autoEligible ? "" : "disabled"} title="${escapeHtml(autoTitle)}">자동 등록</button>`
      : "";
    parts.push(`<div class="ai-chat-bubble-actions">
      <button type="button" class="btn btn-ghost btn-sm" data-action="manual-item" data-message-id="${message.id}" data-item-id="${item.id}">수동 등록</button>
      ${autoBtnHtml}
    </div>`);
  }
  return `<div class="ai-chat-suggestion-item">${parts.join("")}</div>`;
}

// AiChatMessageDto.category가 "SCHEDULE_RECOMMENDATION"(새 일정 추천)이나 "SCHEDULE_UPDATE"(기존 일정
// 수정 제안)인 메시지만 제목/시간/내용 + 등록·수정 UI를 보여주고, "MANDALART_FILL"(만다라트 채우기)은
// 서버가 이미 채우기까지 끝낸 뒤라 결과 안내 + 바로가기만 보여준다. 그 외(category === "GENERAL" - 잡담,
// 일정 조회/설명 등)는 답변 텍스트만 본문 하나로 보여준다. suggestedTitle 유무가 아니라 서버가 명시적으로
// 분류한 category로 판단한다(AiService.SYSTEM_PROMPT 참고).
//
// SCHEDULE_RECOMMENDATION은 한 번에 여러 일정을 제안할 수 있어(message.suggestedItems) 항목마다 카드
// 하나씩 그린다. suggestedItems가 비어 있는데 message.suggestedTitle이 있는 경우는 이 기능 이전에
// 저장된 옛 메시지뿐이라(AiService가 새 메시지는 항상 suggestedItems만 채운다) 그때만 예전 방식(단일
// 필드)으로 그린다
function aiChatAssistantBubbleHtml(message) {
  const isRecommendation = message.category === "SCHEDULE_RECOMMENDATION";
  const isUpdate = message.category === "SCHEDULE_UPDATE";
  const isMandalartFill = message.category === "MANDALART_FILL";
  const items = isRecommendation ? (message.suggestedItems || []) : [];
  const hasItemList = items.length > 0;
  const isLegacyRecommendation = isRecommendation && !hasItemList && !!message.suggestedTitle;
  const showSuggestionFields = isUpdate || isLegacyRecommendation;
  const parts = [];

  if (showSuggestionFields) {
    if (isUpdate) {
      parts.push(`<div class="ai-chat-bubble-tag">✏️ 수정 제안</div>`);
    }
    if (message.suggestedTitle) {
      parts.push(`<div class="ai-chat-bubble-title">${escapeHtml(message.suggestedTitle)}</div>`);
    }
    if (message.suggestedStartAt) {
      parts.push(`<div class="ai-chat-bubble-time">${escapeHtml(formatTimeRange(message.suggestedStartAt, message.suggestedEndAt))}</div>`);
    }
    if (message.suggestedContent) {
      parts.push(`<div class="ai-chat-bubble-content">${escapeHtml(message.suggestedContent)}</div>`);
    }
  }
  if (isMandalartFill) {
    parts.push(`<div class="ai-chat-bubble-tag">🧩 만다라트 채우기</div>`);
  }
  if (message.message) {
    const textClass = (showSuggestionFields || hasItemList) ? "ai-chat-bubble-reason" : "ai-chat-bubble-content";
    parts.push(`<div class="${textClass}">${escapeHtml(message.message)}</div>`);
  }
  if (isMandalartFill && message.targetMandalartBoardId) {
    parts.push(`<div class="ai-chat-bubble-actions">
      <a class="btn btn-ghost btn-sm" href="/mandalart" target="_blank" rel="noopener">만다라트 보기</a>
    </div>`);
  }

  if (hasItemList) {
    parts.push(`<div class="ai-chat-suggestion-items">${items.map((item) => aiChatSuggestionItemHtml(message, item)).join("")}</div>`);
    // 항목이 하나뿐이면 카드 안의 "자동 등록" 버튼과 중복이라, 여러 개일 때만 일괄 버튼을 보여준다
    const unregisteredEligible = items.filter((item) => !item.registeredScheduleId && canAutoRegisterItem(item));
    if (items.length > 1 && isAiAutoRegisterSettingEnabled() && unregisteredEligible.length > 0) {
      parts.push(`<div class="ai-chat-bulk-actions">
        <button type="button" class="btn btn-primary btn-sm" data-action="auto-all-items" data-message-id="${message.id}">전체 자동 등록</button>
      </div>`);
    }
  } else if (isLegacyRecommendation) {
    if (message.registeredScheduleId) {
      parts.push(`<div class="ai-chat-bubble-actions"><span class="ai-chat-registered-badge">✅ 일정으로 등록됨</span></div>`);
    } else {
      // 설정이 켜져 있으면 메시지를 받는 즉시 자동 등록을 시도하므로(maybeAutoRegisterAfterSend 참고)
      // 정상적인 경우엔 이 버튼이 거의 보이지 않는다 - 그 시도가 실패했거나(네트워크 오류 등)
      // 과거 대화 기록을 다시 불러온 경우의 수동 재시도 수단으로 남겨둔다
      const autoEligible = canAutoRegister(message);
      const autoTitle = autoEligible ? "" : "AI가 시작 시각/카테고리 등 충분한 정보를 주지 않아 자동 등록할 수 없어요.";
      const autoBtnHtml = isAiAutoRegisterSettingEnabled()
        ? `<button type="button" class="btn btn-primary btn-sm" data-action="auto" data-message-id="${message.id}" ${autoEligible ? "" : "disabled"} title="${escapeHtml(autoTitle)}">자동 등록</button>`
        : "";
      parts.push(`<div class="ai-chat-bubble-actions">
        <button type="button" class="btn btn-ghost btn-sm" data-action="manual" data-message-id="${message.id}">수동 등록</button>
        ${autoBtnHtml}
      </div>`);
    }
  } else if (isUpdate) {
    if (message.registeredScheduleId) {
      parts.push(`<div class="ai-chat-bubble-actions"><span class="ai-chat-registered-badge">✅ 일정이 수정됨</span></div>`);
    } else if (message.targetScheduleId) {
      // "수정": 대상 일정의 기존 수정 폼을 열어(현재 값으로 미리 채워짐) AI 제안값만 덮어쓴 뒤 검토하고
      // 저장하게 한다. "수정 반영": 추가 창 없이 AI가 제안한 값을 곧바로 PUT으로 반영한다
      parts.push(`<div class="ai-chat-bubble-actions">
        <button type="button" class="btn btn-ghost btn-sm" data-action="edit-update" data-message-id="${message.id}">수정</button>
        <button type="button" class="btn btn-primary btn-sm" data-action="apply-update" data-message-id="${message.id}">수정 반영</button>
      </div>`);
    }
  }

  return `<div class="ai-chat-bubble ai-chat-bubble-assistant${(showSuggestionFields || hasItemList) ? " has-suggestion" : ""}">${parts.join("")}</div>`;
}

function renderAiChatMessages() {
  if (aiChatMessages.length === 0) {
    aiChatMessagesEl.innerHTML = `<div class="ai-chat-empty">안녕하세요! 일정에 대해 무엇이든 물어보세요.<br>예: "이번 주에 운동할 시간 추천해줘"</div>`;
    return;
  }
  aiChatMessagesEl.innerHTML = aiChatMessages
    .map((m) => (m.role === "USER" ? aiChatUserBubbleHtml(m) : aiChatAssistantBubbleHtml(m)))
    .join("");
  aiChatMessagesEl.scrollTop = aiChatMessagesEl.scrollHeight;
}

async function loadAiChatMessages() {
  try {
    aiChatMessages = await API.get("/api/ai/chat/messages");
  } catch (err) {
    aiChatMessages = [];
    showToast(`대화 기록을 불러오지 못했습니다. ${err.message}`);
  }
  renderAiChatMessages();
}

// 인사 말풍선을 X로 닫으면, 새로고침해도 다시 뜨지 않게 이메일별로 기억해둔다 - 서버에는 저장할 만한
// 값이 아니라(activeCategoryStorageKey/radarCategoryFilterStorageKey와 같은 이유) localStorage를 쓴다
function aiChatbotBubbleDismissedKey() {
  const user = API.getCurrentUser();
  const email = (user && user.email) || "anonymous";
  return `ai-chatbot-bubble-dismissed:${email}`;
}

function isAiChatbotBubbleDismissed() {
  return localStorage.getItem(aiChatbotBubbleDismissedKey()) === "true";
}

if (isAiChatbotBubbleDismissed()) {
  aiChatbotBubble.style.display = "none";
}

document.getElementById("ai-chatbot-bubble-close").addEventListener("click", (e) => {
  e.stopPropagation(); // 아바타 클릭(채팅 패널 열기)으로 번지지 않게
  aiChatbotBubble.style.display = "none";
  localStorage.setItem(aiChatbotBubbleDismissedKey(), "true");
});

function openAiSuggestModal() {
  aiSuggestForm.reset();
  aiSuggestLoadingField.style.display = "none";
  aiChatPanel.classList.add("show");
  aiChatbotBubble.style.display = "none"; // 패널이 열려 있는 동안은 인사 말풍선과 자리가 겹치니 숨긴다
  loadAiChatMessages();
  aiSuggestPromptInput.focus();
}

function closeAiSuggestModal() {
  aiChatPanel.classList.remove("show");
  // X로 이미 닫아둔 상태라면 패널을 닫는다고 인사 말풍선을 다시 띄우지 않는다
  if (!isAiChatbotBubbleDismissed()) {
    aiChatbotBubble.style.display = "";
  }
}

function toggleAiSuggestModal() {
  if (aiChatPanel.classList.contains("show")) closeAiSuggestModal();
  else openAiSuggestModal();
}

document.getElementById("open-ai-suggest-modal").addEventListener("click", toggleAiSuggestModal);
document.getElementById("ai-chat-close-btn").addEventListener("click", closeAiSuggestModal);

// 채팅다운 조작감을 위해 Enter로 바로 전송하고, Shift+Enter로만 줄바꿈한다
aiSuggestPromptInput.addEventListener("keydown", (e) => {
  if (e.key === "Enter" && !e.shiftKey) {
    e.preventDefault();
    aiSuggestForm.requestSubmit();
  }
});

aiSuggestForm.addEventListener("submit", async (e) => {
  e.preventDefault();
  const prompt = aiSuggestPromptInput.value.trim();
  if (!prompt) return;

  aiSuggestSubmitBtn.disabled = true;
  aiSuggestPromptInput.disabled = true;
  aiSuggestPromptInput.value = "";
  aiSuggestLoadingField.style.display = "";

  // 서버 응답(Claude 호출 포함이라 몇 초 걸릴 수 있다)을 기다리지 않고 내가 보낸 질문을 즉시 채팅창에
  // 올린다 - 임시 id로 먼저 그렸다가, 서버가 실제로 저장한 메시지 쌍이 오면 그 자리를 통째로 교체한다
  const tempId = `temp-${Date.now()}`;
  aiChatMessages.push({ id: tempId, role: "USER", message: prompt });
  renderAiChatMessages();

  try {
    const exchange = await API.post("/api/ai/chat/messages", { message: prompt });
    const tempIndex = aiChatMessages.findIndex((m) => m.id === tempId);
    if (tempIndex !== -1) {
      aiChatMessages.splice(tempIndex, 1, exchange.userMessage, exchange.assistantMessage);
    } else {
      aiChatMessages.push(exchange.userMessage, exchange.assistantMessage);
    }
    renderAiChatMessages();
    await maybeAutoRegisterAfterSend(exchange.assistantMessage);
  } catch (err) {
    aiChatMessages = aiChatMessages.filter((m) => m.id !== tempId); // 실패했으니 임시로 띄웠던 내 메시지를 지운다
    renderAiChatMessages();
    aiSuggestPromptInput.value = prompt; // 입력했던 내용을 되돌려준다
    showToast(`AI 응답에 실패했습니다. ${err.message}`);
  } finally {
    aiSuggestSubmitBtn.disabled = false;
    aiSuggestPromptInput.disabled = false;
    aiSuggestLoadingField.style.display = "none";
    aiSuggestPromptInput.focus();
  }
});

aiChatClearBtn.addEventListener("click", async () => {
  if (aiChatMessages.length === 0) return;
  if (!confirm("대화 내용을 모두 지울까요? 되돌릴 수 없습니다.")) return;
  try {
    await API.del("/api/ai/chat/messages");
    aiChatMessages = [];
    renderAiChatMessages();
  } catch (err) {
    showToast(`대화 초기화에 실패했습니다. ${err.message}`);
  }
});

// AI 추천 결과로 "일정 추가" 폼을 미리 채우고 그 폼을 연다 - 저장은 여기서 하지 않고, 사용자가
// 값을 확인/수정한 뒤 폼의 "저장" 버튼을 눌러야 실제로 POST /api/schedules가 호출된다.
// AI가 형식이 깨진 시각이나 존재하지 않는 categoryId를 준 경우 AiService가 이미 null로 비워서
// 응답하므로, 여기서는 값이 있는 필드만 채우고 나머지는 openCreateModal()의 기본값을 그대로 둔다
function openCreateModalFromAiSuggestion(message) {
  openCreateModal();
  pendingAiChatRegisterMessageId = message.id;

  if (message.suggestedTitle) document.getElementById("title").value = message.suggestedTitle;
  if (message.suggestedContent) document.getElementById("content").value = message.suggestedContent;

  if (message.suggestedStartAt) {
    startAtInput.value = toDatetimeLocalValue(message.suggestedStartAt);
    startAtSync.syncVisibleFromHidden();
    lastStartValue = startAtInput.value;
  }
  // 종료 시각을 안 줬으면 알림형(시작 시각만) 일정으로 간주한다
  noEndTimeToggle.checked = !!message.suggestedStartAt && !message.suggestedEndAt;
  if (message.suggestedEndAt) {
    endAtInput.value = toDatetimeLocalValue(message.suggestedEndAt);
  }
  applyEndTimeToggleUI();
  if (!noEndTimeToggle.checked) endAtSync.syncVisibleFromHidden();

  if (message.suggestedCategoryId && categories.some((c) => String(c.id) === String(message.suggestedCategoryId))) {
    categorySelect.value = String(message.suggestedCategoryId);
  }
}

// openCreateModalFromAiSuggestion과 같은 목적이지만, SCHEDULE_RECOMMENDATION이 한 번에 여러 일정을
// 제안했을 때 그중 항목 하나(AiChatMessageDto.SuggestedScheduleItemDto)의 값으로 폼을 채운다
function openCreateModalFromAiSuggestionItem(message, item) {
  openCreateModal();
  pendingAiChatRegisterMessageId = message.id;
  pendingAiChatRegisterItemId = item.id;

  if (item.title) document.getElementById("title").value = item.title;
  if (item.content) document.getElementById("content").value = item.content;

  if (item.startAt) {
    startAtInput.value = toDatetimeLocalValue(item.startAt);
    startAtSync.syncVisibleFromHidden();
    lastStartValue = startAtInput.value;
  }
  noEndTimeToggle.checked = !!item.startAt && !item.endAt;
  if (item.endAt) {
    endAtInput.value = toDatetimeLocalValue(item.endAt);
  }
  applyEndTimeToggleUI();
  if (!noEndTimeToggle.checked) endAtSync.syncVisibleFromHidden();

  if (item.categoryId && categories.some((c) => String(c.id) === String(item.categoryId))) {
    categorySelect.value = String(item.categoryId);
  }
}

// AI의 "수정 제안"으로 기존 일정의 수정 폼을 연다 - openEditModal()로 그 일정의 현재 값을 먼저 채운 뒤,
// AI가 제안한 값이 있는 필드만 덮어쓴다(제안에 없는 필드는 기존 값 그대로 유지). 저장은 여기서 하지 않고,
// 사용자가 검토/수정한 뒤 폼의 "저장" 버튼을 눌러야 실제로 PUT /api/schedules/{id}가 호출된다
function openEditModalFromAiSuggestion(message) {
  openEditModal(message.targetScheduleId);
  pendingAiChatRegisterMessageId = message.id;

  if (message.suggestedTitle) document.getElementById("title").value = message.suggestedTitle;
  if (message.suggestedContent) document.getElementById("content").value = message.suggestedContent;

  if (message.suggestedStartAt) {
    startAtInput.value = toDatetimeLocalValue(message.suggestedStartAt);
    startAtSync.syncVisibleFromHidden();
    lastStartValue = startAtInput.value;
  }
  if (message.suggestedEndAt) {
    noEndTimeToggle.checked = false;
    endAtInput.value = toDatetimeLocalValue(message.suggestedEndAt);
    applyEndTimeToggleUI();
    endAtSync.syncVisibleFromHidden();
  }
  if (message.suggestedCategoryId && categories.some((c) => String(c.id) === String(message.suggestedCategoryId))) {
    categorySelect.value = String(message.suggestedCategoryId);
  }
}

// 일정 저장/수정 자체는 이미 끝난 뒤라, 이 연결이 실패해도 조용히 무시한다(채팅창에 "등록됨"/"수정됨" 표시만 안 남을 뿐).
// itemId가 있으면 SCHEDULE_RECOMMENDATION의 특정 항목을, 없으면 메시지 자체(SCHEDULE_UPDATE 또는
// 하위호환 단일 추천)를 연결한다
async function linkAiChatMessageToSchedule(messageId, scheduleId, itemId) {
  try {
    const url = itemId
      ? `/api/ai/chat/messages/${messageId}/items/${itemId}/register`
      : `/api/ai/chat/messages/${messageId}/register`;
    const updated = await API.patch(url, { scheduleId });
    const idx = aiChatMessages.findIndex((m) => m.id === messageId);
    if (idx !== -1) aiChatMessages[idx] = updated;
  } catch (err) {
    // no-op
  }
}

// AI 추천 값을 그대로 새 일정으로 저장하고 채팅 메시지와 연결한다(하위호환: 이 기능 이전 방식의 단일
// suggestedTitle 필드 메시지 전용) - 검토 없이 바로 반영되는 두 경로(버튼으로 누른 "자동 등록", 설정이
// 켜져 있을 때 응답을 받자마자 자동으로 반영하는 경로)가 공유한다
async function saveAiRecommendationAsSchedule(message) {
  const currentUser = API.getCurrentUser();
  const userId = currentUser && currentUser.id;
  const payload = {
    title: message.suggestedTitle,
    content: message.suggestedContent || "",
    startAt: message.suggestedStartAt,
    endAt: message.suggestedEndAt,
    status: "PENDING",
    userId,
    categoryId: message.suggestedCategoryId,
  };

  const saved = await API.post("/api/schedules", payload);
  scheduleMeta.set(String(saved.id), { categoryId: payload.categoryId, userId });
  await linkAiChatMessageToSchedule(message.id, saved.id);
  return saved;
}

// saveAiRecommendationAsSchedule과 같은 목적이지만, message.suggestedItems의 항목 하나를 새 일정으로
// 저장한다 - 한 번에 여러 일정을 제안받았을 때 항목별 자동/일괄 등록이 공유해서 쓴다
async function saveAiRecommendationAsScheduleItem(message, item) {
  const currentUser = API.getCurrentUser();
  const userId = currentUser && currentUser.id;
  const payload = {
    title: item.title,
    content: item.content || "",
    startAt: item.startAt,
    endAt: item.endAt,
    status: "PENDING",
    userId,
    categoryId: item.categoryId,
  };

  const saved = await API.post("/api/schedules", payload);
  scheduleMeta.set(String(saved.id), { categoryId: payload.categoryId, userId });
  await linkAiChatMessageToSchedule(message.id, saved.id, item.id);
  return saved;
}

// "자동 등록" 버튼을 직접 눌렀을 때 - 저장 후 채팅 패널을 닫고 방금 만든 일정의 상세 팝업까지 보여준다
// (사용자가 명시적으로 요청한 동작이니 결과를 바로 확인시켜준다)
async function autoRegisterAiChatMessage(message, buttonEl) {
  buttonEl.disabled = true;
  try {
    const saved = await saveAiRecommendationAsSchedule(message);
    closeAiSuggestModal();
    showToast("AI 추천으로 일정을 자동 등록했습니다.");
    await refreshAll();
    // AiService가 startAt/endAt을 항상 오늘 날짜로 맞춰서 내려주므로(시:분만 추천값 유지), 보드 탭의
    // "오늘 일정만 표시" 필터에도 자동으로 걸린다 - 별도로 뷰를 이동시킬 필요 없이 상세 팝업만 띄운다
    openDetailModal(String(saved.id));
  } catch (err) {
    showToast(`자동 등록에 실패했습니다. ${err.message}`);
    buttonEl.disabled = false;
  }
}

// "자동 등록" 카드 버튼을 항목 하나에 대해 눌렀을 때 - 여러 항목이 있을 수 있는 말풍선이라, 메시지
// 버전(autoRegisterAiChatMessage)과 달리 패널을 닫거나 상세 팝업으로 이동하지 않고 그 자리에서 배지만
// 갱신한다(다른 항목을 마저 등록할 수 있게)
async function autoRegisterAiChatItem(message, item, buttonEl) {
  buttonEl.disabled = true;
  try {
    await saveAiRecommendationAsScheduleItem(message, item);
    renderAiChatMessages();
    showToast(`"${item.title}" 일정을 자동 등록했습니다.`);
    await refreshAll();
  } catch (err) {
    showToast(`자동 등록에 실패했습니다. ${err.message}`);
    buttonEl.disabled = false;
  }
}

// "전체 자동 등록" 버튼 - 아직 등록되지 않았고 자동 등록 조건(제목/시작시각/카테고리)을 만족하는 항목만
// 순서대로 등록한다. 일부가 실패해도 나머지는 계속 진행하고, 실패한 항목만 토스트로 따로 알려준다
async function autoRegisterAllAiChatItems(message, buttonEl) {
  buttonEl.disabled = true;
  const current = aiChatMessages.find((m) => m.id === message.id) || message;
  const eligible = (current.suggestedItems || []).filter((item) => !item.registeredScheduleId && canAutoRegisterItem(item));

  let successCount = 0;
  for (const item of eligible) {
    try {
      await saveAiRecommendationAsScheduleItem(message, item);
      successCount++;
    } catch (err) {
      showToast(`"${item.title}" 등록에 실패했습니다. ${err.message}`);
    }
  }

  renderAiChatMessages();
  if (successCount > 0) {
    showToast(`일정 ${successCount}개를 자동 등록했습니다.`);
    await refreshAll();
  } else {
    buttonEl.disabled = false;
  }
}

// /settings의 "AI 추천 일정 자동 등록"이 켜져 있으면, 방금 받은 답변이 등록 가능한 추천일 때 버튼
// 클릭 없이 바로 등록한다 - 채팅 중이라 채팅 패널은 닫지 않고, 결과는 말풍선의 "✅ 등록됨" 배지와
// 토스트로만 알려준다(자동 등록 버튼을 눌렀을 때처럼 패널을 닫고 상세 팝업으로 이동하진 않는다).
// SCHEDULE_RECOMMENDATION이 여러 항목을 제안했으면 조건을 만족하는 항목을 전부 순서대로 등록하고,
// suggestedItems가 비어 있으면(이 기능 이전 방식의 메시지) 하위호환 경로로 처리한다
async function maybeAutoRegisterAfterSend(message) {
  if (message.category !== "SCHEDULE_RECOMMENDATION") return;
  if (!isAiAutoRegisterSettingEnabled()) return;

  const items = message.suggestedItems || [];
  if (items.length > 0) {
    const eligible = items.filter((item) => !item.registeredScheduleId && canAutoRegisterItem(item));
    if (eligible.length === 0) return;

    let successCount = 0;
    for (const item of eligible) {
      try {
        await saveAiRecommendationAsScheduleItem(message, item);
        successCount++;
      } catch (err) {
        showToast(`"${item.title}" 자동 등록에 실패했습니다. ${err.message}`);
      }
    }
    if (successCount > 0) {
      renderAiChatMessages();
      showToast(`AI 추천 일정 ${successCount}개를 자동으로 등록했습니다.`);
      await refreshAll();
    }
    return;
  }

  if (!canAutoRegister(message)) return;
  try {
    await saveAiRecommendationAsSchedule(message);
    renderAiChatMessages(); // linkAiChatMessageToSchedule이 aiChatMessages를 이미 갱신해뒀으므로 다시 그리기만 하면 된다
    showToast("AI 추천 일정을 자동으로 등록했습니다.");
    await refreshAll();
  } catch (err) {
    showToast(`AI 추천 일정 자동 등록에 실패했습니다. ${err.message}`);
  }
}

// AI의 수정 제안을 곧바로 기존 일정에 반영한다("수정 반영" 버튼) - PUT 대상은 항상 대상 일정의 "현재"
// 값에서 시작해, AiService가 검증에 실패해 null로 비운 필드(예: 잘못된 categoryId)만 원래 값으로
// 되돌리고 나머지는 AI가 제안한 값을 그대로 쓴다. status는 AI가 건드리지 않는 필드라 항상 현재 값을
// 유지한다. endAt만은 예외로 원래 값에 fallback하지 않는다 - null 자체가 "알림형으로 바꾼다"는 유효한
// 최종 상태일 수 있어서, AI가 준 값을 그대로 신뢰해야 한다(SYSTEM_PROMPT가 바뀌지 않는 필드는 현재
// 값을 그대로 반복하라고 지시하므로, 정말 안 바뀌는 경우엔 이미 원래 endAt이 그대로 담겨 온다)
async function saveAiUpdateAsSchedule(message) {
  const current = schedules.find((s) => String(s.id) === String(message.targetScheduleId));
  if (!current) throw new Error("대상 일정을 더 이상 찾을 수 없습니다. 새로고침 후 다시 시도해주세요.");

  const meta = scheduleMeta.get(String(message.targetScheduleId)) || {};
  const currentUser = API.getCurrentUser();
  const userId = meta.userId ?? (currentUser && currentUser.id);
  const currentCategory = categories.find((c) => c.name === current.categoryName);

  const payload = {
    title: message.suggestedTitle || current.title,
    content: message.suggestedContent != null ? message.suggestedContent : current.content || "",
    startAt: message.suggestedStartAt || current.startAt,
    endAt: message.suggestedEndAt,
    status: current.status,
    userId,
    categoryId: message.suggestedCategoryId || meta.categoryId || (currentCategory ? currentCategory.id : undefined),
  };

  const saved = await API.put(`/api/schedules/${message.targetScheduleId}`, payload);
  scheduleMeta.set(String(saved.id), { categoryId: payload.categoryId, userId });
  await linkAiChatMessageToSchedule(message.id, saved.id);
  return saved;
}

async function applyAiUpdateToSchedule(message, buttonEl) {
  buttonEl.disabled = true;
  try {
    await saveAiUpdateAsSchedule(message);
    renderAiChatMessages();
    showToast("AI 제안대로 일정을 수정했습니다.");
    await refreshAll();
  } catch (err) {
    showToast(`일정 수정에 실패했습니다. ${err.message}`);
    buttonEl.disabled = false;
  }
}

// 말풍선마다 등록/수정 버튼이 다시 그려지므로(대화가 늘어날 때마다 renderAiChatMessages가 innerHTML을
// 통째로 갈아끼운다), 버튼 각각에 리스너를 새로 붙이는 대신 컨테이너 하나에 위임해서 처리한다
aiChatMessagesEl.addEventListener("click", (e) => {
  const btn = e.target.closest("button[data-action]");
  if (!btn) return;
  const message = aiChatMessages.find((m) => String(m.id) === btn.dataset.messageId);
  if (!message) return;
  const item = btn.dataset.itemId
    ? (message.suggestedItems || []).find((it) => String(it.id) === btn.dataset.itemId)
    : null;

  if (btn.dataset.action === "manual") {
    closeAiSuggestModal();
    openCreateModalFromAiSuggestion(message);
  } else if (btn.dataset.action === "auto") {
    autoRegisterAiChatMessage(message, btn);
  } else if (btn.dataset.action === "manual-item") {
    if (!item) return;
    closeAiSuggestModal();
    openCreateModalFromAiSuggestionItem(message, item);
  } else if (btn.dataset.action === "auto-item") {
    if (!item) return;
    autoRegisterAiChatItem(message, item, btn);
  } else if (btn.dataset.action === "auto-all-items") {
    autoRegisterAllAiChatItems(message, btn);
  } else if (btn.dataset.action === "edit-update") {
    closeAiSuggestModal();
    openEditModalFromAiSuggestion(message);
  } else if (btn.dataset.action === "apply-update") {
    applyAiUpdateToSchedule(message, btn);
  }
});

// ---------- 모달/팝오버 ESC로 닫기 ----------
// 열려 있는 것만 닫는다 - 보통 한 번에 하나만 열려 있지만, 여러 개가 동시에 열려 있어도 전부 닫히게
// 순서대로 다 확인한다
document.addEventListener("keydown", (e) => {
  if (e.key !== "Escape") return;
  if (modalOverlay.classList.contains("show")) closeModal();
  if (detailModalOverlay.classList.contains("show")) closeDetailModal();
  if (recurringModalOverlay.classList.contains("show")) closeRecurringModal();
  if (aiChatPanel.classList.contains("show")) closeAiSuggestModal();
  if (mandalartPreviewModalOverlay.classList.contains("show")) closeMandalartPreviewModal();
  if (clockFilterPopover.classList.contains("show")) clockFilterPopover.classList.remove("show");
  if (radarFilterPopover.classList.contains("show")) radarFilterPopover.classList.remove("show");
});

// ---------- 카테고리 추가 ----------

document.getElementById("add-category-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const input = document.getElementById("new-category-name");
  const name = input.value.trim();
  if (!name) return;
  try {
    await API.post("/api/categories", { name });
    input.value = "";
    await loadCategories();
    showToast("카테고리를 추가했습니다.");
  } catch (err) {
    showToast(`카테고리를 추가하지 못했습니다. ${err.message}`);
  }
});

// ADMIN이 만드는 카테고리는 CategoryService.createCategory 가 소유자의 userType 만 보고 자동으로
// 전체 공개(기본) 카테고리로 저장하므로, 여기서도 동일한 POST /api/categories 를 그대로 쓴다
document.getElementById("add-default-category-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const input = document.getElementById("new-default-category-name");
  const name = input.value.trim();
  if (!name) return;
  try {
    await API.post("/api/categories", { name });
    input.value = "";
    await loadCategories();
    showToast("기본 카테고리를 생성했습니다.");
  } catch (err) {
    showToast(`기본 카테고리를 생성하지 못했습니다. ${err.message}`);
  }
});

// ---------- 로그아웃 ----------

document.getElementById("logout-btn").addEventListener("click", async () => {
  if (scheduleEventSource) {
    scheduleEventSource.close();
    scheduleEventSource = null;
  }
  try {
    await API.post("/api/auth/logout", {});
  } catch (err) {
    // 로그아웃 실패해도 세션은 정리하고 로그인 화면으로 보낸다
  }
  API.clearSession();
  window.location.href = "/login";
});

// ---------- 만다라트 위젯(미리보기) ----------
// 만다라트를 가장 잘 요약하는 중앙 3x3 블록(핵심 목표(4,4) + 세부목표 8개)을 실제 텍스트로 그린다 -
// 81칸 전부(특히 실행항목 64개)를 넣기엔 위젯도 모달도 공간이 부족해 이 9칸만 보여준다. 대시보드
// 홈 위젯과 미리보기 모달 둘 다 같은 모양(.mandalart-goal-preview-grid)을 그리므로 공용 함수로 뺐고,
// 위젯 쪽은 카드에 맞게 .compact 클래스로 칸 크기만 CSS에서 줄인다. cellsByCoord가 없으면(적용된
// 보드가 아직 없음) 전부 빈 칸으로 그린다
function buildMandalartGoalPreview(gridEl, cellsByCoord) {
  gridEl.innerHTML = "";
  for (let row = 3; row <= 5; row++) {
    for (let col = 3; col <= 5; col++) {
      const cell = document.createElement("div");
      cell.className = "mandalart-goal-preview-cell";
      if (row === 4 && col === 4) cell.classList.add("main-goal");

      const content = cellsByCoord ? cellsByCoord.get(`${row}-${col}`) : null;
      if (content && content.trim()) {
        // 칸 자체를 flex로 두어 가운데 정렬하고, 줄임(line-clamp)은 안쪽 span에만 적용한다 -
        // display:-webkit-box(line-clamp에 필요)와 display:flex(가운데 정렬에 필요)를 같은
        // 요소에 동시에 줄 수 없어서 텍스트를 감싸는 요소를 하나 더 둔다
        const textEl = document.createElement("span");
        textEl.className = "mandalart-goal-preview-text";
        textEl.textContent = content;
        cell.appendChild(textEl);
      } else {
        cell.classList.add("empty");
      }

      gridEl.appendChild(cell);
    }
  }
}

// "적용 중"인 만다라트(mandalart.js의 적용/해제 토글, AiService가 추천 시 참고하는 그 보드)가 있으면
// 그 실제 데이터로 위젯/미리보기 모달 둘 다에 그린다. 제목은 위젯 부제로 보여준다. 적용된 게 없거나
// (하나도 안 만들었거나 전부 해제) 조회에 실패하면 조용히 빈 상태로 대체한다(위젯은 어차피 보조
// 정보라 에러를 따로 알릴 필요는 없다)
async function loadMandalartWidgetPreview() {
  const gridEl = document.getElementById("mandalart-widget-grid");
  const modalGridEl = document.getElementById("mandalart-preview-modal-grid");
  const subtitleEl = document.getElementById("mandalart-widget-subtitle");

  let cellsByCoord = null;
  let title = "";
  try {
    const boards = await API.get("/api/mandalart");
    const activeBoard = boards.find((b) => b.active);
    if (activeBoard) {
      title = activeBoard.title;
      const board = await API.get(`/api/mandalart/${activeBoard.id}`);
      cellsByCoord = new Map(board.cells.map((c) => [`${c.row}-${c.col}`, c.content]));
    }
  } catch (err) {
    cellsByCoord = null;
    title = "";
  }

  if (subtitleEl) subtitleEl.textContent = title;
  if (gridEl) buildMandalartGoalPreview(gridEl, cellsByCoord);
  if (modalGridEl) {
    buildMandalartGoalPreview(modalGridEl, cellsByCoord);
    attachMandalartSubBlockHover(modalGridEl, cellsByCoord);
  }
}

// 미리보기 모달의 9칸(핵심 목표 + 세부목표 8개) 중 세부목표 칸에 마우스를 올리면, 그 세부목표에 대응하는
// 바깥 3x3 블록(자기 블록 중심 칸 = 세부목표 사본 + 실행항목 8개, MandalartAiService의 OUTER_BLOCKS와
// 같은 정의)을 작은 팝오버로 한 번 더 보여준다. 핵심 목표 칸(4,4)은 대응하는 바깥 블록이 없으므로(정석
// 만다라트 구조상 중앙 블록 자체는 "바깥 블록"이 아님) 호버 대상에서 제외한다.
//
// buildMandalartGoalPreview가 매번 gridEl.innerHTML을 비우고 다시 그리므로, 이전 렌더에서 붙인
// 리스너는 옛 칸 요소와 함께 자연히 사라진다 - 매 호출마다 새로 붙이기만 하면 되고 별도 해제 코드가
// 필요 없다. cellsByCoord가 없으면(적용된 보드 없음) 보여줄 실제 내용이 없으므로 아무 것도 하지 않는다
function attachMandalartSubBlockHover(gridEl, cellsByCoord) {
  const popoverEl = document.getElementById("mandalart-subblock-popover");
  const popoverGridEl = document.getElementById("mandalart-subblock-popover-grid");
  const popoverTitleEl = document.getElementById("mandalart-subblock-popover-title");
  if (!popoverEl || !popoverGridEl || !cellsByCoord) return;

  const cells = gridEl.querySelectorAll(".mandalart-goal-preview-cell");
  cells.forEach((cellEl, index) => {
    const row = 3 + Math.floor(index / 3);
    const col = 3 + (index % 3);
    if (row === 4 && col === 4) return; // 핵심 목표 - 대응하는 바깥 블록 없음

    cellEl.addEventListener("mouseenter", () => {
      const baseRow = (row - 3) * 3;
      const baseCol = (col - 3) * 3;

      popoverGridEl.innerHTML = "";
      for (let r = baseRow; r <= baseRow + 2; r++) {
        for (let c = baseCol; c <= baseCol + 2; c++) {
          const cell = document.createElement("div");
          cell.className = "mandalart-goal-preview-cell";
          if (r === baseRow + 1 && c === baseCol + 1) cell.classList.add("main-goal"); // 이 블록의 세부목표 사본

          const content = cellsByCoord.get(`${r}-${c}`);
          if (content && content.trim()) {
            const textEl = document.createElement("span");
            textEl.className = "mandalart-goal-preview-text";
            textEl.textContent = content;
            cell.appendChild(textEl);
          } else {
            cell.classList.add("empty");
          }
          popoverGridEl.appendChild(cell);
        }
      }

      const subGoalContent = cellsByCoord.get(`${row}-${col}`);
      popoverTitleEl.textContent = subGoalContent && subGoalContent.trim() ? subGoalContent : "실행항목";

      // 9칸 중 어느 줄(라인)에 있는 칸이냐에 따라 팝오버가 튀어나오는 방향을 고정한다 - 왼쪽 줄(col 3)은
      // 왼쪽으로, 오른쪽 줄(col 5)은 오른쪽으로, 상단 중앙(3,4)은 위로, 하단 중앙(5,4)은 아래로. 그래야
      // 팝오버가 항상 그 세부목표 칸에서 모달 바깥쪽으로 뻗어나가는 방향에 뜬다(핵심 목표(4,4)는 호출부에서
      // 이미 걸러져 여기 들어오지 않는다)
      const direction = col === 3 ? "left" : col === 5 ? "right" : row === 3 ? "top" : "bottom";

      // 팝오버 크기를 하드코딩하지 않고 실제로 한번 보여서(show) 잰다 - 내용(제목 줄바꿈 등)에 따라
      // 실제 렌더 크기가 달라질 수 있어서
      popoverEl.classList.add("show");
      const cellRect = cellEl.getBoundingClientRect();
      const popRect = popoverEl.getBoundingClientRect();
      const gap = 10;

      let left;
      let top;
      if (direction === "left") {
        left = cellRect.left - popRect.width - gap;
        top = cellRect.top + cellRect.height / 2 - popRect.height / 2;
      } else if (direction === "right") {
        left = cellRect.right + gap;
        top = cellRect.top + cellRect.height / 2 - popRect.height / 2;
      } else if (direction === "top") {
        left = cellRect.left + cellRect.width / 2 - popRect.width / 2;
        top = cellRect.top - popRect.height - gap;
      } else {
        left = cellRect.left + cellRect.width / 2 - popRect.width / 2;
        top = cellRect.bottom + gap;
      }

      left = Math.min(Math.max(left, 10), window.innerWidth - popRect.width - 10);
      top = Math.min(Math.max(top, 10), window.innerHeight - popRect.height - 10);
      popoverEl.style.left = `${left}px`;
      popoverEl.style.top = `${top}px`;
    });

    cellEl.addEventListener("mouseleave", () => {
      popoverEl.classList.remove("show");
    });
  });
}

// 만다라트 위젯을 누르면 바로 /mandalart 로 이동하지 않고, 가이드처럼 미리보기 모달부터 보여준다.
// 배경(오버레이 자체)을 누르면 닫히고, 미리보기 링크(mandalart-preview-link)를 누르면 그 실제
// href("/mandalart")를 따라 이동한다 - 위젯의 현재 부제(있으면 최근 만다라트 제목)를 그대로 복사해
// 모달에도 보여주므로 별도로 다시 조회하지 않는다
const mandalartWidgetEl = document.getElementById("mandalart-widget");
const mandalartPreviewModalOverlay = document.getElementById("mandalart-preview-modal-overlay");

function openMandalartPreviewModal() {
  const widgetSubtitle = document.getElementById("mandalart-widget-subtitle").textContent;
  document.getElementById("mandalart-preview-modal-subtitle").textContent = widgetSubtitle;
  mandalartPreviewModalOverlay.classList.add("show");
}

function closeMandalartPreviewModal() {
  mandalartPreviewModalOverlay.classList.remove("show");
  // 칸에 마우스를 올린 채로 모달을 닫으면(배경 클릭 등) mouseleave가 안 나갈 수 있어 팝오버도 같이 닫는다
  document.getElementById("mandalart-subblock-popover")?.classList.remove("show");
}

if (mandalartWidgetEl) {
  mandalartWidgetEl.addEventListener("click", (e) => {
    e.preventDefault();
    openMandalartPreviewModal();
  });
}

if (mandalartPreviewModalOverlay) {
  mandalartPreviewModalOverlay.addEventListener("click", (e) => {
    if (e.target === mandalartPreviewModalOverlay) closeMandalartPreviewModal();
  });
}

// 일정 생성 폼에서 "작성자 User ID" 수동 입력칸을 없앴기 때문에, 본인 id 를 항상 신뢰성 있게
// 알고 있어야 한다. 기존엔 회원가입 시점에만 브라우저 localStorage 에 email→id 를 캐싱해뒀는데
// (다른 브라우저·캐시 삭제 시 비어있을 수 있음), /api/users/me 로 매번 확실하게 받아온다
async function syncCurrentUserId() {
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
  } catch (err) {
    // 실패해도 기존 캐시된 id 로 폴백 - 그마저 없으면 일정 생성 시 저장이 실패하고 토스트로 안내된다
  }
}

// ADMIN 전용 UI(기본 카테고리 생성 버튼)를 보여줄지 결정한다. 기본 카테고리는 CategoryService 가
// "생성자가 ADMIN이면 자동으로 전체 공개 카테고리로 취급"하는 규칙에 기대는 것이라 별도 API는 없고,
// 여기서는 USER 에게 이 버튼 자체가 보이지 않게만 막는다(서버는 어차피 ADMIN 요청만 그렇게 취급하므로
// 이중 방어이긴 하지만, "USER 는 볼 수 없어야 한다"는 요건은 화면 노출 여부의 문제다)
function applyAdminVisibility() {
  const user = API.getCurrentUser();
  const isAdmin = !!user && user.userType === "ADMIN";
  document.querySelectorAll(".admin-only").forEach((el) => {
    el.style.display = isAdmin ? "" : "none";
  });
}

// ---------- 일정 시작 알림 ----------

// 수동 모드: 알림이 떠도 상태는 그대로 두고 사용자가 직접 바꾼다(기본값).
// 자동 모드: 시작 시각이 되면 대기→진행중, 종료 시각이 되면 진행중→완료로 자동 전환한다.
// 종료 시각이 없는 알림형 일정은 "진행중"으로 머물 종료 시점이 없으므로, 시작 시각에 바로 완료 처리한다.
//
// 이 토글 자체는 /settings 페이지(js/settings.js)로 옮겨갔다 - 여기서는 syncCurrentUserId()로 최신
// 값을 캐시에 반영해둘 뿐, 실제 상태 전환은 ScheduleService.autoTransitionScheduleStatuses()
// (@Scheduled)가 서버에서 직접 하므로 탭이 닫혀 있어도(로그아웃 상태여도) 계속 동작한다.
// 이 프론트 코드는 "지금 시작하는 일정"을 알려주는 팝업(상태를 바꾸진 않는다)만 담당한다

const scheduleReminderContainerEl = document.getElementById("schedule-reminder-container");
const REMINDER_AUTO_DISMISS_MS = 12000;
// 일정 id -> 마지막으로 확인한 상태. 알림은 "시작 시각이 지났는지"가 아니라 "방금 진행중으로
// 바뀌었는지"를 기준으로 띄운다 - 그래야 서버 자동 전환과 알림이 항상 같은 폴링 시점에 함께 반영된다
const knownStatusById = new Map();

// 페이지를 처음 열었을 때 이미 진행중인 일정에는 알림이 뜨지 않도록, 초기 로드 직후 현재 상태를 먼저 기록해둔다
function seedKnownScheduleStatuses() {
  schedules.forEach((s) => knownStatusById.set(s.id, s.status));
}

// type: "start"(기본, 지금 시작하는 일정) | "completed"(방금 완료된 일정) - 배지 문구와 강조색만 다르고
// 카드 구조·자동 닫힘·클릭 시 상세보기 동작은 동일하다
function showScheduleReminder(schedule, type = "start") {
  const card = document.createElement("div");
  card.className = type === "completed" ? "schedule-reminder completed" : "schedule-reminder";
  card.setAttribute("role", "alert");

  const badge = document.createElement("div");
  badge.className = "schedule-reminder-badge";
  badge.textContent = type === "completed" ? "완료된 일정" : "지금 시작하는 일정";

  const titleEl = document.createElement("div");
  titleEl.className = "schedule-reminder-title";
  titleEl.textContent = schedule.title;

  const metaEl = document.createElement("div");
  metaEl.className = "schedule-reminder-meta";
  const metaParts = [formatTimeOnly(schedule.startAt)];
  if (schedule.categoryName) metaParts.push(schedule.categoryName);
  metaEl.textContent = metaParts.join(" · ");

  const closeBtn = document.createElement("button");
  closeBtn.type = "button";
  closeBtn.className = "schedule-reminder-close";
  closeBtn.setAttribute("aria-label", "닫기");
  closeBtn.textContent = "×";

  card.appendChild(badge);
  card.appendChild(titleEl);
  card.appendChild(metaEl);
  card.appendChild(closeBtn);

  // 나타날 때(schedule-reminder-in, CSS) 위에서 당겨지듯 내려오는 것과 대칭으로, 사라질 때도 바로
  // DOM에서 지우지 않고 위로 당겨지듯 올라가며 사라지는 애니메이션이 끝난 뒤에 지운다
  const dismiss = () => {
    card.classList.add("dismissing");
    card.addEventListener("animationend", () => card.remove(), { once: true });
  };
  closeBtn.addEventListener("click", (e) => {
    e.stopPropagation();
    dismiss();
  });
  card.addEventListener("click", () => {
    openDetailModal(schedule.id);
    dismiss();
  });

  scheduleReminderContainerEl.appendChild(card);
  setTimeout(dismiss, REMINDER_AUTO_DISMISS_MS);
}

// 서버가 이 유저의 일정에 변화가 생길 때(생성/수정/삭제/자동 상태 전환) 보내는 이벤트를 받으면
// refreshAll()로 최신 상태를 받아온 뒤, 방금 진행중으로 바뀐 일정과 방금 완료된 일정에만 알림을 띄운다
async function handleScheduleChangeEvent() {
  await refreshAll();

  schedules.forEach((s) => {
    const prevStatus = knownStatusById.get(s.id);
    knownStatusById.set(s.id, s.status);
    if (prevStatus === undefined || prevStatus === s.status) return;
    if (s.status === "IN_PROGRESS") {
      showScheduleReminder(s, "start");
    } else if (s.status === "COMPLETED") {
      showScheduleReminder(s, "completed");
    }
  });
}

// ---------- 일정 변경 실시간 반영 (SSE) ----------
// 예전엔 15초마다 폴링(refreshAll)하며 서버에 매번 물어봤는데, 사용자가 늘면 그만큼 불필요한
// 요청이 계속 쌓인다. 이제는 서버가 이 유저의 일정이 바뀔 때만(ScheduleService.
// evictScheduleCacheForUser) SSE로 직접 이벤트를 밀어주고, 프론트는 그 이벤트를 받을 때만
// 최신 상태를 받아온다.
//
// 브라우저 EventSource는 커스텀 헤더를 못 보내 토큰을 쿼리스트링으로 실어 보내는데, 로컬 개발
// 환경은 access token 수명이 8초로 짧아(application-local.yml) 연결이 끊겨 재연결할 시점엔
// URL에 실어둔 토큰이 이미 만료돼 있기 쉽다. 그래서 네이티브 EventSource의 자동 재연결(고정
// URL)에 맡기지 않고, onerror가 뜨면 직접 닫고 토큰을 새로 발급받아 재연결한다
let scheduleEventSource = null;
let scheduleStreamReconnectTimer = null;
const SCHEDULE_STREAM_RECONNECT_DELAY_MS = 3000;

function connectScheduleStream() {
  if (scheduleEventSource) {
    scheduleEventSource.close();
    scheduleEventSource = null;
  }

  const token = API.getToken();
  if (!token) return;

  const es = new EventSource(`/api/schedules/stream?token=${encodeURIComponent(token)}`);
  scheduleEventSource = es;

  es.addEventListener("schedules-changed", () => {
    handleScheduleChangeEvent();
  });

  es.onerror = () => {
    es.close();
    if (scheduleEventSource === es) scheduleEventSource = null;
    scheduleStreamReconnect();
  };
}

function scheduleStreamReconnect() {
  if (scheduleStreamReconnectTimer) return;
  scheduleStreamReconnectTimer = setTimeout(async () => {
    scheduleStreamReconnectTimer = null;
    if (!API.getToken()) return; // 로그아웃된 상태면 재연결하지 않는다
    try {
      await API.refreshAccessToken();
    } catch (e) {
      // 리프레시도 실패 - 다음 일반 API 호출이 세션을 정리하고 로그인 화면으로 보낼 것이다
    }
    connectScheduleStream();
  }, SCHEDULE_STREAM_RECONNECT_DELAY_MS);
}

// ---------- 초기화 ----------

(async function init() {
  if (!requireAuth()) return;
  renderUserChip();
  renderToday();
  loadMandalartWidgetPreview();
  await syncCurrentUserId();
  applyAdminVisibility();
  activeCategoryId = loadStoredActiveCategoryId();
  await loadCategories();
  // 새로고침 사이에 그 카테고리가 삭제됐거나 더 이상 안 보이면(다른 유저 소유 등) 전체 일정으로 되돌린다
  if (activeCategoryId !== "" && !categories.some((c) => String(c.id) === String(activeCategoryId))) {
    activeCategoryId = "";
    saveActiveCategoryId("");
    renderCategorySidebar();
  }
  renderBoardTitle();
  await loadSchedules();
  seedKnownScheduleStatuses();
  if (activeCategoryId !== "") {
    await loadBoardForActiveCategory();
  }
  // "지금" 표시선이 실제 흐르는 시간을 따라가도록 주기적으로 다시 그린다 (데이터 재조회는 없음)
  setInterval(renderTodayClock, 60000);
  connectScheduleStream();
})();
