// 재사용 가능한 스포트라이트 투어 엔진 - 대상 요소만 밝게 남기고 나머지는 어둡게 가린 채(전체 화면
// 클릭 차단 포함) 말풍선으로 순서대로 안내한다. 페이지별 사용처(대시보드/만다라트)는
// createSpotlightTour(steps, storageKeyPrefix)만 호출하면 되고, steps 배열([{target, title, text,
// placement}])과 "봤음" 여부를 기억할 storageKeyPrefix만 서로 다르게 넘기면 각 페이지의 투어를
// 독립적으로 기억한다. api.js(API)/각 페이지 스크립트(escapeHtml)가 먼저 로드돼 있어야 한다.
function createSpotlightTour(steps, storageKeyPrefix) {
  function seenKey() {
    const user = API.getCurrentUser();
    const email = (user && user.email) || "anon";
    return `${storageKeyPrefix}:${email}`;
  }

  function hasSeen() {
    return localStorage.getItem(seenKey()) === "true";
  }

  function markSeen() {
    localStorage.setItem(seenKey(), "true");
  }

  let backdropEl = null;
  let spotlightEl = null;
  let highlightEl = null;
  let tooltipEl = null;
  let stepIndex = 0;

  function buildOverlay() {
    backdropEl = document.createElement("div");
    backdropEl.className = "tour-backdrop";

    spotlightEl = document.createElement("div");
    spotlightEl.className = "tour-spotlight";

    // 스텝이 highlightTargets를 지정하면(예: 만다라트 중앙 9칸) 스포트라이트 안쪽 일부만 추가로
    // 파스텔 색 틴트를 얹는다 - 하이라이트할 게 없는 스텝에서는 항상 숨겨둔다(positionForStep 참고)
    highlightEl = document.createElement("div");
    highlightEl.className = "tour-highlight-region";
    highlightEl.style.display = "none";

    tooltipEl = document.createElement("div");
    tooltipEl.className = "tour-tooltip";
    tooltipEl.setAttribute("role", "dialog");
    tooltipEl.setAttribute("aria-label", "기능 안내");

    document.body.appendChild(backdropEl);
    document.body.appendChild(spotlightEl);
    document.body.appendChild(highlightEl);
    document.body.appendChild(tooltipEl);
  }

  function teardownOverlay() {
    window.removeEventListener("resize", handleReposition);
    [backdropEl, spotlightEl, highlightEl, tooltipEl].forEach((el) => el && el.remove());
    backdropEl = spotlightEl = highlightEl = tooltipEl = null;
  }

  function endTour() {
    markSeen();
    teardownOverlay();
  }

  // step.target은 셀렉터 문자열 하나 또는 여러 개를 묶은 배열일 수 있다(예: "반복 일정"+"새 일정"
  // 버튼처럼 서로 다른 두 요소를 한 스포트라이트 안에 같이 강조하고 싶은 경우) - 배열이면 각 요소의
  // 사각형을 모두 포함하는 최소 사각형을 구한다
  function resolveTargetElements(target) {
    const selectors = Array.isArray(target) ? target : [target];
    return selectors.map((sel) => document.querySelector(sel)).filter(Boolean);
  }

  function unionRect(elements) {
    const rects = elements.map((el) => el.getBoundingClientRect());
    const top = Math.min(...rects.map((r) => r.top));
    const left = Math.min(...rects.map((r) => r.left));
    const right = Math.max(...rects.map((r) => r.right));
    const bottom = Math.max(...rects.map((r) => r.bottom));
    return { top, left, right, bottom, width: right - left, height: bottom - top };
  }

  // display:none 이거나(예: 보드를 아직 안 골라 숨겨진 "AI로 채우기" 버튼) 아직 내용이 없어 높이가
  // 0인 컨테이너(예: 보드가 하나도 없을 때의 빈 9x9 격자)는 하이라이트할 수 없으므로 건너뛴다
  function isStepUsable(step) {
    const elements = resolveTargetElements(step.target);
    if (elements.length === 0) return false;
    const rect = unionRect(elements);
    return rect.width > 4 && rect.height > 4;
  }

  // 대상의 실제 화면 위치에 맞춰 스포트라이트와 말풍선을 다시 그린다. 세 오버레이 요소가 전부
  // position:fixed 라, getBoundingClientRect()(뷰포트 기준 좌표)를 그대로 top/left 로 써도
  // 스크롤 오프셋을 따로 계산할 필요가 없다
  function positionForStep(step) {
    const elements = resolveTargetElements(step.target);
    if (elements.length === 0) return false;

    elements[0].scrollIntoView({ block: "center", inline: "nearest" });
    const rect = unionRect(elements);
    const pad = 8;

    spotlightEl.style.top = `${rect.top - pad}px`;
    spotlightEl.style.left = `${rect.left - pad}px`;
    spotlightEl.style.width = `${rect.width + pad * 2}px`;
    spotlightEl.style.height = `${rect.height + pad * 2}px`;

    positionHighlight(step.highlightTargets);
    positionTooltip(rect, step.placement || "bottom");
    return true;
  }

  // highlightTargets(선택 요소 CSS 셀렉터 배열)가 있으면 그 요소들을 모두 포함하는 최소 사각형을
  // 구해 스포트라이트 안쪽에 파스텔 색 틴트로 덧그린다(예: 만다라트 중앙 9칸만 콕 집어 강조) - 없거나
  // 대상을 못 찾으면 숨긴다
  function positionHighlight(selectors) {
    const rects = (selectors || [])
      .map((sel) => document.querySelector(sel))
      .filter(Boolean)
      .map((el) => el.getBoundingClientRect());

    if (rects.length === 0) {
      highlightEl.style.display = "none";
      return;
    }

    const top = Math.min(...rects.map((r) => r.top));
    const left = Math.min(...rects.map((r) => r.left));
    const right = Math.max(...rects.map((r) => r.right));
    const bottom = Math.max(...rects.map((r) => r.bottom));

    highlightEl.style.display = "block";
    highlightEl.style.top = `${top}px`;
    highlightEl.style.left = `${left}px`;
    highlightEl.style.width = `${right - left}px`;
    highlightEl.style.height = `${bottom - top}px`;
  }

  function positionTooltip(rect, placement) {
    const gap = 14;
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    const tw = tooltipEl.offsetWidth;
    const th = tooltipEl.offsetHeight;

    let top, left;
    switch (placement) {
      case "top":
        top = rect.top - th - gap;
        left = rect.left + rect.width / 2 - tw / 2;
        break;
      case "left":
        top = rect.top + rect.height / 2 - th / 2;
        left = rect.left - tw - gap;
        break;
      case "right":
        top = rect.top + rect.height / 2 - th / 2;
        left = rect.right + gap;
        break;
      case "bottom":
      default:
        top = rect.bottom + gap;
        left = rect.left + rect.width / 2 - tw / 2;
        break;
    }

    // 화면 밖으로 나가지 않게 clamp - 대상이 화면 가장자리에 붙어 있어도 말풍선은 항상 보인다
    top = Math.min(Math.max(top, 12), vh - th - 12);
    left = Math.min(Math.max(left, 12), vw - tw - 12);

    tooltipEl.style.top = `${top}px`;
    tooltipEl.style.left = `${left}px`;
    tooltipEl.dataset.placement = placement;
  }

  function renderTooltip(step, index, total) {
    const isFirst = index === 0;
    const isLast = index === total - 1;

    tooltipEl.innerHTML = `
      <button type="button" class="tour-tooltip-close" title="건너뛰기" aria-label="건너뛰기">×</button>
      <div class="tour-tooltip-step">${index + 1} / ${total}</div>
      <div class="tour-tooltip-title">${escapeHtml(step.title)}</div>
      <p class="tour-tooltip-text">${escapeHtml(step.text)}</p>
      <div class="tour-tooltip-actions">
        <button type="button" class="btn btn-ghost btn-sm" data-tour-skip>건너뛰기</button>
        <div class="tour-tooltip-nav">
          ${isFirst ? "" : `<button type="button" class="btn btn-ghost btn-sm" data-tour-prev>이전</button>`}
          <button type="button" class="btn btn-primary btn-sm" data-tour-next>${isLast ? "확인했어요" : "다음"}</button>
        </div>
      </div>
      <span class="tour-tooltip-tail"></span>
    `;

    tooltipEl.querySelector(".tour-tooltip-close").addEventListener("click", endTour);
    tooltipEl.querySelector("[data-tour-skip]").addEventListener("click", endTour);
    const prevBtn = tooltipEl.querySelector("[data-tour-prev]");
    if (prevBtn) prevBtn.addEventListener("click", () => goToStep(index - 1));
    tooltipEl.querySelector("[data-tour-next]").addEventListener("click", () => {
      if (isLast) endTour();
      else goToStep(index + 1);
    });
  }

  // 이번 화면에서 하이라이트할 수 없는 스텝(조건부 렌더/아직 빈 상태)은 같은 방향으로 다음/이전
  // 스텝을 계속 찾아본다 - 전부 안 되면 투어를 끝낸다
  function goToStep(index) {
    const direction = index >= stepIndex ? 1 : -1;
    let i = index;
    while (i >= 0 && i < steps.length && !isStepUsable(steps[i])) {
      i += direction;
    }
    if (i < 0 || i >= steps.length) {
      endTour();
      return;
    }

    stepIndex = i;
    const step = steps[i];
    // 말풍선 내용을 먼저 채워야 실제 크기(특히 높이, 텍스트 길이에 따라 달라짐)가 확정되고,
    // positionForStep의 offsetWidth/offsetHeight 측정이 그 확정된 크기를 기준으로 맞게 계산된다
    renderTooltip(step, i, steps.length);
    if (!positionForStep(step)) {
      endTour();
      return;
    }
  }

  function handleReposition() {
    if (!tooltipEl) return;
    positionForStep(steps[stepIndex]);
  }

  function start() {
    buildOverlay();
    stepIndex = 0;
    goToStep(0);
    window.addEventListener("resize", handleReposition);
  }

  function maybeStart() {
    if (!API.getToken() || hasSeen()) return;
    start();
  }

  return {
    start,
    maybeStart,
    isRunning: () => !!tooltipEl,
  };
}
