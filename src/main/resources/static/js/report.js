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

const prefersReducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

// 총 일정/완료율 숫자를 0에서 실제 값까지 세어 올라가는 애니메이션 - CSS로는 텍스트 콘텐츠 자체를
// 바꿀 수 없어(카운트업은 숫자 문자열을 매 프레임 다시 그려야 함) 여기만 JS로 직접 구현한다. 나머지
// (파이차트/선 그래프/범례)는 CSS 애니메이션 + 클래스 재적용으로 처리한다(각 render 함수 참고)
function animateNumber(el, to, durationMs, formatFn) {
  if (prefersReducedMotion) {
    el.textContent = formatFn(to);
    return;
  }
  const start = performance.now();
  function tick(now) {
    const progress = Math.min((now - start) / durationMs, 1);
    const eased = 1 - Math.pow(1 - progress, 3); // ease-out cubic
    el.textContent = formatFn(to * eased);
    if (progress < 1) requestAnimationFrame(tick);
    else el.textContent = formatFn(to);
  }
  requestAnimationFrame(tick);
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

// 카테고리는 사용자마다 개수/이름이 제각각이라 상태(PENDING 등)처럼 고정 색을 못 쓴다 - 파이차트/범례/추이
// 그래프에서 카테고리 인덱스 순서대로(항상 이 순서 고정, 절대 임의로 섞지 않음) 쓰는 팔레트. dataviz
// 스킬의 검증된 8슬롯 categorical 테마(references/palette.md, 라이트 모드 값 - 이 앱은 다크모드 미지원)를
// 그대로 썼다: 인접 쌍 기준 CVD ΔE ≥ 8, 일반 시야 ΔE ≥ 15를 모두 통과한 순서라 임의로 재배열하지 않는다.
// 카테고리가 8개를 넘으면 다시 처음 색부터 순환한다(그 이상은 흔치 않은 경우라 "기타"로 접는 로직까지는
// 두지 않음).
const CATEGORY_COLORS = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300", "#4a3aa7", "#e34948"];

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
const pieWrapEl = document.getElementById("report-pie-wrap");
const pieEl = document.getElementById("report-pie");
const pieTooltipEl = document.getElementById("report-pie-tooltip");
const legendEl = document.getElementById("report-legend");
const trendChartBoxEl = document.getElementById("report-trend-chart-box");
const trendLegendEl = document.getElementById("report-trend-legend");
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

// pieEl은 innerHTML로 다시 만들지 않고 style.background만 바꾸는 고정 DOM 노드라, 클래스를 붙이는 것만으로는
// 두 번째 렌더부터 CSS 애니메이션이 재생되지 않는다(브라우저가 "이미 붙어있던 클래스"로 보고 무시함) -
// 클래스를 뗐다가 강제로 리플로우시킨 뒤 다시 붙이는 표준 트릭으로 매번 처음부터 재생시킨다
function replayAnimation(el, className) {
  if (prefersReducedMotion) return;
  el.classList.remove(className);
  void el.offsetWidth; // 리플로우 강제 - 이 줄이 없으면 remove/add가 같은 프레임에 묶여 재생되지 않는다
  el.classList.add(className);
}

// 파이차트는 SVG가 아니라 conic-gradient 배경 하나뿐인 div라 조각별 DOM 요소/hit-test가 없다 - 호버 시
// 마우스 각도로 어느 조각인지 계산해야 해서(bindPieHover 참고), renderPieChart가 매번 채운 이 배열에
// 조각별 시작/끝 각도(%)·색·카테고리 정보를 저장해두고 호버 핸들러가 참조한다
let pieSlices = [];

function renderPieChart(categoryBreakdown) {
  if (categoryBreakdown.length === 0) {
    pieEl.style.background = "var(--color-border)";
    legendEl.innerHTML = `<li class="report-legend-empty">해당 기간에 등록된 일정이 없어요.</li>`;
    pieSlices = [];
    return;
  }

  let cursor = 0;
  pieSlices = categoryBreakdown.map((c, i) => {
    const color = CATEGORY_COLORS[i % CATEGORY_COLORS.length];
    const start = cursor;
    // 반올림 오차로 마지막 구간이 100%를 못 채우는 걸 방지하기 위해 마지막 항목은 100%까지 채운다
    const end = i === categoryBreakdown.length - 1 ? 100 : cursor + c.percentage;
    cursor = end;
    return { color, start, end, categoryName: c.categoryName, count: c.count, percentage: c.percentage };
  });
  pieEl.style.background = `conic-gradient(${pieSlices.map((s) => `${s.color} ${s.start}% ${s.end}%`).join(", ")})`;
  replayAnimation(pieEl, "report-pie-animate-in");

  // 범례 <li>는 매번 innerHTML로 새로 만드는 요소라 별도 리플로우 없이도 애니메이션이 항상 처음부터
  // 재생된다 - 항목마다 --stagger-index로 순서대로 살짝 늦게 나타나게 한다
  legendEl.innerHTML = categoryBreakdown.map((c, i) => `
    <li class="report-legend-item" style="--stagger-index:${i}">
      <span class="report-legend-dot" style="background:${CATEGORY_COLORS[i % CATEGORY_COLORS.length]}"></span>
      <span class="report-legend-name">${escapeHtml(c.categoryName)}</span>
      <span class="report-legend-value">${c.count}건 · ${c.percentage.toFixed(1)}%</span>
    </li>
  `).join("");
}

// 마우스 위치를 파이 중심 기준 각도로 변환해 어느 조각 위에 있는지 계산한다 - conic-gradient의 기본
// 시작각(12시 방향)·회전 방향(시계 방향)과 맞춰야 pieSlices의 start/end(%)와 일치한다. pieEl은
// innerHTML로 다시 만들어지지 않는 고정 노드라 리스너는 한 번만 붙이면 되고, 매 렌더마다 최신
// pieSlices를 참조하기만 하면 된다
function bindPieHover() {
  pieEl.addEventListener("mousemove", (e) => {
    if (pieSlices.length === 0) return;

    const rect = pieEl.getBoundingClientRect();
    const radius = rect.width / 2;
    const dx = e.clientX - (rect.left + radius);
    const dy = e.clientY - (rect.top + radius);
    if (Math.sqrt(dx * dx + dy * dy) > radius) {
      pieTooltipEl.style.display = "none";
      return;
    }

    let angleDeg = Math.atan2(dx, -dy) * (180 / Math.PI); // 0deg = 12시, 시계 방향으로 증가
    if (angleDeg < 0) angleDeg += 360;
    const pct = (angleDeg / 360) * 100;
    const slice = pieSlices.find((s) => pct >= s.start && pct < s.end) || pieSlices[pieSlices.length - 1];

    pieTooltipEl.innerHTML = `
      <div class="report-trend-tooltip-row">
        <span class="report-legend-dot" style="background:${slice.color}"></span>
        <span>${escapeHtml(slice.categoryName)}</span>
        <strong>${slice.percentage.toFixed(1)}%</strong>
      </div>
    `;
    pieTooltipEl.style.display = "block";

    const wrapRect = pieWrapEl.getBoundingClientRect();
    const left = Math.min(
      Math.max(e.clientX - wrapRect.left + 12, 0),
      Math.max(wrapRect.width - pieTooltipEl.offsetWidth - 4, 0)
    );
    pieTooltipEl.style.left = `${left}px`;
    pieTooltipEl.style.top = `${e.clientY - wrapRect.top + 12}px`;
  });

  pieEl.addEventListener("mouseleave", () => {
    pieTooltipEl.style.display = "none";
  });
}

bindPieHover();

const TREND_VIEW_W = 480;
const TREND_VIEW_H = 140;
const TREND_PAD = { left: 30, right: 18, top: 10, bottom: 20 };
const TREND_DRAW_MS = 1350; // 선 하나가 그려지는 데 걸리는 시간(CSS의 report-trend-line 애니메이션 시간과 맞춰야 함)

// 축 눈금을 "깔끔한" 값으로 반올림한다(dataviz 스킬 - "Y축 눈금은 깔끔한 숫자로 반올림") - 1/2/5의
// 배수만 쓰는 표준 nice-number 올림
function niceCeil(value) {
  if (value <= 0) return 1;
  const exp = Math.floor(Math.log10(value));
  const base = Math.pow(10, exp);
  const norm = value / base;
  const niceNorm = norm <= 1 ? 1 : norm <= 2 ? 2 : norm <= 5 ? 5 : 10;
  return niceNorm * base;
}

// 선이 실제로 "그려지는" 속도로 보이려면 dasharray/dashoffset을 그 선의 실제 경로 길이에 맞춰야 한다 -
// 고정된 큰 값(예: 4000)을 실제 길이(주로 150~400 정도)보다 훨씬 크게 잡으면, dashoffset이 실제 길이만큼만
// 줄어도 이미 선 전체가 드러나버려서 애니메이션 지속시간의 극히 일부(길이 비율만큼)만에 다 그려지고
// 나머지 시간은 멈춰있는 것처럼 보인다(실제로 겪은 버그 - 카테고리가 적어 경로가 짧은 WEEK 뷰에서 특히
// 심했다). getTotalLength()로 실측한 뒤 두 번의 requestAnimationFrame을 거쳐 dashoffset을 0으로 바꿔야
// "숨겨진 상태"가 실제로 한 프레임 페인트된 뒤에 transition이 걸린다(리플로우 없이 같은 프레임에서 바로
// 바꾸면 transition 없이 최종 상태로 바로 그려짐)
function playTrendLineDrawIn(container) {
  if (prefersReducedMotion) return; // dasharray를 건드리지 않으면 기본적으로 완성된 선 그대로 보인다
  container.querySelectorAll(".report-trend-line").forEach((el) => {
    const length = el.getTotalLength();
    el.style.strokeDasharray = `${length}`;
    // CSS transition으로 (style을 두 번 바꿔) 트리거하는 방식은 막 DOM에 삽입된 요소에서 "이전 상태"가
    // 실제로 한 프레임 페인트됐다는 보장이 없어 트랜지션 없이 즉시 완료돼버리는 경우가 있었다(더블
    // requestAnimationFrame으로도 재현됨, 실측 확인) - Web Animations API는 시작/종료 값을 한 번의
    // 호출로 넘겨받으므로 이 문제 자체가 없다
    el.animate(
      [{ strokeDashoffset: length }, { strokeDashoffset: 0 }],
      { duration: TREND_DRAW_MS, easing: "ease-out", fill: "forwards" }
    );
  });
}

// 카테고리별 선 그래프 - 직접 그린 SVG(2px 선, 끝점 마커, 옅은 회색 격자선)에 크로스헤어+툴팁 호버를
// 붙인다. 카테고리 색은 파이차트/범례와 같은 인덱스 순서를 그대로 써서 같은 카테고리는 같은 색으로 보인다
function renderCategoryTrendChart(trend) {
  const { bucketLabels, series } = trend;
  const n = bucketLabels.length;

  if (series.length === 0 || n === 0) {
    trendChartBoxEl.innerHTML = `<p class="report-legend-empty">해당 기간에 등록된 일정이 없어요.</p>`;
    trendLegendEl.innerHTML = "";
    return;
  }

  const plotW = TREND_VIEW_W - TREND_PAD.left - TREND_PAD.right;
  const plotH = TREND_VIEW_H - TREND_PAD.top - TREND_PAD.bottom;
  const stepX = n > 1 ? plotW / (n - 1) : 0;
  const xAt = (i) => TREND_PAD.left + stepX * i;

  const rawMax = Math.max(0, ...series.flatMap((s) => s.counts));
  const niceMax = niceCeil(rawMax);
  const yAt = (v) => TREND_PAD.top + plotH - (v / niceMax) * plotH;

  const yTicks = Array.from(new Set([0, Math.round(niceMax / 2), niceMax]));
  const gridlinesSvg = yTicks.map((t) => `
    <line x1="${TREND_PAD.left}" y1="${yAt(t)}" x2="${TREND_VIEW_W - TREND_PAD.right}" y2="${yAt(t)}" class="report-trend-gridline" />
    <text x="${TREND_PAD.left - 6}" y="${yAt(t)}" class="report-trend-axis-label" text-anchor="end" dominant-baseline="middle">${t}</text>
  `).join("");

  // 구간(bucket)이 많은 MONTH(최대 31개)에서 x축 라벨이 다 겹치지 않도록 일정 간격으로만 그린다 -
  // 데이터 포인트/선 자체는 모든 구간을 그대로 쓰고, 축 "글자"만 솎아낸다
  const labelStep = n <= 10 ? 1 : Math.ceil(n / 8);
  const xLabelsSvg = bucketLabels.map((label, i) => {
    if (i % labelStep !== 0 && i !== n - 1) return "";
    return `<text x="${xAt(i)}" y="${TREND_VIEW_H - 6}" class="report-trend-axis-label" text-anchor="middle">${escapeHtml(label)}</text>`;
  }).join("");

  // 선은 dasharray/dashoffset을 실제 경로 길이보다 넉넉히 큰 고정값으로 뒀다가 0으로 줄어드는 애니메이션으로
  // "그려지는" 느낌을 낸다 - 매 렌더마다 실제 path.getTotalLength()를 재는 대신 이 고정값 트릭을 쓰면 DOM
  // 삽입 후 별도 JS 없이 CSS keyframes만으로 처리된다(이 차트의 작은 viewBox 기준 실제 최대 경로 길이보다
  // 넉넉히 크게 잡음). 모든 카테고리 선이 동시에 그려지기 시작하고(시리즈 간 지연 없음), 각 점은 자기
  // 선이 그 지점까지 그려지는 시점에 맞춰 포인트 비율만큼만 지연시켜 "선을 따라 찍히는" 것처럼 보이게 한다
  const linesSvg = series.map((s, i) => {
    const color = CATEGORY_COLORS[i % CATEGORY_COLORS.length];
    const d = s.counts.map((v, idx) => `${idx === 0 ? "M" : "L"}${xAt(idx)},${yAt(v)}`).join(" ");
    const dotsSvg = s.counts.map((v, idx) => {
      const dotDelay = (idx / Math.max(n - 1, 1)) * TREND_DRAW_MS;
      return `<circle cx="${xAt(idx)}" cy="${yAt(v)}" r="3" fill="${color}" stroke="var(--color-surface)" stroke-width="1.5" class="report-trend-dot" style="--dot-delay:${dotDelay}ms"></circle>`;
    }).join("");
    return `
      <path d="${d}" fill="none" stroke="${color}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="report-trend-line"></path>
      ${dotsSvg}
    `;
  }).join("");

  trendChartBoxEl.innerHTML = `
    <svg viewBox="0 0 ${TREND_VIEW_W} ${TREND_VIEW_H}" class="report-trend-svg" id="report-trend-svg">
      <g class="report-trend-axis-group">${gridlinesSvg}${xLabelsSvg}</g>
      ${linesSvg}
      <line class="report-trend-crosshair" id="report-trend-crosshair" x1="0" y1="${TREND_PAD.top}" x2="0" y2="${TREND_VIEW_H - TREND_PAD.bottom}" style="display:none"></line>
      <rect x="${TREND_PAD.left}" y="${TREND_PAD.top}" width="${plotW}" height="${plotH}" fill="transparent" id="report-trend-hover-area"></rect>
    </svg>
    <div class="report-trend-tooltip" id="report-trend-tooltip" style="display:none"></div>
  `;

  playTrendLineDrawIn(trendChartBoxEl);

  // 범례는 파이차트 쪽과 색이 겹치므로 여기서는 이름만 (건수/비율은 파이 범례가 이미 보여줌) - 단일
  // 카테고리뿐이면 범례 없이도 제목만으로 식별 가능하므로 생략한다
  trendLegendEl.innerHTML = series.length > 1
    ? series.map((s, i) => `
      <li class="report-legend-item" style="--stagger-index:${i}">
        <span class="report-legend-dot" style="background:${CATEGORY_COLORS[i % CATEGORY_COLORS.length]}"></span>
        <span class="report-legend-name">${escapeHtml(s.categoryName)}</span>
      </li>
    `).join("")
    : "";

  bindTrendHover(trend, xAt, n);
}

function bindTrendHover(trend, xAt, n) {
  const svgEl = document.getElementById("report-trend-svg");
  const hoverAreaEl = document.getElementById("report-trend-hover-area");
  const crosshairEl = document.getElementById("report-trend-crosshair");
  const tooltipEl = document.getElementById("report-trend-tooltip");
  if (!svgEl || !hoverAreaEl) return;

  const stepX = n > 1 ? (TREND_VIEW_W - TREND_PAD.left - TREND_PAD.right) / (n - 1) : 0;

  hoverAreaEl.addEventListener("mousemove", (e) => {
    const rect = svgEl.getBoundingClientRect();
    const scaleX = TREND_VIEW_W / rect.width;
    const xInSvg = (e.clientX - rect.left) * scaleX;
    let idx = stepX > 0 ? Math.round((xInSvg - TREND_PAD.left) / stepX) : 0;
    idx = Math.max(0, Math.min(n - 1, idx));

    crosshairEl.setAttribute("x1", xAt(idx));
    crosshairEl.setAttribute("x2", xAt(idx));
    crosshairEl.style.display = "block";

    const rows = trend.series.map((s, i) => `
      <div class="report-trend-tooltip-row">
        <span class="report-legend-dot" style="background:${CATEGORY_COLORS[i % CATEGORY_COLORS.length]}"></span>
        <span>${escapeHtml(s.categoryName)}</span>
        <strong>${s.counts[idx]}건</strong>
      </div>
    `).join("");
    tooltipEl.innerHTML = `<div class="report-trend-tooltip-label">${escapeHtml(trend.bucketLabels[idx])}</div>${rows}`;
    tooltipEl.style.display = "block";

    const wrapRect = trendChartBoxEl.getBoundingClientRect();
    const left = Math.min(
      Math.max(e.clientX - wrapRect.left + 12, 0),
      Math.max(wrapRect.width - tooltipEl.offsetWidth - 4, 0)
    );
    tooltipEl.style.left = `${left}px`;
    tooltipEl.style.top = `${e.clientY - wrapRect.top + 12}px`;
  });

  hoverAreaEl.addEventListener("mouseleave", () => {
    crosshairEl.style.display = "none";
    tooltipEl.style.display = "none";
  });
}

function periodLabel(period) {
  return { WEEK: "이번 주", MONTH: "이번 달", YEAR: "올해" }[period];
}

async function loadStats() {
  rangeLabelEl.textContent = "불러오는 중...";
  try {
    const stats = await API.get(`/api/reports/stats?period=${currentPeriod}&date=${formatLocalDate(referenceDate)}`);
    rangeLabelEl.textContent = `${stats.rangeStart} ~ ${stats.rangeEnd} (${periodLabel(stats.period)})`;
    animateNumber(totalCountEl, stats.totalCount, 800, (v) => `${Math.round(v)}건`);
    animateNumber(completionRateEl, stats.completionRate * 100, 800, (v) => `${Math.round(v * 10) / 10}%`);
    renderComparison(stats.previous);
    renderStatusList(stats.statusCounts);
    renderPieChart(stats.categoryBreakdown);
    renderCategoryTrendChart(stats.categoryTrend);
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
