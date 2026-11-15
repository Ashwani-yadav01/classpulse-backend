package com.classpulse.backend.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

/**
 * Centralized REST API exception handler intercepting errors across all Spring MVC controllers.
 * <p>
 * Ensures that all client-facing failures return a uniform {@link ErrorResponse} JSON body
 * with predictable HTTP status codes, structured logging, and without leaking internal stack traces.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handles custom application-level {@link ApiException} instances.
     * Maps the internal domain status directly to the outgoing HTTP response status.
     *
     * @param ex      the intercepted domain ApiException
     * @param request the current HTTP servlet request
     * @return a {@link ResponseEntity} containing a structured {@link ErrorResponse}
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex, HttpServletRequest request) {
        // Log the endpoint and warning message for operational monitoring
        log.warn("API Exception on {}: {}", request.getRequestURI(), ex.getMessage());

        // Construct standardized error response entity
        ErrorResponse response = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(ex.getStatus().value())
                .error(ex.getStatus().getReasonPhrase())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .build();

        // Return HTTP response with status matched to the ApiException definition
        return ResponseEntity.status(ex.getStatus()).body(response);
    }

    /**
     * Handles standard Spring bean validation failures triggered by {@code @Valid} annotations.
     * Extracts all field errors into an ordered list of detail messages.
     *
     * @param ex      the validation exception containing binding errors
     * @param request the current HTTP servlet request
     * @return HTTP 400 Bad Request with field-by-field error details
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex, HttpServletRequest request) {
        // Extract default messages from all rejected field validations
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .toList();

        // Assemble error payload indicating validation failure
        ErrorResponse response = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .message("Validation failed")
                .path(request.getRequestURI())
                .details(details)
                .build();

        // Return 400 Bad Request
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handles missing mandatory HTTP request headers (e.g. required {@code X-Participant-Token}).
     *
     * @param ex      the missing header exception
     * @param request the current HTTP servlet request
     * @return HTTP 400 Bad Request indicating which header was omitted
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeaderException(MissingRequestHeaderException ex, HttpServletRequest request) {
        // Assemble error response highlighting missing header name
        ErrorResponse response = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .message("Missing required header: " + ex.getHeaderName())
                .path(request.getRequestURI())
                .build();

        // Return 400 Bad Request
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handles JSON syntax or deserialization errors when reading request bodies.
     *
     * @param ex      the message unreadable exception
     * @param request the current HTTP servlet request
     * @return HTTP 400 Bad Request alerting client of malformed JSON
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMessageNotReadableException(HttpMessageNotReadableException ex, HttpServletRequest request) {
        // Assemble error response for syntax parsing failure
        ErrorResponse response = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .message("Malformed JSON request body")
                .path(request.getRequestURI())
                .build();

        // Return 400 Bad Request
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handles Spring Security {@link AccessDeniedException} when a client has insufficient authorities.
     *
     * @param ex      the access denied exception
     * @param request the current HTTP servlet request
     * @return HTTP 403 Forbidden
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDeniedException(AccessDeniedException ex, HttpServletRequest request) {
        // Log access denial warning
        log.warn("Access denied on {}: {}", request.getRequestURI(), ex.getMessage());

        // Construct 403 response
        ErrorResponse response = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(HttpStatus.FORBIDDEN.value())
                .error(HttpStatus.FORBIDDEN.getReasonPhrase())
                .message(ex.getMessage() != null ? ex.getMessage() : "Access is denied")
                .path(request.getRequestURI())
                .build();

        // Return 403 Forbidden
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    /**
     * Fallback catcher for all unanticipated runtime and checked exceptions.
     * Prevents internal technical information or stack traces from leaking to clients.
     *
     * @param ex      the unexpected throwable
     * @param request the current HTTP servlet request
     * @return HTTP 500 Internal Server Error with a sanitized generic message
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(Exception ex, HttpServletRequest request) {
        // Log full stack trace server-side for troubleshooting
        log.error("Unhandled exception processing request to {}", request.getRequestURI(), ex);

        // Construct sanitized 500 response
        ErrorResponse response = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .error(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase())
                .message("An unexpected internal error occurred. Please try again later.")
                .path(request.getRequestURI())
                .build();

        // Return 500 Internal Server Error
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
