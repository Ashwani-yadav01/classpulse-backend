package com.classpulse.backend.dto;

import com.classpulse.backend.model.Activity;
import com.classpulse.backend.model.ActivityType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Request payload submitted by a teacher to create a new activity within an existing session.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateActivityRequest {

    /**
     * Pedagogical type of activity being created. Mandatory field.
     */
    @NotNull(message = "Activity type is required")
    private ActivityType type;

    /**
     * Question title, prompt, or discussion topic. Cannot be blank and max length is 255 characters.
     */
    @NotBlank(message = "Activity title cannot be blank")
    @Size(max = 255, message = "Activity title cannot exceed 255 characters")
    private String title;

    /**
     * Optional JSON string representing customized activity configuration flags.
     */
    private String configJson;

    /**
     * Optional sequence order index. If omitted, service auto-assigns next index at the tail of the queue.
     */
    private Integer orderIndex;

    /**
     * Pre-configured options/choices (mandatory for MCQ, Poll, and Prioritization; empty for open types).
     */
    @Builder.Default
    private List<ActivityOptionDto> options = new ArrayList<>();
}
