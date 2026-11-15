package com.classpulse.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * JPA entity capturing the explicit 1-based rank assigned to an {@link ActivityOption}
 * within a participant's Prioritization {@link Response}.
 * <p>
 * Database constraints enforce uniqueness:
 * <ul>
 *   <li>{@code uq_prioritization_response_option}: Each option can be ranked only once per submission.</li>
 *   <li>{@code uq_prioritization_response_rank}: Each numerical rank position can be assigned only once per submission.</li>
 * </ul>
 */
@Entity
@Table(name = "prioritization_ranks", uniqueConstraints = {
        @UniqueConstraint(name = "uq_prioritization_response_option", columnNames = {"response_id", "option_id"}),
        @UniqueConstraint(name = "uq_prioritization_response_rank", columnNames = {"response_id", "rank_order"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrioritizationRank {

    /**
     * Unique surrogate primary key UUID for the ranking record.
     */
    @Id
    private UUID id;

    /**
     * Parent {@link Response} this item ranking belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "response_id", nullable = false)
    private Response response;

    /**
     * The specific {@link ActivityOption} being ranked.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "option_id", nullable = false)
    private ActivityOption option;

    /**
     * The 1-based ranking position chosen by the participant (1 = highest priority).
     */
    @Column(name = "rank_order", nullable = false)
    private int rankOrder;
}
