const form = document.getElementById("login-form");
const alertBox = document.getElementById("alert");

function showAlert(message) {
  alertBox.textContent = message;
  alertBox.classList.add("show");
}

form.addEventListener("submit", async (e) => {
  e.preventDefault();
  alertBox.classList.remove("show");

  const email = document.getElementById("email").value.trim();
  const password = document.getElementById("password").value;

  try {
    const data = await API.post("/api/auth/login", { email, password });
    API.setToken(data.accessToken);
    API.setCurrentUser({ email, id: API.lookupUserId(email) });
    window.location.href = "/dashboard";
  } catch (err) {
    showAlert(err.message || "로그인에 실패했습니다.");
  }
});

// GIS(Google Identity Services) 스크립트의 onload 콜백으로 호출된다 (index.html 참고).
// 클라이언트 ID는 비밀값이 아니지만 하드코딩 대신 서버에서 받아와 배포 환경별로 값이 달라도 코드를 안 건드리게 한다.
async function initGoogleSignIn() {
  try {
    const { clientId } = await API.get("/api/auth/google/client-id");
    google.accounts.id.initialize({
      client_id: clientId,
      callback: handleGoogleCredential,
    });
    google.accounts.id.renderButton(document.getElementById("google-signin-button"), {
      theme: "outline",
      size: "large",
      width: 320,
      text: "continue_with",
    });
  } catch (err) {
    console.error("구글 로그인 초기화 실패", err);
  }
}

async function handleGoogleCredential(response) {
  alertBox.classList.remove("show");
  try {
    const data = await API.post("/api/auth/google", { idToken: response.credential });
    const { email } = decodeJwtPayload(response.credential);
    API.setToken(data.accessToken);
    API.setCurrentUser({ email, id: API.lookupUserId(email) });
    window.location.href = "/dashboard";
  } catch (err) {
    showAlert(err.message || "구글 로그인에 실패했습니다.");
  }
}

// 서명 검증이 아니라 표시용 이메일만 뽑아내는 용도라 payload 를 디코딩만 한다 (검증은 이미 서버가 함)
function decodeJwtPayload(token) {
  const base64 = token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
  const json = decodeURIComponent(
    atob(base64)
      .split("")
      .map((c) => "%" + c.charCodeAt(0).toString(16).padStart(2, "0"))
      .join("")
  );
  return JSON.parse(json);
}
