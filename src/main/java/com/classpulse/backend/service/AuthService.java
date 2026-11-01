package com.classpulse.backend.service;

import com.classpulse.backend.dto.AuthResponse;
import com.classpulse.backend.dto.LoginRequest;
import com.classpulse.backend.dto.RegisterRequest;
import com.classpulse.backend.dto.UserDto;
import com.classpulse.backend.exception.ConflictException;
import com.classpulse.backend.exception.UnauthorizedException;
import com.classpulse.backend.model.User;
import com.classpulse.backend.repository.UserRepository;
import com.classpulse.backend.security.JwtClaims;
import com.classpulse.backend.security.JwtTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final Clock clock;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String normalizedEmail = request.getEmail().trim().toLowerCase();

        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new ConflictException("An account with email " + normalizedEmail + " already exists");
        }

        Instant now = clock.instant();
        User user = User.builder()
                .id(UUID.randomUUID())
                .email(normalizedEmail)
                .passwordHash(passwordEncoder.encode(request.getPassword().trim()))
                .fullName(request.getFullName().trim())
                .role(request.getRole())
                .createdAt(now)
                .updatedAt(now)
                .build();

        User saved = userRepository.save(user);
        String token = jwtTokenService.generateToken(saved);

        return AuthResponse.builder()
                .token(token)
                .user(mapToDto(saved))
                .build();
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String normalizedEmail = request.getEmail().trim().toLowerCase();

        User user = userRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));

        if (!passwordEncoder.matches(request.getPassword().trim(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password");
        }

        String token = jwtTokenService.generateToken(user);

        return AuthResponse.builder()
                .token(token)
                .user(mapToDto(user))
                .build();
    }

    @Transactional(readOnly = true)
    public UserDto getCurrentUser(String authHeader) {
        User user = resolveUser(authHeader)
                .orElseThrow(() -> new UnauthorizedException("Valid authentication token required"));
        return mapToDto(user);
    }

    @Transactional(readOnly = true)
    public Optional<User> resolveUser(String authHeader) {
        if (authHeader == null || authHeader.isBlank()) {
            return Optional.empty();
        }

        Optional<JwtClaims> claimsOpt = jwtTokenService.validateAndParseToken(authHeader);
        if (claimsOpt.isEmpty()) {
            return Optional.empty();
        }

        return userRepository.findById(claimsOpt.get().getUserId());
    }

    public UserDto mapToDto(User user) {
        return UserDto.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
