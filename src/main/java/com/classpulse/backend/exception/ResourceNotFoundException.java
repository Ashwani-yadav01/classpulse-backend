package com.classpulse.backend.exception;

import org.springframework.http.HttpStatus;

/**
 * Exception indicating that an origin server did not find a current representation
 * for the requested target resource (HTTP 404 Not Found).
 * <p>
 * Typical triggers:
 * <ul>
 *   <li>Session not found matching a provided UUID or 6-character alphanumeric join code.</li>
 *   <li>Activity not found for the supplied session ID and activity ID.</li>
 *   <li>Participant not found matching a session token or participant ID.</li>
 *   <li>User not found for a given email address.</li>
 * </ul>
 */
public class ResourceNotFoundException extends ApiException {

    /**
     * Constructs a {@link ResourceNotFoundException} with the missing resource explanation.
     *
     * @param message description of the resource that was looked up but not found
     */
    public ResourceNotFoundException(String message) {
        // Delegates to parent ApiException with HTTP 404 NOT_FOUND
        super(HttpStatus.NOT_FOUND, message);
    }
}
