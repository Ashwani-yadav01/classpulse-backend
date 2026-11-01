package com.classpulse.backend.dto;

import com.classpulse.backend.model.ActivityStatus;
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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActivityResponse {
    private UUID id;
    private UUID sessionId;
    private ActivityType type;
    private String title;
    private ActivityStatus status;
    private String configJson;
    private Integer orderIndex;
    private Instant createdAt;
    private Instant activatedAt;
    private Instant closedAt;
    @Builder.Default
    private List<ActivityOptionDto> options = new ArrayList<>();
}
