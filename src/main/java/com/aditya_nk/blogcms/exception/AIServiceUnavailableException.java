package com.aditya_nk.blogcms.exception;

/**
 * Thrown when the AI service dispatch call fails (down, timeout, non-2xx).
 * Results in a 503 Service Unavailable HTTP response via GlobalExceptionHandler.
 *
 * Distinct from a failed AI generation itself — a Gemini failure surfaces as
 * a FAILED job status on the polling path, not as this exception. This
 * exception is only about the dispatch call (monolith -> AI service) not
 * succeeding in the first place.
 */
public class AIServiceUnavailableException extends RuntimeException {

    public AIServiceUnavailableException(String message) {
        super(message);
    }

    public AIServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
