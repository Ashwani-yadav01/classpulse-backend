package com.classpulse.backend.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Service responsible for assembling and dispatching application events
 * destined for WebSocket broadcast to clients.
 * <p>
 * Instead of pushing directly to the network during database transactions,
 * events are routed through Spring's {@link ApplicationEventPublisher} so that
 * {@link TransactionalWebSocketEventListener} can guarantee they are only transmitted
 * AFTER the encompassing database transaction commits successfully.
 */
@Service
@RequiredArgsConstructor
public class WebSocketEventPublisher {

    /** Spring core event publisher. */
    private final ApplicationEventPublisher eventPublisher;

    /** System clock provider for accurate event timestamping. */
    private final Clock clock;

    /**
     * Publishes a generic WebSocket event wrapped in an {@link ActivityWebSocketEvent} envelope.
     *
     * @param eventType   event category matching {@link WebSocketEventType}
     * @param sessionId   target session UUID
     * @param activityId  target activity UUID (or null)
     * @param payload     data object being sent
     * @param destination STOMP destination channel
     */
    public void publishAfterCommit(String eventType, UUID sessionId, UUID activityId, Object payload, String destination) {
        // Capture current instant from centralized clock
        Instant now = clock.instant();

        // Assemble envelope event object
        ActivityWebSocketEvent event = ActivityWebSocketEvent.builder()
                .eventType(eventType)
                .sessionId(sessionId)
                .activityId(activityId)
                .timestamp(now)
                .payload(payload)
                .destination(destination)
                .build();

        // Publish internal Spring application event
        eventPublisher.publishEvent(event);
    }

    /**
     * Publishes an event to the general session topic: {@code /topic/sessions/{sessionId}}.
     *
     * @param eventType event name
     * @param sessionId session UUID
     * @param payload   event payload
     */
    public void publishSessionEvent(String eventType, UUID sessionId, Object payload) {
        publishAfterCommit(eventType, sessionId, null, payload, "/topic/sessions/" + sessionId);
    }

    /**
     * Publishes an event to a specific activity topic: {@code /topic/sessions/{sessionId}/activities/{activityId}}.
     *
     * @param eventType  event name
     * @param sessionId  session UUID
     * @param activityId activity UUID
     * @param payload    event payload
     */
    public void publishActivityEvent(String eventType, UUID sessionId, UUID activityId, Object payload) {
        publishAfterCommit(eventType, sessionId, activityId, payload, "/topic/sessions/" + sessionId + "/activities/" + activityId);
    }

    /**
     * Publishes a teacher-only event: {@code /topic/sessions/{sessionId}/teacher}.
     *
     * @param eventType  event name
     * @param sessionId  session UUID
     * @param activityId activity UUID (or null)
     * @param payload    event payload
     */
    public void publishTeacherEvent(String eventType, UUID sessionId, UUID activityId, Object payload) {
        publishAfterCommit(eventType, sessionId, activityId, payload, "/topic/sessions/" + sessionId + "/teacher");
    }
}
