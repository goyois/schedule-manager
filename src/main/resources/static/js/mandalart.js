const GRID_SIZE = 9;

let boards = []; // MandalartBoardSummaryDto[]
let activeBoardId = null;
let activeCells = null; // row*9+col -> content

const toast = document.getElementById("toast");
const boardListEl = document.getElementById("board-list");
const gridEl = document.getElementById("mandalart-grid");
const boardTitleEl = document.getElementById("board-title");
const deleteBoardBtn = document.getElementById("delete-board-btn");

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

function renderUserChip() {
  const user = API.getCurrentUser();
  const email = (user && user.email) || "-";
  const initial = email !== "-" ? email[0].toUpperCase() : "?";
  document.getElementById("user-avatar").textContent = initial;
  document.getElementById("user-name").textContent = (user && user.email) || "사용자";
  document.getElementById("user-email").textContent = email;
}

function escapeHtml(str) {
  return String(str ?? "").replace(/[&<>"']/g, (m) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  }[m]));
}

async function loadBoards() {
  try {
    boards = await API.get("/api/mandalart");
  } catch (err) {
    boards = [];
    showToast(`만다라트 목록을 불러오지 못했습니다. ${err.message}`);
  }
  renderBoardList();
}

function renderBoardList() {
  boardListEl.innerHTML = boards
    .map(
      (b) => `
      <li data-board-id="${b.id}" class="${String(activeBoardId) === String(b.id) ? "active" : ""}">
        <span><span class="dot"></span>${escapeHtml(b.title)}</span>
      </li>`
    )
    .join("");

  boardListEl.querySelectorAll("li").forEach((li) => {
    li.addEventListener("click", () => loadBoard(li.dataset.boardId));
  });
}

async function loadBoard(id) {
  try {
    const board = await API.get(`/api/mandalart/${id}`);
    activeBoardId = board.id;
    activeCells = new Map(board.cells.map((c) => [`${c.row}-${c.col}`, c.content]));
    boardTitleEl.textContent = board.title;
    deleteBoardBtn.style.display = "";
    renderBoardList();
    renderGrid();
  } catch (err) {
    showToast(`만다라트를 불러오지 못했습니다. ${err.message}`);
  }
}

// 정중앙(4,4)이 속한 중앙 블록을 제외한 8개 블록의 색 슬롯 - 읽는 순서로 고정(dataviz 색상 배정 규칙:
// 카테고리 색은 항상 고정 순서로 배정하고, 데이터에 따라 순서를 바꾸지 않는다)
const BLOCK_HUE_CLASS = {
  0: "hue-1", 1: "hue-2", 2: "hue-3",
  3: "hue-4",             5: "hue-5",
  6: "hue-6", 7: "hue-7", 8: "hue-8",
};

// 이 칸이 색으로 표시될 때 어느 블록의 색을 따라야 하는지 계산한다.
// - 메인 목표(정중앙)는 블록 색이 없다(항상 고유하게 강조되는 별도 스타일).
// - 중앙 블록의 나머지 8칸은 "이 서브골이 어느 바깥 블록의 주제인지"를 나타내는 칸이라, 중심에서 본
//   같은 방향(오프셋)의 바깥 블록과 같은 색을 그대로 물려받는다 — "중심점을 기준으로" 색이 정해진다.
// - 바깥 블록의 9칸(자신의 중심 칸 포함)은 모두 그 블록 고유의 색을 쓴다.
function cellBlockHueClass(row, col) {
  const blockRow = Math.floor(row / 3);
  const blockCol = Math.floor(col / 3);

  if (blockRow === 1 && blockCol === 1) {
    if (row === 4 && col === 4) return null;
    const offsetRow = row - 3 - 1; // -1, 0, 1
    const offsetCol = col - 3 - 1;
    const representedBlockIndex = (1 + offsetRow) * 3 + (1 + offsetCol);
    return BLOCK_HUE_CLASS[representedBlockIndex];
  }

  return BLOCK_HUE_CLASS[blockRow * 3 + blockCol];
}

// 칸을 "평소엔 텍스트만 보여주다 클릭하면 textarea 로 교체" 방식으로 만들었더니, 아직 textarea 가
// 아닌 상태에서 마우스로 드래그해 텍스트를 선택하면(mousedown~mouseup) 그 직후에 뒤늦게 click 이벤트가
// 발생해 칸 전체가 새 textarea 로 통째로 교체돼버렸다 - 방금 만든 선택 영역이 그대로 사라지고, 새로
// 생긴 textarea 는 커서만 있고 선택 영역이 없는 상태라 이어서 Backspace 를 눌러도 아무 것도 지워지지
// 않았다. 그래서 처음부터 모든 칸을 textarea 로 렌더링해 이 모드 전환 자체를 없앴다
function renderGrid() {
  gridEl.innerHTML = "";
  if (!activeCells) return;

  for (let row = 0; row < GRID_SIZE; row++) {
    for (let col = 0; col < GRID_SIZE; col++) {
      const cell = document.createElement("div");
      cell.className = "mandalart-cell";
      // 화살표(.mandalart-arrow)가 경계선에 겹치도록 명시적 grid-row/grid-column 을 쓰기 때문에, 칸도
      // 전부 명시적으로 배치해야 한다 - 칸만 auto-placement 에 맡기면, CSS Grid 가 화살표가 차지한
      // 트랙을 "이미 예약됨"으로 보고 auto-flow 칸들을 건너뛰어 버려 전체 격자가 밀리는 문제가 있었다
      cell.style.gridRow = `${row + 1} / ${row + 2}`;
      cell.style.gridColumn = `${col + 1} / ${col + 2}`;
      if (col % 3 === 2 && col !== GRID_SIZE - 1) cell.classList.add("edge-right");
      if (row % 3 === 2 && row !== GRID_SIZE - 1) cell.classList.add("edge-bottom");
      if (row === 4 && col === 4) cell.classList.add("main-goal");
      else if (row % 3 === 1 && col % 3 === 1) cell.classList.add("sub-goal");

      const hueClass = cellBlockHueClass(row, col);
      if (hueClass) cell.classList.add(hueClass);

      const content = activeCells.get(`${row}-${col}`) || "";
      if (content.trim()) cell.classList.add("filled");

      cell.dataset.row = String(row);
      cell.dataset.col = String(col);

      const textarea = document.createElement("textarea");
      textarea.maxLength = 200;
      textarea.value = content;
      textarea.addEventListener("input", () => autoResizeTextarea(textarea));
      textarea.addEventListener("blur", () => commitCell(cell, row, col));
      // 한글 등 조합형 입력(IME) 중에는 Enter 가 "마지막 글자를 조합 확정"하는 용도로도 쓰인다.
      // isComposing 체크 없이 Enter 를 가로채 바로 blur() 해버리면, 조합이 채 끝나기 전에 값을 읽고
      // 커밋해버려 마지막 글자(음절)가 중복 입력된 것처럼 보이는 문제가 생긴다. 사파리는 keydown 에서
      // isComposing 이 정확하지 않을 수 있어 관례적으로 keyCode === 229 도 함께 확인한다
      textarea.addEventListener("keydown", (e) => {
        if (e.key === "Enter" && !e.shiftKey && !e.isComposing && e.keyCode !== 229) {
          e.preventDefault();
          textarea.blur();
        }
      });
      // textarea 를 내용 높이만큼만 차지하도록 auto-resize 하면서, 내용이 짧거나 없는 칸은 textarea 가
      // 칸 전체를 채우지 않게 됐다 - 그 주변 여백(칸의 flex 패딩 영역)을 클릭하면 textarea 가 아니라
      // 이 div 자체가 클릭돼 포커스가 안 잡히는 문제가 생긴다(화살표가 겹친 자리도 pointer-events:none
      // 이라 결국 이 div 로 클릭이 전달되므로 동일하게 걸린다). 칸 어디를 클릭해도 textarea 로 포커스가
      // 가도록 칸 자체에도 핸들러를 둔다
      cell.addEventListener("mousedown", (e) => {
        if (e.target === textarea) return;
        e.preventDefault();
        textarea.focus();
      });
      cell.appendChild(textarea);
      gridEl.appendChild(cell);
      // scrollHeight 는 실제 렌더링된(그리드에 붙은) 상태라야 폭 기준 줄바꿈이 정확히 반영된다
      autoResizeTextarea(textarea);
    }
  }

  renderCenterArrows();
}

// 중앙 블록(3x3)에서 8개 바깥 블록을 향해 뻗어나가는 화살표. 각 화살표는 중앙 블록과 그 블록 사이의
// 경계선에 걸치도록 grid-row/grid-column 을 셀 좌표(0-indexed) 그대로 두 트랙에 걸쳐 배치한다
// (0-indexed 트랙 i 는 CSS grid line (i+1)~(i+2)). 81개 칸을 다 그린 "뒤에" 추가해야 같은 grid 안에서
// 자연스럽게 칸들 위로 그려지고(z-index 도 보험으로 명시), pointer-events: none 이라 클릭은 그대로
// 밑에 있는 textarea 로 전달된다 - 화살표가 칸 편집을 가로막지 않는다
const CENTER_ARROWS = [
  { row: [2, 4], col: [4, 5], glyph: "↑" },
  { row: [5, 7], col: [4, 5], glyph: "↓" },
  { row: [4, 5], col: [2, 4], glyph: "←" },
  { row: [4, 5], col: [5, 7], glyph: "→" },
  { row: [2, 4], col: [2, 4], glyph: "↖" },
  { row: [2, 4], col: [5, 7], glyph: "↗" },
  { row: [5, 7], col: [2, 4], glyph: "↙" },
  { row: [5, 7], col: [5, 7], glyph: "↘" },
];

function renderCenterArrows() {
  for (const { row, col, glyph } of CENTER_ARROWS) {
    const arrow = document.createElement("div");
    arrow.className = "mandalart-arrow";
    arrow.style.gridRow = `${row[0] + 1} / ${row[1] + 1}`;
    arrow.style.gridColumn = `${col[0] + 1} / ${col[1] + 1}`;
    arrow.textContent = glyph;
    gridEl.appendChild(arrow);
  }
}

// 내용 길이에 맞춰 textarea 높이를 다시 잰다 - 부모(.mandalart-cell)가 flex 로 세로 중앙 정렬을
// 하므로, textarea 자체 높이가 내용만큼만 차지해야 짧은 글이 칸 한가운데에 온다
function autoResizeTextarea(textarea) {
  textarea.style.height = "auto";
  textarea.style.height = `${textarea.scrollHeight}px`;
}

async function commitCell(cell, row, col) {
  const textarea = cell.querySelector("textarea");
  const content = textarea.value;
  const previous = activeCells.get(`${row}-${col}`) || "";
  activeCells.set(`${row}-${col}`, content);
  cell.classList.toggle("filled", content.trim().length > 0);

  if (content === previous) return;
  try {
    await API.put(`/api/mandalart/${activeBoardId}/cells/${row}/${col}`, { content });
  } catch (err) {
    activeCells.set(`${row}-${col}`, previous);
    textarea.value = previous;
    autoResizeTextarea(textarea);
    cell.classList.toggle("filled", previous.trim().length > 0);
    showToast(`셀 저장에 실패했습니다. ${err.message}`);
  }
}

document.getElementById("add-board-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const input = document.getElementById("new-board-title");
  const title = input.value.trim();
  if (!title) return;

  try {
    const created = await API.post("/api/mandalart", { title });
    input.value = "";
    await loadBoards();
    await loadBoard(created.id);
  } catch (err) {
    showToast(`만다라트 생성에 실패했습니다. ${err.message}`);
  }
});

deleteBoardBtn.addEventListener("click", async () => {
  if (!activeBoardId) return;
  if (!confirm("이 만다라트를 삭제하시겠습니까?")) return;

  try {
    await API.del(`/api/mandalart/${activeBoardId}`);
    activeBoardId = null;
    activeCells = null;
    boardTitleEl.textContent = "만다라트를 선택하거나 새로 만들어주세요";
    deleteBoardBtn.style.display = "none";
    gridEl.innerHTML = "";
    await loadBoards();
  } catch (err) {
    showToast(`만다라트 삭제에 실패했습니다. ${err.message}`);
  }
});

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
  await loadBoards();
})();
