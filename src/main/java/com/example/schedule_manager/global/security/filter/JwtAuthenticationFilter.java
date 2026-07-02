package com.example.schedule_manager.global.security.filter;

import com.example.schedule_manager.global.security.service.CustomUserDetailsService;
import com.example.schedule_manager.global.security.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// 모든 HTTP 요청마다 한 번씩 실행되는 JWT 인증 필터
// 컨트롤러에 도달하기 전에 토큰을 검사해서 유저를 SecurityContext 에 등록한다
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final CustomUserDetailsService customUserDetailsService;
    private final RedisTemplate<String, String> redisTemplate;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // 1. 요청 헤더에서 토큰을 추출한다
        String token = resolveToken(request);

        // 2. 토큰이 존재하고, 유효하며, 블랙리스트(로그아웃된 토큰)가 아닐 때만 인증 처리
        if (token != null && jwtUtil.isTokenValid(token) && !isBlacklisted(token)) {

            // 3. 토큰에서 이메일을 꺼내 DB 에서 유저 정보를 조회한다
            String email = jwtUtil.extractEmail(token);
            UserDetails userDetails = customUserDetailsService.loadUserByUsername(email);

            // 4. Security 인증 객체를 만들어 SecurityContext 에 저장한다
            //    → 이후 컨트롤러에서 @AuthenticationPrincipal 등으로 현재 유저를 꺼낼 수 있다
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        // 5. 다음 필터(또는 컨트롤러)로 요청을 넘긴다
        filterChain.doFilter(request, response);
    }

    // Authorization 헤더에서 "Bearer " 접두사를 제거하고 순수 토큰 문자열만 반환
    private String resolveToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }

    // Redis 에 "blacklist:{token}" 키가 있으면 로그아웃된 토큰 → 인증 거부
    private boolean isBlacklisted(String token) {
        return Boolean.TRUE.equals(redisTemplate.hasKey("blacklist:" + token));
    }
}
