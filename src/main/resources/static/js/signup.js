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
