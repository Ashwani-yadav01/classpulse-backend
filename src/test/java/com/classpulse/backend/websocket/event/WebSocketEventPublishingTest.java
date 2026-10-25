package com.classpulse.backend.websocket.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebSocketEventPublishingTest {

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private WebSocketEventPublisher publisher;
    private TransactionalWebSocketEventListener listener;
    private final Instant now = Instant.parse("2026-10-25T12:00:00Z");

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(now, ZoneId.of("UTC"));
        publisher = new WebSocketEventPublisher(eventPublisher, fixedClock);
        listener = new TransactionalWebSocketEventListener(messagingTemplate);
    }

    @Test
    void publishActivityEvent_shouldCreateAndPublishEvent() {
        UUID sessionId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        Map<String, Object> payload = Map.of("count", 1);

        publisher.publishActivityEvent(WebSocketEventType.RESPONSE_SUBMITTED, sessionId, activityId, payload);

        ArgumentCaptor<ActivityWebSocketEvent> captor = ArgumentCaptor.forClass(ActivityWebSocketEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        ActivityWebSocketEvent captured = captor.getValue();
        assertThat(captured.getEventType()).isEqualTo(WebSocketEventType.RESPONSE_SUBMITTED);
        assertThat(captured.getSessionId()).isEqualTo(sessionId);
        assertThat(captured.getActivityId()).isEqualTo(activityId);
        assertThat(captured.getDestination()).isEqualTo("/topic/sessions/" + sessionId + "/activities/" + activityId);
        assertThat(captured.getTimestamp()).isEqualTo(now);
    }

    @Test
    void listener_handleAfterCommit_shouldSendToSimpMessagingTemplate() {
        UUID sessionId = UUID.randomUUID();
        UUID activityId = UUID.randomUUID();
        ActivityWebSocketEvent event = ActivityWebSocketEvent.builder()
                .eventType(WebSocketEventType.RESULTS_UPDATED)
                .sessionId(sessionId)
                .activityId(activityId)
                .destination("/topic/sessions/" + sessionId + "/activities/" + activityId)
                .timestamp(now)
                .build();

        listener.handleAfterCommit(event);

        verify(messagingTemplate).convertAndSend(event.getDestination(), event);
    }

    @Test
    void listener_handleAfterCommit_whenTemplateThrows_shouldNotThrowException() {
        ActivityWebSocketEvent event = ActivityWebSocketEvent.builder()
                .eventType(WebSocketEventType.SESSION_CLOSED)
                .destination("/topic/sessions/test")
                .build();

        doThrow(new RuntimeException("SimpMessagingTemplate delivery failed"))
                .when(messagingTemplate).convertAndSend(anyString(), any(Object.class));

        // Must not throw exception
        listener.handleAfterCommit(event);
    }
}
