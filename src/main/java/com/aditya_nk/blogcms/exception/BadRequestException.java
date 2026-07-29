package com.aditya_nk.blogcms.exception;

/**
 * Exception thrown when request data is invalid.
 * Results in 400 Bad Request HTTP response.
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
