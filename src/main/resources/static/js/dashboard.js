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
const boardColumnVisibleCount = new Map(); // status key -> 현재까지 펼쳐서 보여줄 개수. "더보기"를 누를 때마다 5씩 늘어나고, 재렌더링(상태 변경 등) 후에도 유지된다
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

function toDatetimeLocalValue(value) {
  if (!value) return "";
  const d = new Date(value);
  const pad = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
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

function renderToday() {
  const now = new Date();
  document.getElementById("today-label").textContent = now.toLocaleDateString("ko-KR", {
    year: "numeric",
    month: "long",
    day: "numeric",
    weekday: "long",
  });
}

async function loadCategories() {
  try {
    categories = await API.get("/api/categories");
  } catch (err) {
    categories = [];
    showToast("카테고리를 불러오지 못했습니다: " + err.message);
  }
  renderCategorySidebar();
  renderCategorySelectOptions();
}

function renderCategorySidebar() {
  categoryCountEl.textContent = categories.length ? `(${categories.length})` : "";

  const allItem = `
    <li data-category-id="" class="${activeCategoryId === "" ? "active" : ""}">
      <span><span class="dot"></span>전체 일정</span>
    </li>`;

  const items = categories
    .map(
      (c) => `
      <li data-category-id="${c.id}" class="${String(activeCategoryId) === String(c.id) ? "active" : ""}">
        <span><span class="dot"></span>${escapeHtml(c.name)}</span>
        <span class="remove-cat" data-remove-category="${c.id}">&times;</span>
      </li>`
    )
    .join("");

  categoryListEl.innerHTML = allItem + items;

  categoryListEl.querySelectorAll("li").forEach((li) => {
    li.addEventListener("click", async (e) => {
      if (e.target.dataset.removeCategory) return;
      activeCategoryId = li.dataset.categoryId;
      renderCategorySidebar();
      renderBoardTitle();
      await loadBoardForActiveCategory();
    });
  });

  categoryListEl.querySelectorAll("[data-remove-category]").forEach((btn) => {
    btn.addEventListener("click", async (e) => {
      e.stopPropagation();
      const id = btn.dataset.removeCategory;
      if (!confirm("이 카테고리를 삭제할까요?")) return;
      try {
        await API.del(`/api/categories/${id}`);
        if (String(activeCategoryId) === String(id)) activeCategoryId = "";
        await loadCategories();
        renderBoardTitle();
        await loadBoardForActiveCategory();
        showToast("카테고리를 삭제했습니다.");
      } catch (err) {
        showToast("삭제 실패: " + err.message);
      }
    });
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
    showToast("일정을 불러오지 못했습니다: " + err.message);
  }
  renderStats();
  refreshVisibleView();
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
    showToast("카테고리별 일정을 불러오지 못했습니다: " + err.message);
  }
  refreshVisibleView();
}

// 목록을 변경(생성/수정/삭제/상태 변경)한 뒤 통계 카드와 현재 보드(전체 or 선택된 카테고리)를 함께 새로고침한다
async function refreshAll() {
  await loadSchedules();
  await loadBoardForActiveCategory();
}

function visibleSchedules() {
  if (activeCategoryId === "") return schedules;
  return categorySchedules ?? [];
}

function renderStats() {
  const list = schedules;
  document.getElementById("stat-total").textContent = list.length;
  document.getElementById("stat-pending").textContent = list.filter((s) => s.status === "PENDING").length;
  document.getElementById("stat-progress").textContent = list.filter((s) => s.status === "IN_PROGRESS").length;
  document.getElementById("stat-completed").textContent = list.filter((s) => s.status === "COMPLETED").length;
  renderTodayClock();
}

// ---------- 일정 통계 레이더(오각형) - 보드/일/주/월/년 뷰에 맞춰 집계 범위가 바뀐다 ----------

const RADAR_CENTER = 124;
const RADAR_MAX_R = 69;
// labelDy: 글자가 baseline 기준으로 그려지다 보니, 축이 위/아래/옆 중 어디를 향하느냐에 따라
// 같은 +y 값이어도 꼭짓점과의 시각적 간격이 달라진다. 다섯 축의 실제 위치(위/옆/아래)에 맞춰
// 각각 다르게 보정해야 "전체"(맨 위)만 유독 위로 붙어 보이는 문제 없이 간격이 고르게 맞는다
const RADAR_AXES = [
  { key: "total", label: "전체", labelDy: 9 },
  { key: "PENDING", label: "대기", labelDy: 4 },
  { key: "IN_PROGRESS", label: "진행중", labelDy: 10 },
  { key: "COMPLETED", label: "완료", labelDy: 10 },
  { key: "CANCELLED", label: "취소", labelDy: 4 },
];

const radarSvgEl = document.getElementById("month-radar-svg");

// angle 0 = 12시 방향, 시계 방향으로 증가 (today-clock 의 polarPoint 와 동일한 규칙)
function radarPolarPoint(r, angleDeg) {
  const rad = ((angleDeg - 90) * Math.PI) / 180;
  return { x: RADAR_CENTER + r * Math.cos(rad), y: RADAR_CENTER + r * Math.sin(rad) };
}

// 현재 뷰(보드/일/주/월/년)에 맞는 집계 범위를 돌려준다. 보드는 날짜 개념이 없는 전체 목록이라 null.
function getRadarScheduleWindow() {
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
  return null; // board
}

function renderScheduleRadar() {
  const window = getRadarScheduleWindow();
  // 카테고리 필터와 무관하게 오늘 시계와 같은 원칙으로 항상 전체 일정 기준으로 집계한다
  const rangeSchedules = window ? schedulesOverlappingRange(schedules, window.start, window.end) : schedules;

  const counts = {
    total: rangeSchedules.length,
    PENDING: rangeSchedules.filter((s) => s.status === "PENDING").length,
    IN_PROGRESS: rangeSchedules.filter((s) => s.status === "IN_PROGRESS").length,
    COMPLETED: rangeSchedules.filter((s) => s.status === "COMPLETED").length,
    CANCELLED: rangeSchedules.filter((s) => s.status === "CANCELLED").length,
  };
  // 축마다 다른 스케일을 쓰면 모양 비교가 무의미해지므로, 가장 큰 값을 기준으로 5축 전부 같은 스케일을 쓴다
  const maxValue = Math.max(1, ...RADAR_AXES.map((a) => counts[a.key]));

  radarSvgEl.textContent = "";

  // 그리드: 25/50/75/100% 위치에 오각형 링을 그린다
  [0.25, 0.5, 0.75, 1].forEach((ratio) => {
    const pts = RADAR_AXES.map((_, i) => {
      const p = radarPolarPoint(RADAR_MAX_R * ratio, (i / RADAR_AXES.length) * 360);
      return `${p.x},${p.y}`;
    }).join(" ");
    radarSvgEl.appendChild(svgEl("polygon", { class: "radar-grid-ring", points: pts }));
  });

  // 축 선 + 라벨
  RADAR_AXES.forEach((axis, i) => {
    const angle = (i / RADAR_AXES.length) * 360;
    const outer = radarPolarPoint(RADAR_MAX_R, angle);
    radarSvgEl.appendChild(
      svgEl("line", { class: "radar-axis-line", x1: RADAR_CENTER, y1: RADAR_CENTER, x2: outer.x, y2: outer.y })
    );
    const labelPt = radarPolarPoint(RADAR_MAX_R + 22, angle);
    const label = svgEl("text", {
      class: "radar-axis-label",
      x: labelPt.x,
      y: labelPt.y + axis.labelDy,
      "text-anchor": "middle",
    });
    label.textContent = axis.label;
    radarSvgEl.appendChild(label);
  });

  // 데이터 오각형
  const dataPoints = RADAR_AXES.map((axis, i) => {
    const value = counts[axis.key];
    const r = (value / maxValue) * RADAR_MAX_R;
    return radarPolarPoint(r, (i / RADAR_AXES.length) * 360);
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
// 겹치는 일정은 최대 3개 레인까지 동심원으로 분리하고, 그 이상 겹치면 마지막 레인에 함께 그린다
const CLOCK_LANE_R = [62, 51, 40];
const CLOCK_ARC_WIDTH = 8;
const CLOCK_MIN_ARC_MINUTES = 8; // 아주 짧은 일정도 호가 보이도록 최소 폭을 보장한다

const clockSvgEl = document.getElementById("today-clock-svg");
const clockTooltipEl = document.getElementById("clock-tooltip");
const clockLegendEl = document.getElementById("today-clock-legend");

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
    .map((s) => ({ ...s, start: new Date(s.startAt), end: new Date(s.endAt) }))
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

// 겹치는 일정을 동심원 레인으로 분리한다 (구간 그래프 그리디 채색)
function assignClockLanes(events) {
  const laneEndMin = [];
  return events.map((e) => {
    let lane = laneEndMin.findIndex((end) => e.startMin >= end);
    if (lane === -1) lane = laneEndMin.length;
    lane = Math.min(lane, CLOCK_LANE_R.length - 1);
    laneEndMin[lane] = e.endMin;
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
  timeEl.textContent = `${formatDateTime(schedule.startAt)} → ${formatDateTime(schedule.endAt)}`;

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

function renderTodayClock() {
  const todays = assignClockLanes(getTodaysScheduleWindows(schedules));

  clockSvgEl.textContent = "";
  hideClockTooltip();

  clockSvgEl.appendChild(
    svgEl("circle", { class: "clock-face-ring", cx: CLOCK_CENTER, cy: CLOCK_CENTER, r: CLOCK_FACE_R })
  );

  // 24시간 눈금: 매시 hairline, 0/6/12/18 시에만 라벨 (직접 라벨은 아껴서)
  for (let h = 0; h < 24; h++) {
    const angle = (h / 24) * 360;
    const isMajor = h % 6 === 0;
    const inner = polarPoint(isMajor ? 66 : 70, angle);
    const outer = polarPoint(CLOCK_FACE_R, angle);
    clockSvgEl.appendChild(
      svgEl("line", { class: "clock-tick", x1: inner.x, y1: inner.y, x2: outer.x, y2: outer.y })
    );
    if (isMajor) {
      const labelPt = polarPoint(58, angle);
      const label = svgEl("text", {
        class: "clock-tick-label",
        x: labelPt.x,
        y: labelPt.y + 3,
        "text-anchor": "middle",
      });
      label.textContent = String(h).padStart(2, "0");
      clockSvgEl.appendChild(label);
    }
  }

  const usedStatuses = new Set();

  todays.forEach((s) => {
    const r = CLOCK_LANE_R[s.lane];
    const startAngle = (s.startMin / 1440) * 360;
    const endAngle = (s.endMin / 1440) * 360;
    const d = describeClockArc(r, startAngle, endAngle);
    usedStatuses.add(s.status);

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
      stroke: STATUS_COLOR_VAR[s.status] || "var(--color-text-muted)",
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

  renderTodayClockLegend(usedStatuses);

  // 중앙: 오늘 일정 개수 (데이터가 없어도 "0"으로 자연스럽게 빈 상태를 표현)
  const centerValue = svgEl("text", {
    class: "clock-center-value",
    x: CLOCK_CENTER,
    y: CLOCK_CENTER - 2,
    "text-anchor": "middle",
  });
  centerValue.textContent = String(todays.length);
  const centerLabel = svgEl("text", {
    class: "clock-center-label",
    x: CLOCK_CENTER,
    y: CLOCK_CENTER + 14,
    "text-anchor": "middle",
  });
  centerLabel.textContent = "오늘 일정";
  clockSvgEl.appendChild(centerValue);
  clockSvgEl.appendChild(centerLabel);

  // 현재 시각 표시선
  const now = new Date();
  const nowAngle = ((now.getHours() * 60 + now.getMinutes()) / 1440) * 360;
  const nowInner = polarPoint(30, nowAngle);
  const nowOuter = polarPoint(CLOCK_FACE_R, nowAngle);
  clockSvgEl.appendChild(
    svgEl("line", { class: "clock-now-line", x1: nowInner.x, y1: nowInner.y, x2: nowOuter.x, y2: nowOuter.y })
  );
  clockSvgEl.appendChild(svgEl("circle", { class: "clock-now-dot", cx: nowOuter.x, cy: nowOuter.y, r: 3 }));
}

function scheduleAriaLabel(s) {
  return `${s.title}, ${formatDateTime(s.startAt)} - ${formatDateTime(s.endAt)}, ${STATUS_LABELS[s.status] || s.status}`;
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

function scheduleCardHtml(s) {
  return `
    <div class="schedule-card" data-id="${s.id}">
      <div class="card-top">
        <div class="card-title">${escapeHtml(s.title)}</div>
      </div>
      ${s.content ? `<p class="card-content">${escapeHtml(s.content)}</p>` : ""}
      <div class="card-meta">
        ${s.categoryName ? `<span class="tag category-tag">${escapeHtml(s.categoryName)}</span>` : ""}
      </div>
      <div class="card-time">${formatDateTime(s.startAt)} → ${formatDateTime(s.endAt)}${s.username ? ` · ${escapeHtml(s.username)}` : ""}</div>
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

function renderBoard() {
  const list = visibleSchedules();

  board.innerHTML = STATUS_COLUMNS.map((col) => {
    const items = list.filter((s) => s.status === col.key);
    const visibleCount = boardColumnVisibleCount.get(col.key) ?? BOARD_COLUMN_VISIBLE_LIMIT;
    const visibleItems = items.slice(0, visibleCount);
    const hiddenCount = items.length - visibleItems.length;

    return `
      <div class="board-column">
        <div class="board-column-header">
          <div class="title"><span class="status-dot ${col.key}"></span>${col.label}</div>
          <span class="count-badge">${items.length}</span>
        </div>
        <div class="board-column-body">
          ${items.length ? visibleItems.map(scheduleCardHtml).join("") : `<div class="empty-hint">일정이 없습니다</div>`}
        </div>
        ${
          hiddenCount > 0
            ? `<button type="button" class="board-more-btn" data-toggle-more="${col.key}">더보기 (${hiddenCount})</button>`
            : ""
        }
      </div>`;
  }).join("");

  board.querySelectorAll("[data-toggle-more]").forEach((btn) => {
    btn.addEventListener("click", () => {
      const key = btn.dataset.toggleMore;
      const current = boardColumnVisibleCount.get(key) ?? BOARD_COLUMN_VISIBLE_LIMIT;
      boardColumnVisibleCount.set(key, current + BOARD_COLUMN_VISIBLE_LIMIT);
      renderBoard();
    });
  });

  board.querySelectorAll("[data-status-for]").forEach((sel) => {
    sel.addEventListener("change", async () => {
      const id = sel.dataset.statusFor;
      await updateScheduleStatus(id, sel.value);
    });
  });

  board.querySelectorAll("[data-edit]").forEach((btn) => {
    btn.addEventListener("click", () => openEditModal(btn.dataset.edit));
  });

  board.querySelectorAll("[data-delete]").forEach((btn) => {
    btn.addEventListener("click", () => deleteSchedule(btn.dataset.delete));
  });
}

// ---------- 일 / 주 / 월 / 년 캘린더 뷰 ----------

const WEEKDAY_LABELS_KO = ["일", "월", "화", "수", "목", "금", "토"];
const HOUR_PX = 48; // 타임그리드(일/주간)에서 1시간이 차지하는 픽셀 높이

const viewSwitcherEl = document.getElementById("view-switcher");
const viewNavEl = document.getElementById("view-nav");
const viewRangeLabelEl = document.getElementById("view-range-label");
const calendarNavEl = document.getElementById("calendar-nav");
const calendarRangeLabelEl = document.getElementById("calendar-range-label");
const calendarViewEl = document.getElementById("calendar-view");

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
    .map((s) => ({ ...s, start: new Date(s.startAt), end: new Date(s.endAt) }))
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
  calendarNavEl.classList.toggle("show", mode !== "board");
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
  renderScheduleRadar(); // 레이더는 뷰/날짜 전환마다 그 시점의 집계 범위로 다시 그려야 한다
  if (viewMode === "board") {
    renderBoard();
    return;
  }
  updateViewRangeLabel();
  if (viewMode === "day") renderDayOrWeekView(1);
  else if (viewMode === "week") renderDayOrWeekView(7);
  else if (viewMode === "month") renderMonthView();
  else if (viewMode === "year") renderYearView();
}

function updateViewRangeLabel() {
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
  calendarRangeLabelEl.textContent = viewRangeLabelEl.textContent;
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
      chip.textContent = s.title;
      chip.addEventListener("click", () => openEditModal(s.id));
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
      timeEl.textContent = `${formatDateTime(s.startAt)} - ${formatDateTime(s.endAt)}`;
      block.appendChild(titleEl);
      block.appendChild(timeEl);

      block.addEventListener("click", () => openEditModal(s.id));
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
document.getElementById("view-nav-today").addEventListener("click", () => {
  viewDate = new Date();
  refreshVisibleView();
});

// 달력 그리드 바로 위 중앙 네비게이션 - 툴바의 이전/오늘/다음과 동일하게 동작한다
document.getElementById("calendar-nav-prev").addEventListener("click", () => navigateView(-1));
document.getElementById("calendar-nav-next").addEventListener("click", () => navigateView(1));
document.getElementById("calendar-nav-today").addEventListener("click", () => {
  viewDate = new Date();
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
    showToast("상태 변경 실패: " + err.message);
    renderBoard();
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
    showToast("삭제 실패: " + err.message);
  }
}

// ---------- 생성/수정 모달 ----------

const modalOverlay = document.getElementById("schedule-modal-overlay");
const scheduleForm = document.getElementById("schedule-form");
const modalTitle = document.getElementById("modal-title");

function openCreateModal() {
  modalTitle.textContent = "새 일정";
  scheduleForm.reset();
  document.getElementById("schedule-id").value = "";
  document.getElementById("status-select").value = "PENDING";
  const currentUser = API.getCurrentUser();
  document.getElementById("user-id-input").value = (currentUser && currentUser.id) || "";
  if (categories.length) categorySelect.value = String(categories[0].id);
  modalOverlay.classList.add("show");
}

function openEditModal(id) {
  const s = schedules.find((x) => String(x.id) === String(id));
  if (!s) return;
  const meta = scheduleMeta.get(String(id)) || {};

  modalTitle.textContent = "일정 수정";
  document.getElementById("schedule-id").value = s.id;
  document.getElementById("title").value = s.title;
  document.getElementById("content").value = s.content || "";
  document.getElementById("startAt").value = toDatetimeLocalValue(s.startAt);
  document.getElementById("endAt").value = toDatetimeLocalValue(s.endAt);
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

document.getElementById("open-create-modal").addEventListener("click", openCreateModal);
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
    endAt: document.getElementById("endAt").value,
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
    closeModal();
    showToast(id ? "일정을 수정했습니다." : "일정을 추가했습니다.");
    await refreshAll();
  } catch (err) {
    showToast("저장 실패: " + err.message);
  }
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
    showToast("추가 실패: " + err.message);
  }
});

// ---------- 로그아웃 ----------

document.getElementById("logout-btn").addEventListener("click", async () => {
  try {
    await API.post("/api/auth/logout", {});
  } catch (err) {
    // 로그아웃 실패해도 세션은 정리하고 로그인 화면으로 보낸다
  }
  API.clearSession();
  window.location.href = "/login";
});

// ---------- 초기화 ----------

(async function init() {
  if (!requireAuth()) return;
  renderUserChip();
  renderToday();
  await loadCategories();
  await loadSchedules();
  // "지금" 표시선이 실제 흐르는 시간을 따라가도록 주기적으로 다시 그린다 (데이터 재조회는 없음)
  setInterval(renderTodayClock, 60000);
})();
