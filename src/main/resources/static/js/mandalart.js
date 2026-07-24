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
      textarea.addEventListener("keydown", (e) => {
        if (e.key === "Enter" && !e.shiftKey) {
          e.preventDefault();
          textarea.blur();
        }
      });
      cell.appendChild(textarea);
      gridEl.appendChild(cell);
      // scrollHeight 는 실제 렌더링된(그리드에 붙은) 상태라야 폭 기준 줄바꿈이 정확히 반영된다
      autoResizeTextarea(textarea);
    }
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
