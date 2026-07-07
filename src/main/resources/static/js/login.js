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
