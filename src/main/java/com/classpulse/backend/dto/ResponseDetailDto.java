package com.classpulse.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Granular response audit entry provided to teachers.
 * <p>
 * Contains individual participant identity, summarized answer content, submission timestamp,
 * response latency in milliseconds, and correctness status.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponseDetailDto {

    /**
     * Unique identifier of the response entity.
     */
    private UUID responseId;

    /**
     * Unique identifier of the participant who submitted the response.
     */
    private UUID participantId;

    /**
     * Display name of the participant at time of submission.
     */
    private String displayName;

    /**
     * Textual summary of the answer (e.g. option text, ranked sequence, or freeform text).
     */
    private String answerSummary;

    /**
     * UTC timestamp when the response was submitted.
     */
    private Instant submittedAt;

    /**
     * Latency in milliseconds between activity activation and participant response submission.
     */
    private Long responseTimeMs;

    /**
     * Correctness flag for scored activities (MCQ). Null for non-scored activities.
     */
    private Boolean isCorrect;
}
