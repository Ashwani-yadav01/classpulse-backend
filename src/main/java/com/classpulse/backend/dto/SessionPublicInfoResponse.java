package com.classpulse.backend.dto;

import com.classpulse.backend.model.SessionStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Public Data Transfer Object returned to unauthenticated students querying session status
 * via join code before entering their nickname.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionPublicInfoResponse {

    /**
     * Unique identifier of the session.
     */
    private UUID id;

    /**
     * Title of the session (e.g. "Biology 201").
     */
    private String title;

    /**
     * 6-character alphanumeric join code.
     */
    private String joinCode;

    /**
     * Current lifecycle status of the session.
     */
    private SessionStatus status;

    /**
     * Boolean indicator stating if the session is currently joinable and active.
     */
    private boolean active;

    /**
     * Absolute web URL that students can navigate to directly join this session.
     */
    private String joinUrl;
}
