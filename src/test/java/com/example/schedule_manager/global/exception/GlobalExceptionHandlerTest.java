package com.example.schedule_manager.global.exception;

import com.example.schedule_manager.global.response.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Mock
    private MethodParameter methodParameter;

    @Mock
    private BindingResult bindingResult;

    @Test
    @DisplayName("BusinessException은 ErrorCode에 정의된 상태코드와 메시지로 응답한다")
    void handleBusinessException_mapsErrorCodeToResponse() {
        BusinessException e = new BusinessException(ErrorCode.SCHEDULE_NOT_FOUND);

        ResponseEntity<ApiResponse<Void>> response = handler.handleBusinessException(e);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getCode()).isEqualTo(404);
        assertThat(response.getBody().getMessage()).isEqualTo("존재하지 않는 일정입니다.");
    }

    @Test
    @DisplayName("Bean Validation 실패 시 첫 번째 필드 오류 메시지를 담아 400으로 응답한다")
    void handleValidationException_returns400WithFirstFieldError() {
        FieldError fieldError = new FieldError("scheduleRequestDto", "title", "제목을 입력해주세요.");
        when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldError));
        MethodArgumentNotValidException e = new MethodArgumentNotValidException(methodParameter, bindingResult);

        ResponseEntity<ApiResponse<Void>> response = handler.handleValidationException(e);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getMessage()).isEqualTo("title: 제목을 입력해주세요.");
    }

    @Test
    @DisplayName("인증 실패(AuthenticationException)는 원인과 무관하게 401 + 고정 메시지로 응답한다")
    void handleAuthenticationException_returns401WithFixedMessage() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleAuthenticationException(new BadCredentialsException("bad credentials"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().getMessage()).isEqualTo("이메일 또는 비밀번호가 올바르지 않습니다.");
    }

    @Test
    @DisplayName("AccessDeniedException은 403으로 응답한다")
    void handleAccessDeniedException_returns403() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleAccessDeniedException(new AccessDeniedException("denied"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().getMessage()).isEqualTo("접근 권한이 없습니다.");
    }

    @Test
    @DisplayName("예기치 못한 예외는 내부 메시지를 노출하지 않고 500 + 고정 메시지로 응답한다")
    void handleException_returns500WithoutLeakingInternalMessage() {
        ResponseEntity<ApiResponse<Void>> response =
                handler.handleException(new RuntimeException("db connection refused"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getMessage()).isEqualTo("서버 오류가 발생했습니다.");
        assertThat(response.getBody().getMessage()).doesNotContain("db connection refused");
    }
}
