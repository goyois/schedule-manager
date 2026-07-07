// 공용 API 헬퍼: 토큰 저장/조회, fetch 래퍼, 로그인 유저 정보 보관
const API = (() => {
  const TOKEN_KEY = "sm_access_token";
  const USER_KEY = "sm_current_user";
  const KNOWN_USERS_KEY = "sm_known_users"; // email -> id (이 브라우저에서 가입/조회했던 사용자)

  function getToken() {
    return localStorage.getItem(TOKEN_KEY);
  }

  function setToken(token) {
    localStorage.setItem(TOKEN_KEY, token);
  }

  function clearSession() {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
  }

  function getCurrentUser() {
    const raw = localStorage.getItem(USER_KEY);
    return raw ? JSON.parse(raw) : null;
  }

  function setCurrentUser(user) {
    localStorage.setItem(USER_KEY, JSON.stringify(user));
  }

  function rememberUserId(email, id) {
    const map = JSON.parse(localStorage.getItem(KNOWN_USERS_KEY) || "{}");
    map[email] = id;
    localStorage.setItem(KNOWN_USERS_KEY, JSON.stringify(map));
  }

  function lookupUserId(email) {
    const map = JSON.parse(localStorage.getItem(KNOWN_USERS_KEY) || "{}");
    return map[email] ?? null;
  }

  async function request(path, options = {}) {
    const headers = Object.assign(
      { "Content-Type": "application/json" },
      options.headers || {}
    );
    const token = getToken();
    if (token) headers["Authorization"] = `Bearer ${token}`;

    const res = await fetch(path, Object.assign({}, options, { headers }));

    if (res.status === 204) return null;

    let body = null;
    try {
      body = await res.json();
    } catch (e) {
      body = null;
    }

    if (!res.ok) {
      const message = (body && (body.message || body.error)) || `요청 실패 (${res.status})`;
      throw new Error(message);
    }

    // 백엔드 공통 포맷: { code, message, data }
    return body && Object.prototype.hasOwnProperty.call(body, "data") ? body.data : body;
  }

  return {
    get: (path) => request(path, { method: "GET" }),
    post: (path, body) => request(path, { method: "POST", body: JSON.stringify(body) }),
    put: (path, body) => request(path, { method: "PUT", body: JSON.stringify(body) }),
    del: (path) => request(path, { method: "DELETE" }),
    getToken,
    setToken,
    clearSession,
    getCurrentUser,
    setCurrentUser,
    rememberUserId,
    lookupUserId,
  };
})();
