package com.example.schedule_manager.domain.user;

import com.example.schedule_manager.domain.user.dto.UserRequestDto;
import com.example.schedule_manager.domain.user.dto.UserResponseDto;
import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.entity.UserType;
import com.example.schedule_manager.domain.user.repository.UserRepository;
import com.example.schedule_manager.domain.user.service.UserService;
import com.example.schedule_manager.global.exception.BusinessException;
import com.example.schedule_manager.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    @Test
    @DisplayName("유저 생성 성공 - 비밀번호는 암호화되어 저장된다")
    void createUser_success() {
        UserRequestDto request = new UserRequestDto("tester", "raw-password", "tester@example.com", null);

        when(userRepository.existsByEmail(request.email())).thenReturn(false);
        when(passwordEncoder.encode(request.password())).thenReturn("encoded-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            return User.builder()
                    .id(1L)
                    .username(saved.getUsername())
                    .password(saved.getPassword())
                    .email(saved.getEmail())
                    .userType(saved.getUserType())
                    .build();
        });

        UserResponseDto response = userService.createUser(request);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.username()).isEqualTo("tester");
        assertThat(response.email()).isEqualTo("tester@example.com");
        assertThat(response.userType()).isEqualTo(UserType.USER);
        verify(passwordEncoder).encode("raw-password");
    }

    @Test
    @DisplayName("유저 생성 실패 - 이미 존재하는 이메일이면 예외가 발생한다")
    void createUser_duplicateEmail_throws() {
        UserRequestDto request = new UserRequestDto("tester", "raw-password", "tester@example.com", null);
        when(userRepository.existsByEmail(request.email())).thenReturn(true);

        assertThatThrownBy(() -> userService.createUser(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("이미 사용 중인 이메일입니다.")
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_EMAIL);
    }
}
