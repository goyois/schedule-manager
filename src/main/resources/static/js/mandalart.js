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

      cell.dataset.row = String(row);
      cell.dataset.col = String(col);
      cell.textContent = activeCells.get(`${row}-${col}`) || "";
      cell.addEventListener("click", () => beginEditCell(cell));
      gridEl.appendChild(cell);
    }
  }
}

function beginEditCell(cell) {
  if (cell.querySelector("textarea")) return;

  const row = Number(cell.dataset.row);
  const col = Number(cell.dataset.col);
  const currentContent = activeCells.get(`${row}-${col}`) || "";

  cell.textContent = "";
  const textarea = document.createElement("textarea");
  textarea.value = currentContent;
  textarea.maxLength = 200;
  cell.appendChild(textarea);
  textarea.focus();

  textarea.addEventListener("blur", () => commitEditCell(cell, row, col, textarea.value));
  textarea.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      textarea.blur();
    }
  });
}

async function commitEditCell(cell, row, col, content) {
  const previous = activeCells.get(`${row}-${col}`) || "";
  activeCells.set(`${row}-${col}`, content);
  cell.textContent = content;

  if (content === previous) return;
  try {
    await API.put(`/api/mandalart/${activeBoardId}/cells/${row}/${col}`, { content });
  } catch (err) {
    activeCells.set(`${row}-${col}`, previous);
    cell.textContent = previous;
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
