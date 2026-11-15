package com.classpulse.backend.websocket;

import lombok.Getter;

import java.security.Principal;
import java.util.UUID;

/**
 * Custom {@link Principal} implementation representing an authenticated STOMP WebSocket connection.
 * <p>
 * Binds the client session to a security role, an optional registered user ID,
 * a participant record ID, and the specific classroom session they are authorized to access.
 */
@Getter
public class WebSocketUserPrincipal implements Principal {

    /**
     * Enumeration of security authorization levels for WebSocket connections.
     */
    public enum Role {
        /** Administrative access possessing universal subscription privileges. */
        MASTER_TEACHER,
        /** Instructor authenticated for a specific session or owning teacher account. */
        TEACHER,
        /** Enrolled student participant restricted to public session channels. */
        PARTICIPANT
    }

    /** Unique identifier string representing this principal in the messaging broker. */
    private final String name;

    /** Session UUID this principal is authorized for, or null if master teacher. */
    private final UUID sessionId;

    /** Security role level assigned during connection handshake. */
    private final Role role;

    /** Participant UUID if authenticated as a participant; null otherwise. */
    private final UUID participantId;

    /** Registered User account UUID if authenticated via JWT; null otherwise. */
    private final UUID userId;

    /**
     * Convenience constructor without registered user account ID.
     *
     * @param name          principal name
     * @param sessionId     authorized session UUID
     * @param role          role category
     * @param participantId participant UUID (if student)
     */
    public WebSocketUserPrincipal(String name, UUID sessionId, Role role, UUID participantId) {
        this(name, sessionId, role, participantId, null);
    }

    /**
     * Full constructor capturing all principal identity facets.
     *
     * @param name          principal name
     * @param sessionId     authorized session UUID
     * @param role          role category
     * @param participantId participant UUID (if student)
     * @param userId        user account UUID (if logged in)
     */
    public WebSocketUserPrincipal(String name, UUID sessionId, Role role, UUID participantId, UUID userId) {
        this.name = name;
        this.sessionId = sessionId;
        this.role = role;
        this.participantId = participantId;
        this.userId = userId;
    }

    /**
     * Returns the unique name of this principal.
     *
     * @return principal name
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * Checks if this principal possesses instructor-level privileges (TEACHER or MASTER_TEACHER).
     *
     * @return true if teacher or master teacher, false otherwise
     */
    public boolean isTeacher() {
        return role == Role.TEACHER || role == Role.MASTER_TEACHER;
    }

    /**
     * Checks if this principal possesses master administrative privileges.
     *
     * @return true if master teacher, false otherwise
     */
    public boolean isMasterTeacher() {
        return role == Role.MASTER_TEACHER;
    }
}
