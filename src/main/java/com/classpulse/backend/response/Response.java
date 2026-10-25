package com.classpulse.backend.response;

import com.classpulse.backend.activity.Activity;
import com.classpulse.backend.activity.ActivityOption;
import com.classpulse.backend.participant.Participant;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "responses", uniqueConstraints = {
        @UniqueConstraint(name = "uq_response_activity_participant",
                columnNames = {"activity_id", "participant_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Response {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "activity_id", nullable = false)
    private Activity activity;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id", nullable = false)
    private Participant participant;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "selected_option_id", nullable = true)
    private ActivityOption selectedOption;

    @Column(name = "text_response", length = 1000)
    private String textResponse;

    @Column(name = "normalized_text", length = 1000)
    private String normalizedText;

    @OneToMany(mappedBy = "response", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private java.util.List<PrioritizationRank> prioritizationRanks = new java.util.ArrayList<>();

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
