package com.classpulse.backend.session.dto;

import com.classpulse.backend.session.SessionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionPublicInfoResponse {
    private UUID id;
    private String title;
    private String joinCode;
    private SessionStatus status;
    private boolean active;
    private String joinUrl;
}
