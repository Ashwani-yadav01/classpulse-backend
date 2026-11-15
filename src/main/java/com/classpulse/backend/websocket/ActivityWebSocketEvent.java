package com.classpulse.backend.websocket;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Standard envelope payload broadcast across STOMP WebSocket channels to connected clients.
 * <p>
 * Contains the event type discriminator, session and activity context IDs, creation timestamp,
 * the targeted STOMP destination path, and the domain data payload.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActivityWebSocketEvent {

    /**
     * Event type discriminator name matching {@link WebSocketEventType} constants.
     */
    private String eventType;

    /**
     * Unique identifier of the session to which this event belongs.
     */
    private UUID sessionId;

    /**
     * Optional UUID of the specific activity involved, or null for session-level events.
     */
    private UUID activityId;

    /**
     * Exact UTC timestamp when the event was generated.
     */
    private Instant timestamp;

    /**
     * Concrete domain payload object (e.g. ActivityResponse, ActivityResultResponse, ParticipantDto).
     */
    private Object payload;

    /**
     * The targeted STOMP topic destination path (e.g. "/topic/sessions/{sessionId}").
     */
    private String destination;
}
