package com.example.schedule_manager.global.security.service;

import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

// Spring Security 가 인증 시 호출하는 유저 조회 서비스
// AuthenticationManager 가 비밀번호 검증 전에 이 클래스를 통해 DB 에서 유저를 불러온다
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    // Spring Security 가 로그인 요청을 처리할 때 자동으로 호출된다
    // email 로 DB 에서 유저를 조회하고, Security 가 이해할 수 있는 UserDetails 형태로 반환한다
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("유저를 찾을 수 없습니다."));

        // Security 의 User 객체에 이메일, 암호화된 비밀번호, 권한(ROLE_USER / ROLE_ADMIN) 을 담아 반환
        // → AuthenticationManager 가 이 정보로 입력된 비밀번호와 비교 검증한다
        return new org.springframework.security.core.userdetails.User(
                user.getEmail(),
                user.getPassword(),
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getUserType().name()))
        );
    }
}
