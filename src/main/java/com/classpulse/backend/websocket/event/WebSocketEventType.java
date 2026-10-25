package com.classpulse.backend.websocket.event;

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

    private WebSocketEventType() {}
}
