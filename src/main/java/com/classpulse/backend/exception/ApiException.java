package com.classpulse.backend.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base custom unchecked exception for all domain and API-level errors in ClassPulse.
 * <p>
 * Encapsulates an explicit HTTP status code alongside a human-readable error message.
 * When thrown anywhere within controller or service execution paths, it is captured
 * by {@link GlobalExceptionHandler} to generate structured HTTP JSON error responses.
 */
@Getter
public class ApiException extends RuntimeException {

    /**
     * The targeted HTTP response status (e.g., 400 BAD_REQUEST, 404 NOT_FOUND).
     */
    private final HttpStatus status;

    /**
     * Constructs a new API exception with a designated HTTP status and explanation message.
     *
     * @param status  the HTTP status code associated with this operational failure
     * @param message the detailed, human-readable error message explaining the cause
     */
    public ApiException(HttpStatus status, String message) {
        // Pass the error message to the parent RuntimeException constructor for stack traces
        super(message);
        // Bind the specific HTTP status code for response mapping
        this.status = status;
    }
}
