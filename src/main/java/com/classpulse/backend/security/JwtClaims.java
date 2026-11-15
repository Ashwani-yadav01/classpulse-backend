package com.classpulse.backend.security;

import com.classpulse.backend.model.UserRole;
import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

/**
 * Value object representing verified cryptographic claims extracted from a valid JSON Web Token.
 * <p>
 * Carries verified user identity, authority level, email address, and expiration boundary.
 */
@Getter
@Builder
public class JwtClaims {

    /**
     * Unique user UUID extracted from the 'sub' (subject) claim.
     */
    private final UUID userId;

    /**
     * User's verified email address extracted from the 'email' claim.
     */
    private final String email;

    /**
     * User's full name extracted from the 'fullName' claim.
     */
    private final String fullName;

    /**
     * User role enum (TEACHER or STUDENT) extracted from the 'role' claim.
     */
    private final UserRole role;

    /**
     * Token expiration timestamp in epoch seconds extracted from the 'exp' claim.
     */
    private final long exp;
}
