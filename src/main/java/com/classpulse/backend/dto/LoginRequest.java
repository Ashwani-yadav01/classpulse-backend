package com.classpulse.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Authentication request payload for registered user sign-in.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequest {

    /**
     * Account email address. Must be a valid email format and non-blank.
     */
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    /**
     * Raw plain-text account password to be verified against the BCrypt hash.
     */
    @NotBlank(message = "Password is required")
    private String password;
}
