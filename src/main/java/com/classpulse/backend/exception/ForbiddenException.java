package com.classpulse.backend.exception;

import org.springframework.http.HttpStatus;

/**
 * Exception indicating that the authenticated client lacks sufficient rights
 * to perform the operation on the specified resource (HTTP 403 Forbidden).
 * <p>
 * Typical triggers:
 * <ul>
 *   <li>A teacher attempting to modify a session owned by another teacher.</li>
 *   <li>A participant attempting teacher-only actions (e.g., launching activities).</li>
 *   <li>A participant possessing a token for Session A attempting to submit responses into Session B.</li>
 * </ul>
 */
public class ForbiddenException extends ApiException {

    /**
     * Constructs a {@link ForbiddenException} with an authorization denial explanation.
     *
     * @param message description of why access is forbidden to this resource
     */
    public ForbiddenException(String message) {
        // Delegates to parent ApiException with HTTP 403 FORBIDDEN
        super(HttpStatus.FORBIDDEN, message);
    }
}
