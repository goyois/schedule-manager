const form = document.getElementById("signup-form");
const alertBox = document.getElementById("alert");

function showAlert(message) {
  alertBox.textContent = message;
  alertBox.classList.add("show");
}

form.addEventListener("submit", async (e) => {
  e.preventDefault();
  alertBox.classList.remove("show");

  const username = document.getElementById("username").value.trim();
  const email = document.getElementById("email").value.trim();
  const password = document.getElementById("password").value;

  try {
    // userType은 보내지 않는다 - UserService.createUser가 비어있으면 UserType.USER로 채운다.
    // 관리자 계정은 가입 화면에서 스스로 고를 수 있는 게 아니라 DB에서 직접 승격시켜야 한다
    const user = await API.post("/api/users", { username, email, password });
    API.rememberUserId(email, user.id);
    alertBox.classList.remove("show");
    window.location.href = "/login";
  } catch (err) {
    showAlert(err.message || "회원가입에 실패했습니다.");
  }
});

// 구글로 회원가입 - login.js의 구글 로그인과 완전히 같은 흐름/엔드포인트(POST /api/auth/google)를
// 쓴다. AuthService.loginWithGoogle이 이메일 기준으로 없으면 만들고(AuthProvider.GOOGLE) 있으면
// 그대로 로그인시키는 get-or-create라서, "구글로 로그인"과 "구글로 회원가입"은 프론트에서 굳이
// 나눌 필요 없이 같은 버튼/같은 API 호출로 처리된다
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
      text: "signup_with",
    });
  } catch (err) {
    console.error("구글 회원가입 초기화 실패", err);
  }
}

async function handleGoogleCredential(response) {
  alertBox.classList.remove("show");
  try {
    const data = await API.post("/api/auth/google", { idToken: response.credential });
    const { email } = decodeJwtPayload(response.credential);
    API.setTokens(data.accessToken, data.refreshToken);
    API.setCurrentUser({ email, id: API.lookupUserId(email) });
    window.location.href = "/dashboard";
  } catch (err) {
    showAlert(err.message || "구글 회원가입에 실패했습니다.");
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
