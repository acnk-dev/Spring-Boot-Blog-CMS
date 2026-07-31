package com.aditya_nk.blogcms.service;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/**
 * Service interface for file storage operations.
 *
 * Defines the contract for storing and retrieving uploaded files.
 * The interface abstracts the storage backend, making it possible to swap
 * LocalFileStorageServiceImpl for an S3-backed implementation without
 * changing any caller code.
 *
 * Current implementation: local disk storage (LocalFileStorageServiceImpl)
 * Future implementation: AWS S3 (S3FileStorageServiceImpl)
 */
public interface FileStorageService {

    /**
     * Store a file in the given subdirectory and return its relative path.
     *
     * The returned path is relative to the configured upload root
     * (e.g., "posts/550e8400-e29b-41d4-a716-446655440000.jpg").
     * Callers should persist this path and later serve it via a static
     * resource endpoint or a signed URL.
     *
     * @param file      the multipart file to store
     * @param directory the subdirectory to store the file in (e.g., "posts", "avatars")
     * @return relative file path from the upload root
     * @throws com.studylink.blogcms.exception.BadRequestException if validation fails
     * @throws com.studylink.blogcms.exception.FileStorageException if writing fails
     */
    String storeFile(MultipartFile file, String directory);

    /**
     * Delete a previously stored file.
     *
     * @param filePath relative path returned by {@link #storeFile}
     * @throws com.studylink.blogcms.exception.FileStorageException if deletion fails
     */
    void deleteFile(String filePath);

    /**
     * Load a stored file as a Spring {@link Resource} for streaming to a client.
     *
     * @param filePath relative path returned by {@link #storeFile}
     * @return readable Resource pointing to the file
     * @throws com.studylink.blogcms.exception.ResourceNotFoundException if the file doesn't exist
     */
    Resource loadFileAsResource(String filePath);

    /**
     * Check whether a multipart file is a valid image.
     *
     * Validates both the declared MIME type and the file extension.
     * Does NOT inspect the file's magic bytes — that is intentionally
     * left to a future security hardening task.
     *
     * @param file the file to check
     * @return true if the file passes all image validation rules
     */
    boolean isValidImage(MultipartFile file);

    /**
     * Generate a unique, filesystem-safe filename from an original filename.
     *
     * Produces a UUID v4 name with the original extension preserved,
     * e.g., "550e8400-e29b-41d4-a716-446655440000.jpg".
     *
     * @param originalFileName the client-supplied filename
     * @return unique filename safe for storage
     */
    String generateUniqueFileName(String originalFileName);
}

