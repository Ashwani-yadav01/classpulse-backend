package com.classpulse.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponseDetailDto {
    private UUID responseId;
    private UUID participantId;
    private String displayName;
    private String answerSummary;
    private Instant submittedAt;
    private Long responseTimeMs;
    private Boolean isCorrect;
}
