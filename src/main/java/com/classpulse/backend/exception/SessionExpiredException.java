package com.classpulse.backend.exception;

import org.springframework.http.HttpStatus;

public class SessionExpiredException extends ApiException {
    public SessionExpiredException(String message) {
        super(HttpStatus.GONE, message);
    }
}
