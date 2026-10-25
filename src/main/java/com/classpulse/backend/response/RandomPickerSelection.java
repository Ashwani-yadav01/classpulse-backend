package com.classpulse.backend.response;

import com.classpulse.backend.activity.Activity;
import com.classpulse.backend.participant.Participant;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "random_picker_selections")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RandomPickerSelection {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "activity_id", nullable = false)
    private Activity activity;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "participant_id", nullable = false)
    private Participant participant;

    @Column(name = "picked_at", nullable = false)
    private Instant pickedAt;
}
