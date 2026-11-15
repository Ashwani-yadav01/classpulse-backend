package com.classpulse.backend.exception;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Standardized JSON error response payload returned by all REST endpoints
 * whenever a request encounters an exception or fails validation.
 * <p>
 * Follows RFC 7807 problem details conventions with timestamp, HTTP status,
 * error category, message, request URI path, and optional granular field details.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {

    /**
     * Exact UTC timestamp when the exception was intercepted and transformed into an error response.
     */
    private Instant timestamp;

    /**
     * HTTP numerical status code (e.g. 400, 401, 403, 404, 409, 410, 500).
     */
    private int status;

    /**
     * Standard HTTP reason phrase accompanying the status code (e.g., "Bad Request", "Not Found").
     */
    private String error;

    /**
     * Human-readable, descriptive explanation of the specific error condition encountered.
     */
    private String message;

    /**
     * The URI endpoint path on which the offending request arrived (e.g. "/api/v1/sessions/join").
     */
    private String path;

    /**
     * Optional list of granular validation failure messages (e.g., specific field validation errors).
     */
    private List<String> details;
}
