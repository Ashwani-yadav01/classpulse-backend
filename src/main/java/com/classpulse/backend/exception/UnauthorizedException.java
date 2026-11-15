package com.classpulse.backend.exception;

import org.springframework.http.HttpStatus;

/**
 * Exception indicating missing, malformed, or invalid authentication credentials (HTTP 401 Unauthorized).
 * <p>
 * Typical triggers:
 * <ul>
 *   <li>Invalid email/password combination during teacher login.</li>
 *   <li>Missing Bearer JWT token on protected endpoints.</li>
 *   <li>Expired, revoked, or cryptographically invalid participant token.</li>
 *   <li>Missing participant authentication header ({@code X-Participant-Token}).</li>
 * </ul>
 */
public class UnauthorizedException extends ApiException {

    /**
     * Constructs an {@link UnauthorizedException} with an unauthenticated explanation.
     *
     * @param message description of the credential defect
     */
    public UnauthorizedException(String message) {
        // Delegates to parent ApiException with HTTP 401 UNAUTHORIZED
        super(HttpStatus.UNAUTHORIZED, message);
    }
}
