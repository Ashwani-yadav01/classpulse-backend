package com.classpulse.backend.dto;

import com.classpulse.backend.model.Session;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload submitted by a teacher to initialize and launch a new classroom session.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateSessionRequest {

    /**
     * Human-readable title of the classroom session (e.g. "Software Architecture Lecture 2").
     */
    @NotBlank(message = "Session title cannot be blank")
    @Size(max = 255, message = "Session title cannot exceed 255 characters")
    private String title;

    /**
     * Temporal lifespan of the session in hours before expiration.
     * Must be between 1 and 168 hours (7 days). If null, defaults to configured system value.
     */
    @Min(value = 1, message = "Duration must be at least 1 hour")
    @Max(value = 168, message = "Duration cannot exceed 168 hours (7 days)")
    private Integer durationHours;
}
