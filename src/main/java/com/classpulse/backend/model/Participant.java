package com.classpulse.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity representing a student participating in a specific classroom {@link Session}.
 * <p>
 * Enforces unique display names within a single session and maintains a cryptographically
 * hashed token for stateless authentication across subsequent participant API and WebSocket interactions.
 */
@Entity
@Table(name = "participants", uniqueConstraints = {
        @UniqueConstraint(name = "uq_session_display_name", columnNames = {"session_id", "display_name"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Participant {

    /**
     * Unique surrogate primary key UUID for the participant record.
     */
    @Id
    private UUID id;

    /**
     * Many-to-One reference to the classroom {@link Session} this participant belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private Session session;

    /**
     * Display name chosen by the participant (e.g. "Alice Smith"), guaranteed unique within the session.
     */
    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    /**
     * SHA-256 hash of the participant's secret access token.
     * The raw token is returned only upon joining the session and presented in HTTP headers / STOMP frames.
     */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    /**
     * Flag indicating whether this participant's access has been revoked (e.g., student kicked by teacher).
     */
    @Column(name = "is_revoked", nullable = false)
    private boolean isRevoked;

    /**
     * UTC timestamp when the student first entered this classroom session.
     */
    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    /**
     * UTC timestamp of the student's most recent interaction or activity heartbeat.
     */
    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    /**
     * Optional link to the registered student {@link User} account if the student was logged in.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private User student;
}
