package com.classpulse.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * JPA entity representing a selectable choice or item belonging to an {@link Activity}.
 * <p>
 * Used across multiple activity types:
 * <ul>
 *   <li>MCQ: Choices with a boolean {@code isCorrect} flag identifying right answers.</li>
 *   <li>POLL: Distinct options that participants vote among.</li>
 *   <li>PRIORITIZATION: Items that participants order from 1 to N.</li>
 * </ul>
 */
@Entity
@Table(name = "activity_options")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActivityOption {

    /**
     * Unique surrogate primary key UUID for the option.
     */
    @Id
    private UUID id;

    /**
     * Parent {@link Activity} this option is linked to.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "activity_id", nullable = false)
    private Activity activity;

    /**
     * Textual content of the option (e.g. "O(log n)" or "Option A").
     * Maximum length is 500 characters.
     */
    @Column(name = "option_text", nullable = false, length = 500)
    private String optionText;

    /**
     * Indicates whether this option represents the correct answer (primarily used in MCQs).
     */
    @Column(name = "is_correct", nullable = false)
    private boolean isCorrect;

    /**
     * Zero-based ordering index determining display sequence in client UIs.
     */
    @Column(name = "display_order", nullable = false)
    private int displayOrder;
}
