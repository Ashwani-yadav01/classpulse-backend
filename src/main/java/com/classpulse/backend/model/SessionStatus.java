package com.classpulse.backend.model;

/**
 * Enumeration representing the operational lifecycle stages of a classroom {@link Session}.
 */
public enum SessionStatus {
    /**
     * Session is live and accessible. Students can join using the 6-character code,
     * and teachers can create/activate activities.
     */
    ACTIVE,

    /**
     * Session was explicitly closed or ended by the instructor.
     * New student joins and answer submissions are permanently rejected.
     */
    CLOSED,

    /**
     * Session reached its configured temporal TTL expiration boundary.
     */
    EXPIRED
}
