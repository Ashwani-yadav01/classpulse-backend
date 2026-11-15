package com.classpulse.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload submitted by a student to join an active classroom session.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JoinSessionRequest {

    /**
     * The 6-character alphanumeric join code (e.g., "A3X9K2") identifying the target session.
     */
    @NotBlank(message = "Join code cannot be blank")
    private String joinCode;

    /**
     * Unique display name chosen by the student within this session (between 1 and 50 characters).
     */
    @NotBlank(message = "Display name cannot be blank")
    @Size(min = 1, max = 50, message = "Display name must be between 1 and 50 characters")
    private String displayName;
}
