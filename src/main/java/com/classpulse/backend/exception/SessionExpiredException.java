package com.classpulse.backend.exception;

import org.springframework.http.HttpStatus;

/**
 * Exception indicating that access to the target session or activity is permanently unavailable
 * because the session or activity has ended or expired (HTTP 410 Gone).
 * <p>
 * Typical triggers:
 * <ul>
 *   <li>The session's {@code expiresAt} timestamp is in the past.</li>
 *   <li>The session has already been transitioned to status {@code CLOSED}.</li>
 *   <li>An activity's timer has expired and submissions are no longer accepted.</li>
 * </ul>
 */
public class SessionExpiredException extends ApiException {

    /**
     * Constructs a {@link SessionExpiredException} indicating resource obsolescence.
     *
     * @param message explanation describing that the session or activity has expired or closed
     */
    public SessionExpiredException(String message) {
        // Delegates to parent ApiException with HTTP 410 GONE
        super(HttpStatus.GONE, message);
    }
}
