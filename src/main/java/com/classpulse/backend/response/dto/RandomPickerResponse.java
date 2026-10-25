package com.classpulse.backend.response.dto;

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
public class RandomPickerResponse {
    private UUID selectionId;
    private UUID activityId;
    private UUID participantId;
    private String displayName;
    private Instant pickedAt;
    private int totalEligible;
    private int totalPickedSoFar;
}
