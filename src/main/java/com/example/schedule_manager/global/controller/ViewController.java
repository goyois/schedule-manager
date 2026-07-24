package com.example.schedule_manager.global.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// 정적 뷰(static/*.html) 접속을 위한 컨트롤러
// 확장자 없는 깔끔한 경로로 접근할 수 있도록 각 정적 페이지로 forward 한다
@Controller
public class ViewController {

    @GetMapping("/")
    public String index() {
        return "forward:/index.html";
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
}
