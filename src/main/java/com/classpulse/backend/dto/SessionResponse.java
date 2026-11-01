package com.classpulse.backend.dto;

import com.classpulse.backend.model.SessionStatus;

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
public class SessionResponse {
    private UUID id;
    private String title;
    private String joinCode;
    private SessionStatus status;
    private String teacherToken;
    private String joinUrl;
    private Instant createdAt;
    private Instant expiresAt;
    private Instant closedAt;
    private Long participantCount;
    private String teacherName;
    private Boolean isOwner;
}
