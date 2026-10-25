package com.classpulse.backend.websocket.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionalWebSocketEventListener {

    private final SimpMessagingTemplate messagingTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handleAfterCommit(ActivityWebSocketEvent event) {
        try {
            messagingTemplate.convertAndSend(event.getDestination(), event);
            log.debug("Successfully published WebSocket event '{}' to destination '{}'", event.getEventType(), event.getDestination());
        } catch (Exception e) {
            log.error("Failed to publish WebSocket event '{}' to destination '{}': {}",
                    event.getEventType(), event.getDestination(), e.getMessage());
        }
    }
}
