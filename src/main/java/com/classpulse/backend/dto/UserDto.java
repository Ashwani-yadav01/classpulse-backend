package com.classpulse.backend.dto;

import com.classpulse.backend.model.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Public profile Data Transfer Object representing a registered user account.
 * <p>
 * Excludes sensitive cryptographic secrets (such as password hashes).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDto {

    /**
     * Unique surrogate primary key UUID of the user.
     */
    private UUID id;

    /**
     * Account email address.
     */
    private String email;

    /**
     * User's full real name or display name.
     */
    private String fullName;

    /**
     * User role (TEACHER or STUDENT).
     */
    private UserRole role;

    /**
     * UTC timestamp when this user account was registered.
     */
    private Instant createdAt;
}
