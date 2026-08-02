const toast = document.getElementById("toast");

function showToast(message) {
  toast.textContent = message;
  toast.classList.add("show");
  setTimeout(() => toast.classList.remove("show"), 2600);
}

function requireAuth() {
  if (!API.getToken()) {
    window.location.href = "/login";
    return false;
  }
  return true;
}

function escapeHtml(str) {
  return String(str ?? "").replace(/[&<>"']/g, (m) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  }[m]));
}

function renderUserChip() {
  const user = API.getCurrentUser();
  const email = (user && user.email) || "-";
  const initial = email !== "-" ? email[0].toUpperCase() : "?";
  document.getElementById("user-avatar").textContent = initial;
  document.getElementById("user-name").textContent = (user && user.email) || "사용자";
  document.getElementById("user-email").textContent = email;
}

const STATUS_LABELS = { PENDING: "대기", IN_PROGRESS: "진행중", COMPLETED: "완료", CANCELLED: "취소" };
const STATUS_ORDER = ["PENDING", "IN_PROGRESS", "COMPLETED", "CANCELLED"];

// 카테고리는 사용자마다 개수/이름이 제각각이라 상태(PENDING 등)처럼 고정 색을 못 쓴다 - 파이차트/범례에서
// 카테고리 인덱스 순서대로 순환해서 쓰는 고정 팔레트. --color-pending 등 상태 색과 겹치지 않는 색으로 골랐다
const CATEGORY_COLORS = ["#6366f1", "#22c55e", "#f59e0b", "#ec4899", "#06b6d4", "#8b5cf6", "#f97316", "#14b8a6", "#ef4444", "#84cc16"];

// 서버 LocalDate 응답("yyyy-MM-dd")을 그대로 쿼리 파라미터로 되돌려보낼 때 쓰는 포맷 - toISOString()은
// UTC로 변환되며 로컬 자정 근처 날짜가 하루 밀릴 수 있어 로컬 연/월/일을 직접 이어붙인다
function formatLocalDate(d) {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

let currentPeriod = "WEEK";
let referenceDate = new Date();

const periodSwitcherEl = document.getElementById("report-period-switcher");
const rangeLabelEl = document.getElementById("report-range-label");
const totalCountEl = document.getElementById("report-total-count");
const completionRateEl = document.getElementById("report-completion-rate");
const comparisonEl = document.getElementById("report-comparison");
const statusListEl = document.getElementById("report-status-list");
const pieEl = document.getElementById("report-pie");
const legendEl = document.getElementById("report-legend");
const insightBtn = document.getElementById("report-insight-btn");
const insightBodyEl = document.getElementById("report-insight-body");

function renderStatusList(statusCounts) {
  const byStatus = Object.fromEntries(statusCounts.map((s) => [s.status, s.count]));
  statusListEl.innerHTML = STATUS_ORDER.map((status) => `
    <li class="report-status-item">
      <span class="status-dot ${status}"></span>
      <span class="report-status-name">${STATUS_LABELS[status]}</span>
      <span class="report-status-count">${byStatus[status] || 0}건</span>
    </li>
  `).join("");
}

function renderComparison(previous) {
  if (!previous || previous.totalCount === 0) {
    comparisonEl.innerHTML = `<span class="report-comparison-empty">직전 동일 기간에는 일정이 없었어요.</span>`;
    return;
  }
  const countDelta = previous.totalCountDelta;
  const rateDeltaPct = Math.round(previous.completionRateDelta * 1000) / 10;
  const countCls = countDelta > 0 ? "up" : countDelta < 0 ? "down" : "flat";
  const rateCls = rateDeltaPct > 0 ? "up" : rateDeltaPct < 0 ? "down" : "flat";
  comparisonEl.innerHTML = `
    직전 동일 기간 대비
    <span class="report-delta report-delta-${countCls}">일정 ${countDelta > 0 ? "+" : ""}${countDelta}건</span>,
    <span class="report-delta report-delta-${rateCls}">완료율 ${rateDeltaPct > 0 ? "+" : ""}${rateDeltaPct}%p</span>
  `;
}

function renderPieChart(categoryBreakdown) {
  if (categoryBreakdown.length === 0) {
    pieEl.style.background = "var(--color-border)";
    legendEl.innerHTML = `<li class="report-legend-empty">해당 기간에 등록된 일정이 없어요.</li>`;
    return;
  }

  let cursor = 0;
  const stops = categoryBreakdown.map((c, i) => {
    const color = CATEGORY_COLORS[i % CATEGORY_COLORS.length];
    const start = cursor;
    // 반올림 오차로 마지막 구간이 100%를 못 채우는 걸 방지하기 위해 마지막 항목은 100%까지 채운다
    const end = i === categoryBreakdown.length - 1 ? 100 : cursor + c.percentage;
    cursor = end;
    return { color, start, end };
  });
  pieEl.style.background = `conic-gradient(${stops.map((s) => `${s.color} ${s.start}% ${s.end}%`).join(", ")})`;

  legendEl.innerHTML = categoryBreakdown.map((c, i) => `
    <li class="report-legend-item">
      <span class="report-legend-dot" style="background:${CATEGORY_COLORS[i % CATEGORY_COLORS.length]}"></span>
      <span class="report-legend-name">${escapeHtml(c.categoryName)}</span>
      <span class="report-legend-value">${c.count}건 · ${c.percentage.toFixed(1)}%</span>
    </li>
  `).join("");
}

function periodLabel(period) {
  return { WEEK: "이번 주", MONTH: "이번 달", YEAR: "올해" }[period];
}

async function loadStats() {
  rangeLabelEl.textContent = "불러오는 중...";
  try {
    const stats = await API.get(`/api/reports/stats?period=${currentPeriod}&date=${formatLocalDate(referenceDate)}`);
    rangeLabelEl.textContent = `${stats.rangeStart} ~ ${stats.rangeEnd} (${periodLabel(stats.period)})`;
    totalCountEl.textContent = `${stats.totalCount}건`;
    completionRateEl.textContent = `${Math.round(stats.completionRate * 1000) / 10}%`;
    renderComparison(stats.previous);
    renderStatusList(stats.statusCounts);
    renderPieChart(stats.categoryBreakdown);
  } catch (err) {
    rangeLabelEl.textContent = "-";
    showToast(`리포트를 불러오지 못했습니다. ${err.message}`);
  }
}

async function loadInsight() {
  insightBtn.disabled = true;
  insightBtn.textContent = "생성 중...";
  insightBodyEl.innerHTML = "";
  try {
    const insight = await API.get(`/api/reports/insight?period=${currentPeriod}&date=${formatLocalDate(referenceDate)}`);
    const strengthsHtml = insight.strengths.length
      ? `<ul>${insight.strengths.map((s) => `<li>${escapeHtml(s)}</li>`).join("")}</ul>`
      : `<p class="report-insight-empty">이번 기간엔 특별히 짚을 만한 점이 없었어요.</p>`;
    const improvementsHtml = insight.improvements.length
      ? `<ul>${insight.improvements.map((s) => `<li>${escapeHtml(s)}</li>`).join("")}</ul>`
      : `<p class="report-insight-empty">아쉬운 점은 딱히 없었어요.</p>`;
    insightBodyEl.innerHTML = `
      <div class="report-insight-section">
        <h3>👍 잘한 점</h3>
        ${strengthsHtml}
      </div>
      <div class="report-insight-section">
        <h3>🌱 아쉬운 점</h3>
        ${improvementsHtml}
      </div>
      <div class="report-insight-section">
        <h3>📊 행동 패턴</h3>
        <p>${escapeHtml(insight.behaviorPattern || "-")}</p>
      </div>
      <div class="report-insight-section">
        <h3>✨ 성향</h3>
        <p>${escapeHtml(insight.personalityNote || "-")}</p>
      </div>
    `;
  } catch (err) {
    insightBodyEl.innerHTML = `<p class="report-insight-empty">AI 코멘트를 생성하지 못했어요. ${escapeHtml(err.message)}</p>`;
  } finally {
    insightBtn.disabled = false;
    insightBtn.textContent = "AI 코멘트 생성";
  }
}

function shiftReferenceDate(delta) {
  const d = new Date(referenceDate);
  if (currentPeriod === "WEEK") {
    d.setDate(d.getDate() + 7 * delta);
  } else if (currentPeriod === "MONTH") {
    d.setDate(1); // 월말 근처 날짜에서 매달 하루 수가 달라 밀리는 것을 방지
    d.setMonth(d.getMonth() + delta);
  } else {
    d.setDate(1);
    d.setMonth(0);
    d.setFullYear(d.getFullYear() + delta);
  }
  referenceDate = d;
}

periodSwitcherEl.addEventListener("click", (e) => {
  const btn = e.target.closest("button[data-period]");
  if (!btn) return;
  currentPeriod = btn.dataset.period;
  periodSwitcherEl.querySelectorAll(".view-tab").forEach((t) => t.classList.toggle("active", t === btn));
  insightBodyEl.innerHTML = ""; // 기간이 바뀌면 이전 기간의 AI 코멘트를 그대로 보여주지 않는다
  loadStats();
});

document.getElementById("report-prev-btn").addEventListener("click", () => {
  shiftReferenceDate(-1);
  insightBodyEl.innerHTML = "";
  loadStats();
});

document.getElementById("report-next-btn").addEventListener("click", () => {
  shiftReferenceDate(1);
  insightBodyEl.innerHTML = "";
  loadStats();
});

document.getElementById("report-today-btn").addEventListener("click", () => {
  referenceDate = new Date();
  insightBodyEl.innerHTML = "";
  loadStats();
});

insightBtn.addEventListener("click", loadInsight);

document.getElementById("logout-btn").addEventListener("click", async () => {
  try {
    await API.post("/api/auth/logout", {});
  } catch (e) {
    // 로그아웃 API 실패해도 로컬 세션은 정리하고 로그인 화면으로 보낸다
  }
  API.clearSession();
  window.location.href = "/login";
});

(async function init() {
  if (!requireAuth()) return;
  renderUserChip();
  await loadStats();
})();
