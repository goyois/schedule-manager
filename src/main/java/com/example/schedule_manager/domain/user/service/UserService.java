package com.example.schedule_manager.domain.user.service;

import com.example.schedule_manager.domain.user.dto.UserRequestDto;
import com.example.schedule_manager.domain.user.dto.UserResponseDto;
import com.example.schedule_manager.domain.user.entity.User;
import com.example.schedule_manager.domain.user.entity.UserType;
import com.example.schedule_manager.domain.user.repository.UserRepository;
import com.example.schedule_manager.global.exception.BusinessException;
import com.example.schedule_manager.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserResponseDto createUser(UserRequestDto request) {
        if (userRepository.existsByEmail(request.email())) throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        User user = User.builder()
                .username(request.username())
                .password(passwordEncoder.encode(request.password()))
                .email(request.email())
                .userType(request.userType() != null ? request.userType() : UserType.USER)
                .build();
        return UserResponseDto.from(userRepository.save(user));
    }

    @Transactional(readOnly = true)
    public UserResponseDto getUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        return UserResponseDto.from(user);
    }

    // 프론트가 로그인한 본인의 id 를 알아낼 방법이 마땅치 않았다(JWT subject 는 email 뿐, 로그인 응답도
    // 토큰만 내려줌) - 지금까지는 회원가입 시점에 브라우저 localStorage 에 email→id 매핑을 캐싱해두는
    // 방식(api.js rememberUserId/lookupUserId)에 의존했는데, 다른 브라우저·캐시 삭제 시엔 깨진다.
    // 일정 생성 폼에서 "작성자 User ID" 수동 입력칸을 없애면서(더 이상 깨졌을 때 수동으로 채워 넣을
    // 방법이 없어지므로) 이 엔드포인트로 항상 신뢰할 수 있게 대체한다
    @Transactional(readOnly = true)
    public UserResponseDto getCurrentUser(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        return UserResponseDto.from(user);
    }

    public UserResponseDto updateUser(Long id, UserRequestDto request) {
        User user = userRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        user.update(request.username(), request.email(), passwordEncoder.encode(request.password()));
        return UserResponseDto.from(user);
    }

    public void deleteUser(Long id) {
        User user = userRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        userRepository.delete(user);
    }
}
