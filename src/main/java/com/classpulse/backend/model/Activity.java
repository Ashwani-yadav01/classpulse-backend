package com.classpulse.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA entity representing a pedagogical interaction unit (Poll, MCQ, Word Cloud, etc.)
 * within an active or past classroom {@link Session}.
 * <p>
 * Supports queued ordering, dynamic JSON configuration, single-active-activity constraints
 * via {@code active_session_id}, and cascade management of predefined options.
 */
@Entity
@Table(name = "activities")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Activity {

    /**
     * Unique surrogate primary key UUID for the activity.
     */
    @Id
    private UUID id;

    /**
     * Parent {@link Session} to which this activity belongs.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private Session session;

    /**
     * Type/category of the activity (MCQ, POLL, WORD_CLOUD, OPEN_QUESTION, RANDOM_PICKER, etc.).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ActivityType type;

    /**
     * Question prompt, title, or instruction displayed to students (e.g. "What is Big-O complexity?").
     */
    @Column(nullable = false)
    private String title;

    /**
     * Operational state of the activity (DRAFT, ACTIVE, CLOSED).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ActivityStatus status;

    /**
     * Serialized JSON string containing optional configuration options
     * (e.g. {@code showCorrectAnswer}, {@code allowRevision}, {@code resultsVisible}).
     */
    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    /**
     * Zero-based presentation order index for sequentially queued activities within the session.
     */
    @Column(name = "order_index", nullable = false)
    @Builder.Default
    private int orderIndex = 0;

    /**
     * Denormalized session reference populated ONLY when the activity is currently ACTIVE.
     * Enforces a partial unique constraint at the database schema level ensuring only one
     * activity per session can be active simultaneously. Set to null when in DRAFT or CLOSED.
     */
    @Column(name = "active_session_id")
    private UUID activeSessionId;

    /**
     * UTC timestamp when this activity was created.
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * UTC timestamp when this activity was set to ACTIVE, or null if never activated.
     */
    @Column(name = "activated_at")
    private Instant activatedAt;

    /**
     * UTC timestamp when this activity was transitioned to CLOSED.
     */
    @Column(name = "closed_at")
    private Instant closedAt;

    /**
     * Ordered list of predefined choices/options associated with this activity (used for MCQ, Poll, Prioritization).
     * Automatically cascaded on persist/delete and sorted ascending by displayOrder.
     */
    @OneToMany(mappedBy = "activity", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("displayOrder ASC")
    @Builder.Default
    private List<ActivityOption> options = new ArrayList<>();
}
