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

    public UserResponseDto updateUser(Long id, UserRequestDto request) {
        User user = userRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        user.update(request.username(), request.email());
        return UserResponseDto.from(user);
    }

    public void deleteUser(Long id) {
        User user = userRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        userRepository.delete(user);
    }
}
