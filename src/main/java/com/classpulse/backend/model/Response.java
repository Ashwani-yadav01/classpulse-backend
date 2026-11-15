package com.classpulse.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity representing a participant's submission to an {@link Activity}.
 * <p>
 * Unifies various answer modalities:
 * <ul>
 *   <li>Option-based: {@code selectedOption} for MCQ and Poll.</li>
 *   <li>Text-based: {@code textResponse} and case-folded {@code normalizedText} for Word Clouds and Open Questions.</li>
 *   <li>Rank-based: {@code prioritizationRanks} collection for Prioritization activities.</li>
 * </ul>
 */
@Entity
@Table(name = "responses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Response {

    /**
     * Unique surrogate primary key UUID for the submitted response.
     */
    @Id
    private UUID id;

    /**
     * The target {@link Activity} this response answers.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "activity_id", nullable = false)
    private Activity activity;

    /**
     * The {@link Participant} who submitted this response.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id", nullable = false)
    private Participant participant;

    /**
     * Selected {@link ActivityOption} for discrete choice activities (MCQ, Poll).
     * Null for text or ranked responses.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "selected_option_id", nullable = true)
    private ActivityOption selectedOption;

    /**
     * Raw textual submission for Word Cloud, Open Question, and Brainstorming activities.
     */
    @Column(name = "text_response", length = 1000)
    private String textResponse;

    /**
     * Lowercased, trimmed, and normalized version of {@code textResponse}
     * used for accurate case-insensitive frequency clustering in Word Cloud aggregation.
     */
    @Column(name = "normalized_text", length = 1000)
    private String normalizedText;

    /**
     * Ordered rank placements assigned by the participant for Prioritization activities.
     * Managed with orphan removal and cascade lifecycle.
     */
    @OneToMany(mappedBy = "response", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private java.util.List<PrioritizationRank> prioritizationRanks = new java.util.ArrayList<>();

    /**
     * UTC timestamp when the response was initially submitted.
     */
    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    /**
     * UTC timestamp of the most recent revision/update to this response.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
