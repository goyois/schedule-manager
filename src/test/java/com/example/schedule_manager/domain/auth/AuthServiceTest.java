package com.example.schedule_manager.domain.auth;

import com.example.schedule_manager.domain.auth.dto.GoogleLoginRequestDto;
import com.example.schedule_manager.domain.auth.dto.LoginResponseDto;
import com.example.schedule_manager.domain.auth.service.AuthService;
import com.example.schedule_manager.domain.user.entity.AuthProvider;
import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.entity.UserType;
import com.example.schedule_manager.domain.user.repository.UserRepository;
import com.example.schedule_manager.global.security.util.JwtUtil;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.json.webtoken.JsonWebSignature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.security.GeneralSecurityException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private GoogleIdTokenVerifier googleIdTokenVerifier;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthService authService;

    private GoogleIdToken googleIdToken(String email, boolean emailVerified, String name) {
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload()
                .setEmail(email)
                .setEmailVerified(emailVerified);
        payload.set("name", name);
        return new GoogleIdToken(new JsonWebSignature.Header(), payload, new byte[0], new byte[0]);
    }

    @Test
    @DisplayName("구글 로그인 성공 - 이미 가입된 이메일이면 기존 계정으로 로그인하고 새 계정을 만들지 않는다")
    void loginWithGoogle_existingUser_reusesAccount() throws GeneralSecurityException, java.io.IOException {
        GoogleLoginRequestDto request = new GoogleLoginRequestDto("valid-id-token");
        User existing = User.builder()
                .id(1L)
                .username("tester")
                .email("tester@example.com")
                .userType(UserType.USER)
                .authProvider(AuthProvider.LOCAL)
                .build();

        when(googleIdTokenVerifier.verify("valid-id-token"))
                .thenReturn(googleIdToken("tester@example.com", true, "Tester"));
        when(userRepository.findByEmail("tester@example.com")).thenReturn(Optional.of(existing));
        when(jwtUtil.generateToken("tester@example.com")).thenReturn("app-jwt");

        LoginResponseDto response = authService.loginWithGoogle(request);

        assertThat(response.accessToken()).isEqualTo("app-jwt");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("구글 로그인 성공 - 가입 이력이 없으면 구글 프로필로 새 계정을 만든다")
    void loginWithGoogle_newUser_createsGoogleAccount() throws GeneralSecurityException, java.io.IOException {
        GoogleLoginRequestDto request = new GoogleLoginRequestDto("valid-id-token");

        when(googleIdTokenVerifier.verify("valid-id-token"))
                .thenReturn(googleIdToken("newbie@example.com", true, "Newbie"));
        when(userRepository.findByEmail("newbie@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-random-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            return User.builder()
                    .id(2L)
                    .username(saved.getUsername())
                    .email(saved.getEmail())
                    .password(saved.getPassword())
                    .userType(saved.getUserType())
                    .authProvider(saved.getAuthProvider())
                    .build();
        });
        when(jwtUtil.generateToken("newbie@example.com")).thenReturn("app-jwt");

        LoginResponseDto response = authService.loginWithGoogle(request);

        assertThat(response.accessToken()).isEqualTo("app-jwt");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User created = captor.getValue();
        assertThat(created.getEmail()).isEqualTo("newbie@example.com");
        assertThat(created.getUsername()).isEqualTo("Newbie");
        assertThat(created.getUserType()).isEqualTo(UserType.USER);
        assertThat(created.getAuthProvider()).isEqualTo(AuthProvider.GOOGLE);
        assertThat(created.getPassword()).isEqualTo("encoded-random-password");
    }

    @Test
    @DisplayName("구글 로그인 실패 - 이메일이 인증되지 않은 계정이면 예외가 발생한다")
    void loginWithGoogle_emailNotVerified_throws() throws GeneralSecurityException, java.io.IOException {
        GoogleLoginRequestDto request = new GoogleLoginRequestDto("valid-id-token");
        when(googleIdTokenVerifier.verify("valid-id-token"))
                .thenReturn(googleIdToken("unverified@example.com", false, "Unverified"));

        assertThatThrownBy(() -> authService.loginWithGoogle(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("이메일이 인증되지 않은 구글 계정입니다.");

        verify(userRepository, never()).findByEmail(anyString());
    }

    @Test
    @DisplayName("구글 로그인 실패 - 서명/발급자 검증에 실패해 verify()가 null을 반환하면 예외가 발생한다")
    void loginWithGoogle_verifyReturnsNull_throws() throws GeneralSecurityException, java.io.IOException {
        GoogleLoginRequestDto request = new GoogleLoginRequestDto("forged-id-token");
        when(googleIdTokenVerifier.verify("forged-id-token")).thenReturn(null);

        assertThatThrownBy(() -> authService.loginWithGoogle(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("유효하지 않은 구글 로그인 토큰입니다.");
    }

    @Test
    @DisplayName("구글 로그인 실패 - 토큰 형식이 아예 잘못돼 검증 과정에서 예외가 나면 안전하게 감싸서 던진다")
    void loginWithGoogle_verifierThrows_wrapsAsIllegalArgumentException() throws GeneralSecurityException, java.io.IOException {
        GoogleLoginRequestDto request = new GoogleLoginRequestDto("garbage");
        when(googleIdTokenVerifier.verify("garbage")).thenThrow(new IllegalArgumentException("malformed"));

        assertThatThrownBy(() -> authService.loginWithGoogle(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("유효하지 않은 구글 로그인 토큰입니다.");
    }
}
