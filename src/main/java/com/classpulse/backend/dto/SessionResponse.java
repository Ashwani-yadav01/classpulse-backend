package com.classpulse.backend.dto;

import com.classpulse.backend.model.SessionStatus;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Data Transfer Object representing detailed session metadata.
 * <p>
 * Returned when creating, fetching, or listing sessions. Includes instructor token (only when creating),
 * QR join URL, active participant count, owner name, and ownership flags.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SessionResponse {

    /**
     * Unique identifier of the session.
     */
    private UUID id;

    /**
     * Human-readable title of the session.
     */
    private String title;

    /**
     * 6-character alphanumeric join code.
     */
    private String joinCode;

    /**
     * Current lifecycle status (ACTIVE, CLOSED, EXPIRED).
     */
    private SessionStatus status;

    /**
     * Secret teacher session token. Populated ONLY in response to initial session creation.
     */
    private String teacherToken;

    /**
     * Direct join URL for web clients or QR code generation.
     */
    private String joinUrl;

    /**
     * UTC timestamp when the session was initialized.
     */
    private Instant createdAt;

    /**
     * UTC timestamp when the session expires automatically.
     */
    private Instant expiresAt;

    /**
     * UTC timestamp when the session was terminated by instructor.
     */
    private Instant closedAt;

    /**
     * Total number of active participants currently in this session.
     */
    private Long participantCount;

    /**
     * Full name of the teacher who created or owns the session.
     */
    private String teacherName;

    /**
     * Indicates whether the authenticated user making the request is the owner of the session.
     */
    private Boolean isOwner;
}
