package com.classpulse.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Domain configuration model deserialized to/from the {@code config_json} column of {@link Activity}.
 * <p>
 * Controls feature toggles such as revision allowances, answer visibility, text lengths,
 * and random picker selection filters.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityConfig {

    /**
     * For MCQ activities: whether the correct answer should be revealed to participants
     * immediately upon submission or after the activity closes. Defaults to false.
     */
    @Builder.Default
    private Boolean showCorrectAnswer = false;

    /**
     * General revision setting for MCQ, Word Cloud, Open Question, Brainstorming, and Prioritization.
     * When true, participants can overwrite their previous submission while the activity is ACTIVE.
     */
    @Builder.Default
    private Boolean allowRevision = false;

    /**
     * Controls whether aggregate results (e.g. chart distributions, word clouds, rankings)
     * are visible to student participants in real time. Defaults to false.
     */
    @Builder.Default
    private Boolean resultsVisible = false;

    /**
     * Specifically for Poll activities: controls whether participants can change their vote.
     * Defaults to true.
     */
    @Builder.Default
    private Boolean allowVoteRevision = true;

    /**
     * For Random Student Picker activities: whether participants chosen in previous picks
     * during the same activity should be excluded from future eligibility. Defaults to true.
     */
    @Builder.Default
    private Boolean excludePreviouslyPicked = true;

    /**
     * Maximum character length allowed for single-word submissions (Word Cloud).
     * Defaults to 50 characters.
     */
    @Builder.Default
    private Integer maxWordLength = 50;

    /**
     * Maximum character length allowed for freeform text responses (Open Question / Brainstorming).
     * Defaults to 1000 characters.
     */
    @Builder.Default
    private Integer maxTextLength = 1000;

    /**
     * Safe null-guarded getter checking if correct answers should be displayed.
     *
     * @return true if showCorrectAnswer is set to true, false otherwise
     */
    public boolean isShowCorrectAnswer() {
        return Boolean.TRUE.equals(showCorrectAnswer);
    }

    /**
     * Safe null-guarded getter checking if general answer revision is permitted.
     *
     * @return true if allowRevision is set to true, false otherwise
     */
    public boolean isAllowRevision() {
        return Boolean.TRUE.equals(allowRevision);
    }

    /**
     * Safe null-guarded getter checking if aggregated results are visible to students.
     *
     * @return true if resultsVisible is set to true, false otherwise
     */
    public boolean isResultsVisible() {
        return Boolean.TRUE.equals(resultsVisible);
    }

    /**
     * Safe null-guarded getter checking if vote revision is allowed (default is true).
     *
     * @return true unless allowVoteRevision is explicitly false
     */
    public boolean isAllowVoteRevision() {
        return !Boolean.FALSE.equals(allowVoteRevision);
    }

    /**
     * Safe null-guarded getter checking if previously picked students should be excluded.
     *
     * @return true unless excludePreviouslyPicked is explicitly false
     */
    public boolean isExcludePreviouslyPicked() {
        return !Boolean.FALSE.equals(excludePreviouslyPicked);
    }
}
