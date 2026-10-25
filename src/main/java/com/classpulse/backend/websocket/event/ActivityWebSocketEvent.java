package com.classpulse.backend.websocket.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActivityWebSocketEvent {
    private String eventType;
    private UUID sessionId;
    private UUID activityId;
    private Instant timestamp;
    private Object payload;
    private String destination;
}
