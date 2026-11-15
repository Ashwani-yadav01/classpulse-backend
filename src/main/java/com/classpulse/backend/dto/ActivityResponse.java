package com.classpulse.backend.dto;

import com.classpulse.backend.model.ActivityStatus;
import com.classpulse.backend.model.ActivityType;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Data Transfer Object representing an activity's complete details returned by REST and WebSocket APIs.
 * <p>
 * Carries metadata, operational lifecycle state, dynamic configurations, queue ordering,
 * and associated choices/options.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ActivityResponse {

    /**
     * Unique surrogate primary key UUID of the activity.
     */
    private UUID id;

    /**
     * UUID of the parent session to which this activity belongs.
     */
    private UUID sessionId;

    /**
     * Module category (MCQ, POLL, WORD_CLOUD, OPEN_QUESTION, RANDOM_PICKER, BRAINSTORMING, PRIORITIZATION).
     */
    private ActivityType type;

    /**
     * Title, question prompt, or topic of the activity.
     */
    private String title;

    /**
     * Current lifecycle status of the activity (DRAFT, ACTIVE, CLOSED).
     */
    private ActivityStatus status;

    /**
     * Raw serialized JSON string of activity-specific configuration settings.
     */
    private String configJson;

    /**
     * Zero-based presentation sequence position in the classroom activity queue.
     */
    private Integer orderIndex;

    /**
     * UTC timestamp when this activity was created.
     */
    private Instant createdAt;

    /**
     * UTC timestamp when the activity transitioned into the ACTIVE state, or null if never activated.
     */
    private Instant activatedAt;

    /**
     * UTC timestamp when the activity transitioned into the CLOSED state, or null if open/draft.
     */
    private Instant closedAt;

    /**
     * Ordered list of selectable choices configured for this activity (e.g. for MCQ, Poll, Prioritization).
     */
    @Builder.Default
    private List<ActivityOptionDto> options = new ArrayList<>();
}
