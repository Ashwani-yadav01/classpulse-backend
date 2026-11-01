package com.classpulse.backend.websocket;

import lombok.Getter;

import java.security.Principal;
import java.util.UUID;

@Getter
public class WebSocketUserPrincipal implements Principal {

    public enum Role {
        MASTER_TEACHER,
        TEACHER,
        PARTICIPANT
    }

    private final String name;
    private final UUID sessionId;
    private final Role role;
    private final UUID participantId;
    private final UUID userId;

    public WebSocketUserPrincipal(String name, UUID sessionId, Role role, UUID participantId) {
        this(name, sessionId, role, participantId, null);
    }

    public WebSocketUserPrincipal(String name, UUID sessionId, Role role, UUID participantId, UUID userId) {
        this.name = name;
        this.sessionId = sessionId;
        this.role = role;
        this.participantId = participantId;
        this.userId = userId;
    }

    @Override
    public String getName() {
        return name;
    }

    public boolean isTeacher() {
        return role == Role.TEACHER || role == Role.MASTER_TEACHER;
    }

    public boolean isMasterTeacher() {
        return role == Role.MASTER_TEACHER;
    }
}
