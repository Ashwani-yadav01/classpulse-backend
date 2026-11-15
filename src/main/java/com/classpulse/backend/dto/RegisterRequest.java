package com.classpulse.backend.dto;

import com.classpulse.backend.model.UserRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for creating and registering a new user account on ClassPulse.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequest {

    /**
     * Unique email address for the user account.
     */
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    /**
     * Account password. Minimum requirement is 6 characters.
     */
    @NotBlank(message = "Password is required")
    @Size(min = 6, message = "Password must be at least 6 characters")
    private String password;

    /**
     * Full real name or legal name of the user (maximum 255 characters).
     */
    @NotBlank(message = "Full name is required")
    @Size(max = 255, message = "Full name cannot exceed 255 characters")
    private String fullName;

    /**
     * Role specification (TEACHER or STUDENT).
     */
    @NotNull(message = "User role is required (TEACHER or STUDENT)")
    private UserRole role;
}
