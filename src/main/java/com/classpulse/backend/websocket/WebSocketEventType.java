package com.classpulse.backend.websocket;

/**
 * Constants defining the real-time event types broadcast across STOMP WebSocket topics.
 * <p>
 * Front-end clients subscribe to session topics ({@code /topic/sessions/{sessionId}})
 * or activity topics ({@code /topic/sessions/{sessionId}/activities/{activityId}})
 * and dispatch UI state transitions based on these event types.
 */
public final class WebSocketEventType {

    /** Fired when an instructor switches an activity from DRAFT to ACTIVE. */
    public static final String ACTIVITY_ACTIVATED = "ACTIVITY_ACTIVATED";

    /** Fired when an instructor stops/closes an active activity. */
    public static final String ACTIVITY_CLOSED = "ACTIVITY_CLOSED";

    /** Fired when a participant submits a new or revised answer. */
    public static final String RESPONSE_SUBMITTED = "RESPONSE_SUBMITTED";

    /** Fired when aggregated results (charts, percentages) are recalculated. */
    public static final String RESULTS_UPDATED = "RESULTS_UPDATED";

    /** Fired when a Word Cloud receives a new word submission. */
    public static final String WORD_CLOUD_UPDATED = "WORD_CLOUD_UPDATED";

    /** Fired when a new brainstorming idea is posted by a student. */
    public static final String BRAINSTORMING_IDEA_ADDED = "BRAINSTORMING_IDEA_ADDED";

    /** Fired when ranked prioritization totals are updated. */
    public static final String PRIORITIZATION_UPDATED = "PRIORITIZATION_UPDATED";

    /** Fired when a student is selected by the Random Student Picker. */
    public static final String RANDOM_PICKER_SELECTED = "RANDOM_PICKER_SELECTED";

    /** Fired when the teacher toggles whether students can view results. */
    public static final String RESULT_VISIBILITY_CHANGED = "RESULT_VISIBILITY_CHANGED";

    /** Fired when the teacher concludes or closes the entire classroom session. */
    public static final String SESSION_CLOSED = "SESSION_CLOSED";

    /** Fired when a session is completely deleted. */
    public static final String SESSION_DELETED = "SESSION_DELETED";

    /** Fired when an activity is removed from the session queue. */
    public static final String ACTIVITY_DELETED = "ACTIVITY_DELETED";

    /** Fired when a new participant joins the session. */
    public static final String PARTICIPANT_JOINED = "PARTICIPANT_JOINED";

    /** Fired when a participant's session access is revoked. */
    public static final String PARTICIPANT_REVOKED = "PARTICIPANT_REVOKED";

    /**
     * Private constructor preventing instantiation of constant carrier class.
     */
    private WebSocketEventType() {}
}
