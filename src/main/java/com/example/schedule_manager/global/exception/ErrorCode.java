package com.example.schedule_manager.global.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    UNVERIFIED_GOOGLE_EMAIL(HttpStatus.BAD_REQUEST, "이메일이 인증되지 않은 구글 계정입니다."),

    MISSING_TOKEN(HttpStatus.UNAUTHORIZED, "토큰이 없습니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 리프레시 토큰입니다."),
    EXPIRED_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "만료되었거나 폐기된 리프레시 토큰입니다."),
    INVALID_GOOGLE_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 구글 로그인 토큰입니다."),

    SCHEDULE_ACCESS_DENIED(HttpStatus.FORBIDDEN, "본인의 일정만 조회할 수 있습니다."),
    DEFAULT_CATEGORY_DELETE_FORBIDDEN(HttpStatus.FORBIDDEN, "기본 설정 카테고리는 삭제 불가합니다."),
    DEFAULT_CATEGORY_UPDATE_FORBIDDEN(HttpStatus.FORBIDDEN, "기본 설정 카테고리는 수정 불가합니다."),

    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 유저입니다."),
    CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 카테고리입니다."),
    SCHEDULE_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 일정입니다."),

    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    DUPLICATE_CATEGORY(HttpStatus.CONFLICT, "이미 존재하는 카테고리입니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }
}
