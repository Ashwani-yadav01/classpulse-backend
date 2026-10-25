package com.classpulse.backend.result.dto;

import com.classpulse.backend.activity.ActivityType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActivityResultResponse {
    private UUID activityId;
    private String activityTitle;
    private ActivityType activityType;
    private long totalResponses;
    private Long totalVotes;
    private boolean resultsVisible;

    // For MCQ, Poll, Prioritization
    @Builder.Default
    private List<OptionResultDto> options = new ArrayList<>();

    // For Word Cloud
    @Builder.Default
    private List<WordCloudItemDto> wordCloud = new ArrayList<>();

    // For Open Question
    @Builder.Default
    private List<OpenQuestionAnswerDto> textAnswers = new ArrayList<>();

    // For Brainstorming
    @Builder.Default
    private List<BrainstormingIdeaDto> ideas = new ArrayList<>();

    // For Random Picker
    private RandomPickerResultDto randomPicker;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OptionResultDto {
        private UUID optionId;
        private String optionText;
        private long count;
        private double percentage;
        private Boolean isCorrect;
        private Integer score;
        private Integer rank;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WordCloudItemDto {
        private String word;
        private long count;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OpenQuestionAnswerDto {
        private UUID responseId;
        private String answerText;
        private Instant submittedAt;
        private String displayName;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BrainstormingIdeaDto {
        private UUID responseId;
        private String ideaText;
        private Instant submittedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RandomPickerResultDto {
        private SelectedParticipantDto latestSelection;
        @Builder.Default
        private List<SelectedParticipantDto> history = new ArrayList<>();
        private int totalEligible;
        private int totalPickedSoFar;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SelectedParticipantDto {
        private UUID participantId;
        private String displayName;
        private Instant pickedAt;
    }
}
