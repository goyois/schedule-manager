package com.example.schedule_manager.global.config;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;

// 프론트에서 Google Identity Services 로 발급받은 ID 토큰을 서버가 구글 공개키로 검증할 때 쓰는 빈
// (GoogleAuthService 참고: 리다이렉트 방식이 아니라 프론트가 받은 ID 토큰을 그대로 검증하는 구조)
@Configuration
public class GoogleOAuthConfig {

    @Bean
    public GoogleIdTokenVerifier googleIdTokenVerifier(@Value("${google.oauth.client-id}") String clientId) {
        return new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(clientId))
                .build();
    }
}
