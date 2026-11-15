package com.classpulse.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Data Transfer Object returned immediately when an instructor draws a random student
 * during a Random Student Picker activity.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RandomPickerResponse {

    /**
     * Unique identifier of the generated selection record.
     */
    private UUID selectionId;

    /**
     * UUID of the active Random Picker activity.
     */
    private UUID activityId;

    /**
     * UUID of the student who was chosen.
     */
    private UUID participantId;

    /**
     * Display nickname of the chosen student.
     */
    private String displayName;

    /**
     * UTC timestamp when this selection was generated.
     */
    private Instant pickedAt;

    /**
     * Total count of eligible participants considered during this draw.
     */
    private int totalEligible;

    /**
     * Cumulative count of students picked so far across all draws for this activity.
     */
    private int totalPickedSoFar;
}
