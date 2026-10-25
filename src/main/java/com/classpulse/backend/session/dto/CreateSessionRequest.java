package com.classpulse.backend.session.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateSessionRequest {

    @NotBlank(message = "Session title cannot be blank")
    @Size(max = 255, message = "Session title cannot exceed 255 characters")
    private String title;

    @Min(value = 1, message = "Duration must be at least 1 hour")
    @Max(value = 168, message = "Duration cannot exceed 168 hours (7 days)")
    private Integer durationHours;
}
