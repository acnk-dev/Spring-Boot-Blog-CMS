package com.aditya_nk.blogcms.exception;

/**
 * Exception thrown when authentication fails or is missing.
 * Results in 401 Unauthorized HTTP response.
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }

    public UnauthorizedException() {
        super("Authentication required");
    }
}
