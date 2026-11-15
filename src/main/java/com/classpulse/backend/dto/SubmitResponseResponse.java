package com.classpulse.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Confirmation payload returned to a participant immediately after submitting an answer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SubmitResponseResponse {

    /**
     * Unique identifier of the recorded response entity.
     */
    private UUID responseId;

    /**
     * UUID of the activity that was answered.
     */
    private UUID activityId;

    /**
     * Selected option UUID (MCQ/Poll), if applicable.
     */
    private UUID selectedOptionId;

    /**
     * Submitted text response (Word Cloud/Open Question/Brainstorming), if applicable.
     */
    private String textResponse;

    /**
     * Ordered ranking option UUIDs (Prioritization), if applicable.
     */
    private List<UUID> orderedOptionIds;

    /**
     * Flag indicating whether this submission modified/overwrote a pre-existing answer (revision).
     */
    private boolean revised;

    /**
     * UTC timestamp when this submission was processed and committed.
     */
    private Instant submittedAt;
}
