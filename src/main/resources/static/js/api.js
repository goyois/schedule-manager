// 상태 코드를 들고 다니는 에러 — 호출부가 err.message 뿐 아니라 err.status 로도 분기해
// UX에 맞는 처리(예: 404면 목록 새로고침, 403이면 권한 안내)를 할 수 있게 한다
class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

// 백엔드가 message 를 못 내려준 경우(네트워크 실패 등)를 대비한 상태코드별 기본 문구.
// 상태 코드나 "요청 실패" 같은 기술적인 표현 대신, 사용자가 다음에 뭘 해야 할지 알 수 있는 문장으로 통일한다
function defaultMessageForStatus(status) {
  switch (status) {
    case 400:
      return "입력한 내용을 다시 확인해주세요.";
    case 401:
      return "로그인이 필요합니다.";
    case 403:
      return "이 작업을 수행할 권한이 없습니다.";
    case 404:
      return "찾으시는 항목이 이미 삭제되었거나 존재하지 않습니다.";
    case 409:
      return "이미 사용 중이거나 존재하는 값입니다.";
    default:
      return "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.";
  }
}

// 공용 API 헬퍼: 토큰 저장/조회, fetch 래퍼(만료 시 refresh token 으로 자동 재발급), 로그인 유저 정보 보관
const API = (() => {
  const TOKEN_KEY = "sm_access_token";
  const REFRESH_TOKEN_KEY = "sm_refresh_token";
  const USER_KEY = "sm_current_user";
  const KNOWN_USERS_KEY = "sm_known_users"; // email -> id (이 브라우저에서 가입/조회했던 사용자)

  // 여러 요청이 동시에 만료를 맞아도 재발급 호출은 한 번만 나가도록 진행 중인 refresh Promise 를 공유한다
  let refreshInFlight = null;

  // ---------- 데모 모드 ----------
  // 토큰 없이 대시보드에 들어온 방문자에게 실제 서비스처럼 보이는 화면을 보여주기 위한 모드.
  // localStorage가 아니라 이 모듈 스코프 변수로만 들고 있다 - 로그인/로그아웃은 항상 페이지 전체
  // 이동(window.location.href)을 동반하므로, 스크립트가 다시 로드되면 자연히 꺼진 상태로 리셋된다.
  // dashboard.js의 requireAuth()가 토큰이 없을 때 리다이렉트 대신 이 모드를 켠다
  let demoMode = false;
  let demoAuthRequiredHandler = null;

  function isDemoMode() {
    return demoMode;
  }

  function enableDemoMode() {
    demoMode = true;
  }

  // 데모 모드에서 저장/수정/삭제 등 권한이 필요한 동작을 시도했을 때 호출할 콜백을 등록한다.
  // api.js는 DOM을 모르므로(기존에도 모달/토스트를 직접 그리지 않는다), "회원가입/로그인 유도" UI를
  // 그리는 실제 동작은 dashboard.js가 등록한 콜백에 위임한다
  function setDemoAuthRequiredHandler(handler) {
    demoAuthRequiredHandler = handler;
  }

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
    const method = (options.method || "GET").toUpperCase();

    // 데모 모드: 실제 백엔드를 절대 건드리지 않는다(로그인하지 않았으므로 애초에 뭘 보내도 401일
    // 뿐더러, 저장/수정/삭제 시도가 실수로라도 서버에 닿아선 안 된다) - fetch() 호출 자체를 하지 않고
    // 여기서 바로 갈라진다. GET은 demo-data.js가 아는 경로면 미리 만들어둔 가짜 데이터를 그대로
    // 돌려주고(모르는 경로면 아래로 흘려보내 평소처럼 401을 받게 둔다 - 이미 화면 곳곳의 catch가
    // 조용히 넘어가도록 되어 있다), GET이 아닌 모든 요청은 무조건 "로그인/회원가입 유도" 콜백을 부르고
    // 실패로 던진다
    if (demoMode) {
      if (method !== "GET") {
        if (demoAuthRequiredHandler) demoAuthRequiredHandler();
        throw new ApiError(401, "로그인하거나 회원가입하면 이용할 수 있어요.");
      }
      if (window.DemoFixtures) {
        const fixture = window.DemoFixtures.resolve(path);
        if (fixture !== undefined) return fixture;
      }
    }

    const headers = Object.assign(
      { "Content-Type": "application/json" },
      options.headers || {}
    );
    const token = getToken();
    if (token) headers["Authorization"] = `Bearer ${token}`;

    let res;
    try {
      res = await fetch(path, Object.assign({}, options, { headers }));
    } catch (networkErr) {
      throw new ApiError(0, "네트워크 연결을 확인해주세요.");
    }

    // access token 이 만료/무효화된 요청(401)만 refresh token 으로 재발급 후 한 번 재시도한다.
    // 403(권한 없음)은 토큰 자체는 유효한 상태라 재발급해도 소용없으므로 재시도 없이 바로 실패 처리한다
    if (res.status === 401 && !isRetry && token) {
      try {
        await refreshAccessToken();
      } catch (e) {
        clearSession();
        window.location.href = "/login";
        throw new ApiError(401, "세션이 만료되었습니다. 다시 로그인해주세요.");
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
      const message = (body && (body.message || body.error)) || defaultMessageForStatus(res.status);
      throw new ApiError(res.status, message);
    }

    // 백엔드 공통 포맷: { code, message, data }
    return body && Object.prototype.hasOwnProperty.call(body, "data") ? body.data : body;
  }

  return {
    get: (path) => request(path, { method: "GET" }),
    post: (path, body) => request(path, { method: "POST", body: JSON.stringify(body) }),
    put: (path, body) => request(path, { method: "PUT", body: JSON.stringify(body) }),
    patch: (path, body) => request(path, { method: "PATCH", body: JSON.stringify(body) }),
    del: (path) => request(path, { method: "DELETE" }),
    getToken,
    getRefreshToken,
    setTokens,
    clearSession,
    refreshAccessToken,
    getCurrentUser,
    setCurrentUser,
    rememberUserId,
    lookupUserId,
    isDemoMode,
    enableDemoMode,
    setDemoAuthRequiredHandler,
  };
})();
