package com.classpulse.backend.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivityConfig {

    // MCQ settings
    @Builder.Default
    private Boolean showCorrectAnswer = false;

    // General revision setting (MCQ, Word Cloud, Open Question, Brainstorming, Prioritization)
    @Builder.Default
    private Boolean allowRevision = false;

    // Visibility of results to participants
    @Builder.Default
    private Boolean resultsVisible = false;

    // Poll settings
    @Builder.Default
    private Boolean allowVoteRevision = true;

    // Random Student Picker settings (default: true so picked students are not chosen again)
    @Builder.Default
    private Boolean excludePreviouslyPicked = true;

    // Length limit configurations
    @Builder.Default
    private Integer maxWordLength = 50;

    @Builder.Default
    private Integer maxTextLength = 1000;

    public boolean isShowCorrectAnswer() {
        return Boolean.TRUE.equals(showCorrectAnswer);
    }

    public boolean isAllowRevision() {
        return Boolean.TRUE.equals(allowRevision);
    }

    public boolean isResultsVisible() {
        return Boolean.TRUE.equals(resultsVisible);
    }

    public boolean isAllowVoteRevision() {
        return !Boolean.FALSE.equals(allowVoteRevision);
    }

    public boolean isExcludePreviouslyPicked() {
        return !Boolean.FALSE.equals(excludePreviouslyPicked);
    }
}
