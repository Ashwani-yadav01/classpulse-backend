package com.classpulse.backend.response.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SubmitResponseResponse {
    private UUID responseId;
    private UUID activityId;
    private UUID selectedOptionId;
    private String textResponse;
    private List<UUID> orderedOptionIds;
    private boolean revised;
    private Instant submittedAt;
}
