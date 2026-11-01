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

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    public ResponseEntity<UserDto> getMe(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        UserDto response = authService.getCurrentUser(authHeader);
        return ResponseEntity.ok(response);
    }
}
