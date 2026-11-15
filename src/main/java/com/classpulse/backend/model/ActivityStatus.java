package com.classpulse.backend.model;

/**
 * Enumeration representing the operational lifecycle states of an {@link Activity}.
 */
public enum ActivityStatus {
    /**
     * The activity has been prepared/configured by the teacher but is not yet open
     * for participant interaction or answer submissions.
     */
    DRAFT,

    /**
     * The activity is currently live in the classroom. Participants can view questions,
     * submit answers, or participate in real-time interactions.
     * Note: Only one activity per session may have activeSessionId set / be ACTIVE at a time.
     */
    ACTIVE,

    /**
     * The activity has been stopped or concluded by the teacher.
     * Submissions are locked and responses can be aggregated for review or leaderboards.
     */
    CLOSED
}
