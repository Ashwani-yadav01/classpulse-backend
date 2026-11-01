package com.classpulse.backend.security;

import com.classpulse.backend.model.UserRole;
import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

@Getter
@Builder
public class JwtClaims {
    private final UUID userId;
    private final String email;
    private final String fullName;
    private final UserRole role;
    private final long exp;
}
