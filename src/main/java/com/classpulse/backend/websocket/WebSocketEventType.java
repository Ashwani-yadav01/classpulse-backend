package com.classpulse.backend.websocket;

public final class WebSocketEventType {
    public static final String ACTIVITY_ACTIVATED = "ACTIVITY_ACTIVATED";
    public static final String ACTIVITY_CLOSED = "ACTIVITY_CLOSED";
    public static final String RESPONSE_SUBMITTED = "RESPONSE_SUBMITTED";
    public static final String RESULTS_UPDATED = "RESULTS_UPDATED";
    public static final String WORD_CLOUD_UPDATED = "WORD_CLOUD_UPDATED";
    public static final String BRAINSTORMING_IDEA_ADDED = "BRAINSTORMING_IDEA_ADDED";
    public static final String PRIORITIZATION_UPDATED = "PRIORITIZATION_UPDATED";
    public static final String RANDOM_PICKER_SELECTED = "RANDOM_PICKER_SELECTED";
    public static final String RESULT_VISIBILITY_CHANGED = "RESULT_VISIBILITY_CHANGED";
    public static final String SESSION_CLOSED = "SESSION_CLOSED";
    public static final String SESSION_DELETED = "SESSION_DELETED";
    public static final String ACTIVITY_DELETED = "ACTIVITY_DELETED";
    public static final String PARTICIPANT_JOINED = "PARTICIPANT_JOINED";
    public static final String PARTICIPANT_REVOKED = "PARTICIPANT_REVOKED";

    private WebSocketEventType() {}
}
