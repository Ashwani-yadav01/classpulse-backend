package com.classpulse.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Authentication response returned upon successful user registration or login.
 * <p>
 * Contains the signed stateless JSON Web Token (JWT) and user profile information.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {

    /**
     * Signed JWT bearer token presented by clients in the Authorization HTTP header.
     */
    private String token;

    /**
     * Public user profile details associated with the authenticated account.
     */
    private UserDto user;
}
