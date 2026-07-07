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
  const userType = document.getElementById("userType").value;

  try {
    const user = await API.post("/api/users", { username, email, password, userType });
    API.rememberUserId(email, user.id);
    alertBox.classList.remove("show");
    window.location.href = "/login";
  } catch (err) {
    showAlert(err.message || "회원가입에 실패했습니다.");
  }
});
