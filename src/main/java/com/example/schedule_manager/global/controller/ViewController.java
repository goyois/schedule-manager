package com.example.schedule_manager.global.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// 정적 뷰(static/*.html) 접속을 위한 컨트롤러
// 확장자 없는 깔끔한 경로로 접근할 수 있도록 각 정적 페이지로 forward 한다
@Controller
public class ViewController {

    // 루트는 더 이상 로그인 화면이 아니다 - dashboard.html이 토큰 유무에 따라 실제 대시보드/데모
    // 화면을 알아서 갈라 그리므로(js/dashboard.js requireAuth 참고), 로그인 여부와 무관하게 항상
    // 대시보드 셸로 보낸다. 로그인 화면 자체는 여전히 /login 에서 볼 수 있다
    @GetMapping("/")
    public String index() {
        return "forward:/dashboard.html";
    }

    @GetMapping("/login")
    public String login() {
        return "forward:/index.html";
    }

    @GetMapping("/signup")
    public String signup() {
        return "forward:/signup.html";
    }

    @GetMapping("/dashboard")
    public String dashboard() {
        return "forward:/dashboard.html";
    }

    @GetMapping("/mandalart")
    public String mandalart() {
        return "forward:/mandalart.html";
    }

    @GetMapping("/settings")
    public String settings() {
        return "forward:/settings.html";
    }

    @GetMapping("/report")
    public String report() {
        return "forward:/report.html";
    }
}
