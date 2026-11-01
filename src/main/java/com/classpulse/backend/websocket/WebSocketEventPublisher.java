package com.classpulse.backend.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WebSocketEventPublisher {

    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public void publishAfterCommit(String eventType, UUID sessionId, UUID activityId, Object payload, String destination) {
        Instant now = clock.instant();
        ActivityWebSocketEvent event = ActivityWebSocketEvent.builder()
                .eventType(eventType)
                .sessionId(sessionId)
                .activityId(activityId)
                .timestamp(now)
                .payload(payload)
                .destination(destination)
                .build();
        eventPublisher.publishEvent(event);
    }

    public void publishSessionEvent(String eventType, UUID sessionId, Object payload) {
        publishAfterCommit(eventType, sessionId, null, payload, "/topic/sessions/" + sessionId);
    }

    public void publishActivityEvent(String eventType, UUID sessionId, UUID activityId, Object payload) {
        publishAfterCommit(eventType, sessionId, activityId, payload, "/topic/sessions/" + sessionId + "/activities/" + activityId);
    }

    public void publishTeacherEvent(String eventType, UUID sessionId, UUID activityId, Object payload) {
        publishAfterCommit(eventType, sessionId, activityId, payload, "/topic/sessions/" + sessionId + "/teacher");
    }
}
