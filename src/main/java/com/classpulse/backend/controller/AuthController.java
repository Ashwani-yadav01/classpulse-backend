package com.classpulse.backend.controller;

import com.classpulse.backend.dto.AuthResponse;
import com.classpulse.backend.dto.LoginRequest;
import com.classpulse.backend.dto.RegisterRequest;
import com.classpulse.backend.dto.UserDto;
import com.classpulse.backend.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API controller managing user authentication, registration, and identity discovery.
 * <p>
 * Provides endpoints for:
 * <ul>
 *   <li>Teacher and Student account registration ({@code POST /api/v1/auth/register}).</li>
 *   <li>Credential login yielding stateless JWT tokens ({@code POST /api/v1/auth/login}).</li>
 *   <li>Profile introspection of the currently authenticated bearer ({@code GET /api/v1/auth/me}).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    /** Underlying domain service executing authentication and registration logic. */
    private final AuthService authService;

    /**
     * Registers a new user account (Teacher or Student).
     *
     * @param request validated registration payload containing email, password, full name, and role
     * @return HTTP 201 Created containing the issued JWT token and user profile
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        // Step 1: Delegate account registration and JWT generation to AuthService
        AuthResponse response = authService.register(request);

        // Step 2: Return HTTP 201 Created with the response envelope
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Authenticates an existing user via email and password credentials.
     *
     * @param request validated login credentials
     * @return HTTP 200 OK containing the issued JWT token and user profile
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        // Step 1: Verify credentials and issue signed JWT via AuthService
        AuthResponse response = authService.login(request);

        // Step 2: Return HTTP 200 OK
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves the profile information of the currently authenticated user from the Authorization header.
     *
     * @param authHeader incoming HTTP Authorization header (e.g. "Bearer eyJhbGci...")
     * @return HTTP 200 OK containing the public {@link UserDto}
     */
    @GetMapping("/me")
    public ResponseEntity<UserDto> getMe(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        // Step 1: Validate token and extract user details
        UserDto response = authService.getCurrentUser(authHeader);

        // Step 2: Return user profile
        return ResponseEntity.ok(response);
    }
}
