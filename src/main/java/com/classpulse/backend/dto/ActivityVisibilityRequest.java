package com.classpulse.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload sent by instructors to dynamically toggle activity result visibility
 * or correct answer reveals for participants in real time.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityVisibilityRequest {

    /**
     * Toggles whether the correct answer indicator is revealed to students (MCQ).
     */
    private Boolean showCorrectAnswer;

    /**
     * Toggles whether live charts and aggregate summaries are visible to students.
     */
    private Boolean resultsVisible;
}
