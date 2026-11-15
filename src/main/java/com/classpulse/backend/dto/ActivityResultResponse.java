package com.classpulse.backend.dto;

import com.classpulse.backend.model.ActivityType;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Composite Data Transfer Object returned by result aggregation endpoints and broadcast via WebSockets.
 * <p>
 * Contains aggregated statistics and visualizations customized for every activity type:
 * <ul>
 *   <li>MCQ / Poll: Vote counts, percentages, correctness indicators.</li>
 *   <li>Prioritization: Borda-count positional scores and ranks.</li>
 *   <li>Word Cloud: Clustered frequencies of normalized words.</li>
 *   <li>Open Question: Individual participant textual answers with response timings.</li>
 *   <li>Brainstorming: Submitted ideas with participant metadata and timings.</li>
 *   <li>Random Picker: History of picks and eligible participant roster counts.</li>
 *   <li>Teacher view: Granular breakdown list of all individual responses.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActivityResultResponse {

    /**
     * Unique identifier of the activity.
     */
    private UUID activityId;

    /**
     * Question title or topic of the activity.
     */
    private String activityTitle;

    /**
     * Activity module type.
     */
    private ActivityType activityType;

    /**
     * Total number of distinct submissions received for this activity.
     */
    private long totalResponses;

    /**
     * Total votes counted across options (relevant for multi-vote or poll options).
     */
    private Long totalVotes;

    /**
     * Indicates whether student participants are currently authorized to view these results.
     */
    private boolean resultsVisible;

    /**
     * Option-level results for MCQ, Poll, and Prioritization activities.
     */
    @Builder.Default
    private List<OptionResultDto> options = new ArrayList<>();

    /**
     * Frequency-counted word items for Word Cloud activities.
     */
    @Builder.Default
    private List<WordCloudItemDto> wordCloud = new ArrayList<>();

    /**
     * Individual textual responses for Open Question activities.
     */
    @Builder.Default
    private List<OpenQuestionAnswerDto> textAnswers = new ArrayList<>();

    /**
     * Submitted ideas for Brainstorming activities.
     */
    @Builder.Default
    private List<BrainstormingIdeaDto> ideas = new ArrayList<>();

    /**
     * Selection state and audit history for Random Student Picker activities.
     */
    private RandomPickerResultDto randomPicker;

    /**
     * Detailed response audit log for teachers (who answered, what, when, latency in ms).
     */
    @Builder.Default
    private List<ResponseDetailDto> responseDetails = new ArrayList<>();

    /**
     * Aggregation result entry for a specific activity option.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OptionResultDto {
        /** Unique UUID of the option. */
        private UUID optionId;
        /** Display label of the option. */
        private String optionText;
        /** Total number of votes/selections received for this option. */
        private long count;
        /** Percentage of total votes represented by this option (0.0 to 100.0). */
        private double percentage;
        /** Flag indicating if this was marked as the correct answer (MCQ). */
        private Boolean isCorrect;
        /** Positional Borda-count score (Prioritization). */
        private Integer score;
        /** Final calculated rank position (Prioritization, 1 = top ranked). */
        private Integer rank;
        /** List of participants who selected this option (teacher view). */
        @Builder.Default
        private List<ResponseDetailDto> voters = new ArrayList<>();
    }

    /**
     * Aggregation result entry for a clustered word in a Word Cloud activity.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WordCloudItemDto {
        /** The normalized word text. */
        private String word;
        /** Total occurrences of this word submitted by students. */
        private long count;
        /** Participants who submitted this word (teacher view). */
        @Builder.Default
        private List<ResponseDetailDto> respondents = new ArrayList<>();
    }

    /**
     * Student submission record for an Open Question activity.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OpenQuestionAnswerDto {
        /** UUID of the response record. */
        private UUID responseId;
        /** UUID of the student participant. */
        private UUID participantId;
        /** The raw text answered by the student. */
        private String answerText;
        /** UTC timestamp of submission. */
        private Instant submittedAt;
        /** Elapsed response time in milliseconds from activity activation to submission. */
        private Long responseTimeMs;
        /** Participant display name. */
        private String displayName;
    }

    /**
     * Submitted idea record for a Brainstorming activity.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BrainstormingIdeaDto {
        /** UUID of the response record. */
        private UUID responseId;
        /** UUID of the student participant. */
        private UUID participantId;
        /** Content of the brainstorming idea. */
        private String ideaText;
        /** UTC timestamp of submission. */
        private Instant submittedAt;
        /** Elapsed response time in milliseconds from activity activation to submission. */
        private Long responseTimeMs;
        /** Participant display name. */
        private String displayName;
    }

    /**
     * Summary state and history for Random Student Picker activities.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RandomPickerResultDto {
        /** The most recently selected participant. */
        private SelectedParticipantDto latestSelection;
        /** Reverse chronological history of all selections made in this activity. */
        @Builder.Default
        private List<SelectedParticipantDto> history = new ArrayList<>();
        /** Total active participants eligible to be picked in the session. */
        private int totalEligible;
        /** Total number of selections executed so far. */
        private int totalPickedSoFar;
    }

    /**
     * Information about a participant selected during a random draw.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SelectedParticipantDto {
        /** UUID of the selected participant. */
        private UUID participantId;
        /** Display name of the selected participant. */
        private String displayName;
        /** UTC timestamp when the selection occurred. */
        private Instant pickedAt;
    }
}
