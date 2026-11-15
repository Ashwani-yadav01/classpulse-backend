package com.classpulse.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Polymorphic submission payload submitted by a participant to answer an activity.
 * <p>
 * Depending on the active activity type, one of the three response channels is populated:
 * <ul>
 *   <li>{@code selectedOptionId}: For MCQ and Poll choices.</li>
 *   <li>{@code textResponse}: For Word Cloud, Open Question, and Brainstorming text submissions.</li>
 *   <li>{@code orderedOptionIds}: For Prioritization ranked sequences (top rank to lowest rank).</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmitResponseRequest {

    /**
     * The UUID of the chosen option for MCQ and Poll activities.
     */
    private UUID selectedOptionId;

    /**
     * Textual submission content for Word Cloud, Open Question, and Brainstorming activities.
     */
    private String textResponse;

    /**
     * Ordered list of option UUIDs representing participant ranking preference for Prioritization activities.
     * The first element represents rank 1 (highest priority).
     */
    private List<UUID> orderedOptionIds;
}
