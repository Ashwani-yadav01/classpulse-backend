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

/**
 * Service managing user identity lifecycle, credential authentication, and JWT token issuance.
 * <p>
 * Key capabilities:
 * <ul>
 *   <li>Account registration for teachers and students with BCrypt password hashing.</li>
 *   <li>Email/password credential verification with constant-time password matching.</li>
 *   <li>Stateless HMAC-SHA256 JWT generation and claim resolution.</li>
 *   <li>User profile retrieval and DTO mapping.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** Repository for User entity persistence and email lookups. */
    private final UserRepository userRepository;

    /** BCrypt password hashing implementation. */
    private final PasswordEncoder passwordEncoder;

    /** Token service for generating and validating compact JWTs. */
    private final JwtTokenService jwtTokenService;

    /** Application clock provider for deterministic timestamp generation. */
    private final Clock clock;

    /**
     * Registers a new user account on the ClassPulse platform.
     *
     * @param request the registration details containing email, password, full name, and role
     * @return {@link AuthResponse} containing the issued JWT token and public user profile
     * @throws ConflictException if an account already exists with the given email address
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // Step 1: Normalize email address by trimming whitespace and converting to lowercase
        String normalizedEmail = request.getEmail().trim().toLowerCase();

        // Step 2: Ensure email is not already registered (enforces platform-wide email uniqueness)
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new ConflictException("An account with email " + normalizedEmail + " already exists");
        }

        // Step 3: Capture current UTC instant from the centralized application clock
        Instant now = clock.instant();

        // Step 4: Build a new User domain entity with BCrypt-hashed password
        User user = User.builder()
                .id(UUID.randomUUID()) // Assign new surrogate primary key UUID
                .email(normalizedEmail) // Set case-normalized email
                .passwordHash(passwordEncoder.encode(request.getPassword().trim())) // Hash raw password using BCrypt
                .fullName(request.getFullName().trim()) // Store trimmed display name
                .role(request.getRole()) // Assign TEACHER or STUDENT role
                .createdAt(now) // Timestamp account creation
                .updatedAt(now) // Initialize account update timestamp
                .build();

        // Step 5: Persist user entity into the database
        User saved = userRepository.save(user);

        // Step 6: Generate signed stateless JWT containing subject UUID, email, and role
        String token = jwtTokenService.generateToken(saved);

        // Step 7: Construct and return response envelope with token and sanitized UserDto
        return AuthResponse.builder()
                .token(token)
                .user(mapToDto(saved))
                .build();
    }

    /**
     * Authenticates an existing user using email and password credentials.
     *
     * @param request the login request containing email and plain-text password
     * @return {@link AuthResponse} containing a fresh JWT token and user profile
     * @throws UnauthorizedException if email does not exist or password does not match
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        // Step 1: Normalize email address by trimming whitespace and converting to lowercase
        String normalizedEmail = request.getEmail().trim().toLowerCase();

        // Step 2: Locate user account by email; throw 401 if not found
        User user = userRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));

        // Step 3: Verify candidate password against stored BCrypt hash using constant-time comparison
        if (!passwordEncoder.matches(request.getPassword().trim(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password");
        }

        // Step 4: Issue new signed JWT token valid for 7 days
        String token = jwtTokenService.generateToken(user);

        // Step 5: Return authentication response with token and user profile
        return AuthResponse.builder()
                .token(token)
                .user(mapToDto(user))
                .build();
    }

    /**
     * Retrieves the profile of the user identified by the incoming Authorization header.
     *
     * @param authHeader HTTP Authorization header containing Bearer JWT
     * @return sanitized {@link UserDto} representing the authenticated user
     * @throws UnauthorizedException if token is absent, malformed, expired, or user not found
     */
    @Transactional(readOnly = true)
    public UserDto getCurrentUser(String authHeader) {
        // Step 1: Resolve the user entity from the JWT Authorization header
        User user = resolveUser(authHeader)
                .orElseThrow(() -> new UnauthorizedException("Valid authentication token required"));

        // Step 2: Transform entity to DTO and return
        return mapToDto(user);
    }

    /**
     * Resolves a {@link User} entity from an HTTP Authorization header containing a Bearer JWT.
     *
     * @param authHeader incoming HTTP header string (e.g. "Bearer eyJhbGciOiJIUzI1...")
     * @return Optional containing User if token is valid and user exists in database; empty Optional otherwise
     */
    @Transactional(readOnly = true)
    public Optional<User> resolveUser(String authHeader) {
        // Step 1: Check for absent or empty header
        if (authHeader == null || authHeader.isBlank()) {
            return Optional.empty();
        }

        // Step 2: Cryptographically parse and validate token claims (signature, expiration, claims)
        Optional<JwtClaims> claimsOpt = jwtTokenService.validateAndParseToken(authHeader);
        if (claimsOpt.isEmpty()) {
            return Optional.empty();
        }

        // Step 3: Fetch active user record from database using the verified userId UUID
        return userRepository.findById(claimsOpt.get().getUserId());
    }

    /**
     * Maps an internal {@link User} JPA entity to a safe, public {@link UserDto}.
     * Ensures password hashes and internal fields are never exposed to clients.
     *
     * @param user the persistent User entity
     * @return the transformed UserDto
     */
    public UserDto mapToDto(User user) {
        // Map persistent entity attributes to public transfer object
        return UserDto.builder()
                .id(user.getId()) // Primary key UUID
                .email(user.getEmail()) // User email
                .fullName(user.getFullName()) // Display name
                .role(user.getRole()) // Security role (TEACHER or STUDENT)
                .createdAt(user.getCreatedAt()) // Account creation instant
                .build();
    }
}
