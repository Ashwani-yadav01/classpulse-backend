package com.classpulse.backend.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Transaction-synchronized event listener that delivers WebSocket events to connected STOMP clients.
 * <p>
 * Configured with {@code phase = TransactionPhase.AFTER_COMMIT} and {@code fallbackExecution = true}.
 * This ensures that:
 * <ul>
 *   <li>Events are broadcast only after database transactions commit cleanly, eliminating ghost events on rollback.</li>
 *   <li>Events published outside an active transaction boundary are still reliably delivered (fallback execution).</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionalWebSocketEventListener {

    /** Spring messaging template for sending STOMP messages over the configured message broker. */
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Handles an {@link ActivityWebSocketEvent} immediately following successful transaction commit.
     *
     * @param event the event to broadcast
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void handleAfterCommit(ActivityWebSocketEvent event) {
        try {
            // Deliver event payload to the destination STOMP topic
            messagingTemplate.convertAndSend(event.getDestination(), event);
            log.debug("Successfully published WebSocket event '{}' to destination '{}'", event.getEventType(), event.getDestination());
        } catch (Exception e) {
            // Log delivery errors without crashing calling transaction threads
            log.error("Failed to publish WebSocket event '{}' to destination '{}': {}",
                    event.getEventType(), event.getDestination(), e.getMessage());
        }
    }
}
