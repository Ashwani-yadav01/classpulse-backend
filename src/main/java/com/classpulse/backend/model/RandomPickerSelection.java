package com.classpulse.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity recording a selection event within a Random Student Picker {@link Activity}.
 * <p>
 * Maintains an audit trail of which participant was picked and when, enabling the system
 * to exclude previously picked students on subsequent rounds when configured to do so.
 */
@Entity
@Table(name = "random_picker_selections")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RandomPickerSelection {

    /**
     * Unique surrogate primary key UUID for the selection record.
     */
    @Id
    private UUID id;

    /**
     * The Random Picker {@link Activity} within which this selection occurred.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "activity_id", nullable = false)
    private Activity activity;

    /**
     * The {@link Participant} who was randomly selected.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id", nullable = false)
    private Participant participant;

    /**
     * UTC timestamp when this selection was generated.
     */
    @Column(name = "picked_at", nullable = false)
    private Instant pickedAt;
}
