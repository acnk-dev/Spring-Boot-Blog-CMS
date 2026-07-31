package com.aditya_nk.blogcms.exception;

/**
 * Exception thrown when a file storage operation fails at the I/O level.
 * Results in a 500 Internal Server Error HTTP response via GlobalExceptionHandler.
 *
 * Distinct from BadRequestException (client error) — this exception signals
 * an infrastructure problem the client cannot fix (disk full, permission denied).
 */
public class FileStorageException extends RuntimeException {

    public FileStorageException(String message) {
        super(message);
    }

    public FileStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}

