package com.classpulse.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

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

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "response_id", nullable = false)
    private Response response;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "option_id", nullable = false)
    private ActivityOption option;

    @Column(name = "rank_order", nullable = false)
    private int rankOrder;
}
