package com.classpulse.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity representing a live or historical classroom session.
 * <p>
 * A session acts as the organizational boundary for:
 * <ul>
 *   <li>The 6-character unique join code used by students to enter.</li>
 *   <li>The teacher's cryptographic token hash for session management.</li>
 *   <li>Lifecycle state management (ACTIVE, CLOSED, EXPIRED).</li>
 *   <li>Associated activities, participants, and real-time WebSocket communication channels.</li>
 * </ul>
 */
@Entity
@Table(name = "sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Session {

    /**
     * Unique surrogate primary key UUID for the session.
     */
    @Id
    private UUID id;

    /**
     * Human-readable title or subject of the classroom session (e.g. "Calculus 101 - Lecture 4").
     */
    @Column(nullable = false)
    private String title;

    /**
     * Unique 6-character alphanumeric join code (e.g. "AB12CD") entered by students to discover this session.
     */
    @Column(name = "join_code", nullable = false, unique = true, length = 16)
    private String joinCode;

    /**
     * Current lifecycle status of the session (ACTIVE, CLOSED, or EXPIRED).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SessionStatus status;

    /**
     * SHA-256 cryptographic hash of the teacher token.
     * The raw token is issued to the creator upon session creation and verified on privileged endpoints.
     */
    @Column(name = "teacher_token_hash", nullable = false, length = 64)
    private String teacherTokenHash;

    /**
     * UTC timestamp when the session was created.
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * UTC timestamp after which the session automatically expires if not closed earlier.
     */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * UTC timestamp when the instructor explicitly closed the session, or null if still active.
     */
    @Column(name = "closed_at")
    private Instant closedAt;

    /**
     * Optional foreign key relationship to the registered teacher who owns this session.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id")
    private User teacher;

    /**
     * Checks if the session has reached an expired state either via its status flag
     * or by comparison against the current clock timestamp.
     *
     * @param now Current instant provided by the centralized application clock
     * @return true if the session is logically expired, false otherwise
     */
    public boolean isExpired(Instant now) {
        // Return true if status is explicitly marked EXPIRED or current time is past the expiry instant
        return status == SessionStatus.EXPIRED || (expiresAt != null && now.isAfter(expiresAt));
    }
}
