// 공용 API 헬퍼: 토큰 저장/조회, fetch 래퍼(만료 시 refresh token 으로 자동 재발급), 로그인 유저 정보 보관
const API = (() => {
  const TOKEN_KEY = "sm_access_token";
  const REFRESH_TOKEN_KEY = "sm_refresh_token";
  const USER_KEY = "sm_current_user";
  const KNOWN_USERS_KEY = "sm_known_users"; // email -> id (이 브라우저에서 가입/조회했던 사용자)

  // 여러 요청이 동시에 만료를 맞아도 재발급 호출은 한 번만 나가도록 진행 중인 refresh Promise 를 공유한다
  let refreshInFlight = null;

  function getToken() {
    return localStorage.getItem(TOKEN_KEY);
  }

  function getRefreshToken() {
    return localStorage.getItem(REFRESH_TOKEN_KEY);
  }

  function setTokens(accessToken, refreshToken) {
    localStorage.setItem(TOKEN_KEY, accessToken);
    if (refreshToken) localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken);
  }

  function clearSession() {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(REFRESH_TOKEN_KEY);
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

  // refresh token 으로 access/refresh token 을 재발급 받는다. 이 호출 자체는 만료된 access token 을
  // 실어 보내지 않도록 request()를 거치지 않고 직접 fetch 한다(재발급 요청이 다시 재발급을 트리거하는 순환 방지)
  async function refreshAccessToken() {
    if (refreshInFlight) return refreshInFlight;

    const refreshToken = getRefreshToken();
    if (!refreshToken) return Promise.reject(new Error("리프레시 토큰이 없습니다."));

    refreshInFlight = (async () => {
      const res = await fetch("/api/auth/refresh", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refreshToken }),
      });

      let body = null;
      try {
        body = await res.json();
      } catch (e) {
        body = null;
      }

      if (!res.ok) {
        throw new Error((body && (body.message || body.error)) || "세션이 만료되었습니다.");
      }

      const data = body && Object.prototype.hasOwnProperty.call(body, "data") ? body.data : body;
      setTokens(data.accessToken, data.refreshToken);
      return data.accessToken;
    })();

    try {
      return await refreshInFlight;
    } finally {
      refreshInFlight = null;
    }
  }

  async function request(path, options = {}, isRetry = false) {
    const headers = Object.assign(
      { "Content-Type": "application/json" },
      options.headers || {}
    );
    const token = getToken();
    if (token) headers["Authorization"] = `Bearer ${token}`;

    const res = await fetch(path, Object.assign({}, options, { headers }));

    // access token 이 만료/무효화된 요청(401/403)이면 refresh token 으로 재발급 후 한 번만 재시도한다.
    // 이미 재시도한 요청이거나 애초에 로그인 상태가 아니었던 요청(token 없음)은 그대로 실패 처리한다
    if ((res.status === 401 || res.status === 403) && !isRetry && token) {
      try {
        await refreshAccessToken();
      } catch (e) {
        clearSession();
        window.location.href = "/login";
        throw e;
      }
      return request(path, options, true);
    }

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
    getRefreshToken,
    setTokens,
    clearSession,
    getCurrentUser,
    setCurrentUser,
    rememberUserId,
    lookupUserId,
  };
})();
