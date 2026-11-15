package com.classpulse.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Response payload returned to a student upon successfully joining a classroom session.
 * <p>
 * Contains the crucial secret {@code participantToken} that the student client must persist
 * and include in the {@code X-Participant-Token} HTTP header and WebSocket STOMP connect headers.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JoinSessionResponse {

    /**
     * Unique UUID identifying the newly enrolled participant record.
     */
    private UUID participantId;

    /**
     * UUID of the session joined.
     */
    private UUID sessionId;

    /**
     * Display name confirmed for the participant.
     */
    private String displayName;

    /**
     * Secret, unhashed participant session token.
     * Note: This is returned ONLY on initial join and is never sent again.
     */
    private String participantToken;

    /**
     * UTC timestamp when the participant entered the session.
     */
    private Instant joinedAt;
}
