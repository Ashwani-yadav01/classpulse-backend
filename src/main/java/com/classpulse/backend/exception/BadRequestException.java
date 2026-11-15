package com.classpulse.backend.exception;

import org.springframework.http.HttpStatus;

/**
 * Exception indicating client-side semantic or syntactic invalidity (HTTP 400 Bad Request).
 * <p>
 * Thrown when:
 * <ul>
 *   <li>Incoming payload fails custom business validations (e.g. invalid activity options).</li>
 *   <li>Invalid request parameters or formats are supplied.</li>
 *   <li>Illegal state transitions are attempted on resources (e.g. activating a closed session).</li>
 * </ul>
 */
public class BadRequestException extends ApiException {

    /**
     * Constructs a {@link BadRequestException} with a custom failure message.
     *
     * @param message the validation or malformed request explanation
     */
    public BadRequestException(String message) {
        // Initializes parent ApiException with HTTP 400 BAD_REQUEST and the given explanation
        super(HttpStatus.BAD_REQUEST, message);
    }
}
