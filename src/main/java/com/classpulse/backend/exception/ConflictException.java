package com.classpulse.backend.exception;

import org.springframework.http.HttpStatus;

/**
 * Exception indicating a state conflict with current target resource data (HTTP 409 Conflict).
 * <p>
 * Typical triggers:
 * <ul>
 *   <li>Duplicate user registration email.</li>
 *   <li>Duplicate participant display name inside an active session.</li>
 *   <li>Submitting duplicate responses when an activity restricts answers to single submissions.</li>
 * </ul>
 */
public class ConflictException extends ApiException {

    /**
     * Constructs a {@link ConflictException} with a detailed explanation of the conflicting state.
     *
     * @param message the conflicting state description
     */
    public ConflictException(String message) {
        // Delegates to ApiException with HTTP 409 CONFLICT
        super(HttpStatus.CONFLICT, message);
    }
}
