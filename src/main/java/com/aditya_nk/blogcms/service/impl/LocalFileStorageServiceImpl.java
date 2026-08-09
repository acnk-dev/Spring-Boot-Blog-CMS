package com.aditya_nk.blogcms.service.impl;

import com.aditya_nk.blogcms.config.FileStorageConfig;
import com.aditya_nk.blogcms.exception.BadRequestException;
import com.aditya_nk.blogcms.exception.FileStorageException;
import com.aditya_nk.blogcms.exception.ResourceNotFoundException;
import com.aditya_nk.blogcms.service.FileStorageService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Local disk implementation of FileStorageService.
 *
 * Stores uploaded files in a configurable directory on the host filesystem.
 * Intended for development and single-server deployments.
 * For multi-instance or cloud deployments, swap this for S3FileStorageServiceImpl
 * — no callers need to change because they depend on the FileStorageService interface.
 *
 * Directory layout:
 *   {uploadDir}/
 *   ├── posts/           ← featured images for blog posts
 *   └── avatars/         ← user profile pictures
 *
 * Active under every profile except "prod" (including the default, no
 * profile case) — R2FileStorageServiceImpl replaces this bean only when
 * "prod" is active, so exactly one FileStorageService implementation is
 * ever on the context at a time.
 */
@Service
@Profile("!prod")
@RequiredArgsConstructor
@Slf4j
public class LocalFileStorageServiceImpl implements FileStorageService {

    private final FileStorageConfig fileStorageConfig;

    // Resolved absolute path to the upload root, set once at startup
    private Path uploadRootPath;

    /**
     * Resolve and create the upload root directory once at application startup.
     * Fails fast if the directory cannot be created — better to crash at boot
     * than to fail silently on the first upload request.
     */
    @PostConstruct
    public void init() {
        uploadRootPath = Paths.get(fileStorageConfig.getUploadDir())
                .toAbsolutePath()
                .normalize();

        try {
            Files.createDirectories(uploadRootPath);
            log.info("File storage root initialized at: {}", uploadRootPath);
        } catch (IOException e) {
            throw new FileStorageException(
                    "Cannot create upload directory: " + uploadRootPath, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * Steps:
     * 1. Validate the file (type, size, not empty)
     * 2. Generate a UUID-based filename to prevent collisions
     * 3. Resolve the target directory, creating it if needed
     * 4. Copy the file bytes to disk
     * 5. Return the relative path (directory/filename)
     */
    @Override
    public String storeFile(MultipartFile file, String directory) {
        // 1. Validation
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("File must not be empty");
        }

        if (!isValidImage(file)) {
            throw new BadRequestException(
                    "Invalid file type. Allowed types: " +
                            String.join(", ", fileStorageConfig.getAllowedTypes()));
        }

        if (file.getSize() > fileStorageConfig.getMaxSize()) {
            long maxMb = fileStorageConfig.getMaxSize() / (1024 * 1024);
            throw new BadRequestException(
                    "File size exceeds the maximum allowed size of " + maxMb + " MB");
        }

        // 2. Generate unique filename
        String uniqueFileName = generateUniqueFileName(file.getOriginalFilename());

        // 3. Resolve target directory, guarding against path traversal
        String safeDirectory = sanitizeDirectory(directory);
        Path targetDirectory = uploadRootPath.resolve(safeDirectory).normalize();

        // Ensure the resolved path is still within the upload root
        if (!targetDirectory.startsWith(uploadRootPath)) {
            throw new BadRequestException("Invalid storage directory: " + directory);
        }

        try {
            Files.createDirectories(targetDirectory);
        } catch (IOException e) {
            throw new FileStorageException(
                    "Cannot create storage directory: " + targetDirectory, e);
        }

        // 4. Write to disk
        Path targetPath = targetDirectory.resolve(uniqueFileName);
        try {
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Stored file: {} ({} bytes)", targetPath, file.getSize());
        } catch (IOException e) {
            throw new FileStorageException(
                    "Failed to store file " + uniqueFileName, e);
        }

        // 5. Return relative path (e.g., "posts/550e8400-....jpg")
        return safeDirectory + "/" + uniqueFileName;
    }

    /**
     * {@inheritDoc}
     *
     * Resolves the relative path against the upload root and deletes the file.
     * Does not throw if the file is already gone — deletion is idempotent.
     */
    @Override
    public void deleteFile(String filePath) {
        if (!StringUtils.hasText(filePath)) {
            return;
        }

        Path target = resolveAndValidatePath(filePath);

        try {
            boolean deleted = Files.deleteIfExists(target);
            if (deleted) {
                log.info("Deleted file: {}", target);
            } else {
                log.warn("File not found for deletion (already gone?): {}", target);
            }
        } catch (IOException e) {
            throw new FileStorageException("Failed to delete file: " + filePath, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * Returns a {@link UrlResource} that Spring MVC can stream directly to the
     * HTTP response without loading the entire file into memory.
     */
    @Override
    public Resource loadFileAsResource(String filePath) {
        Path target = resolveAndValidatePath(filePath);

        try {
            Resource resource = new UrlResource(target.toUri());
            if (resource.exists() && resource.isReadable()) {
                return resource;
            }
            throw new ResourceNotFoundException("File", "path", filePath);
        } catch (MalformedURLException e) {
            throw new FileStorageException("Cannot load file: " + filePath, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * Checks both the declared Content-Type and the file extension.
     * Note: MIME type can be spoofed by the client. For production hardening,
     * add magic-byte inspection (Apache Tika) to prevent disguised executables.
     */
    @Override
    public boolean isValidImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return false;
        }

        // Check declared MIME type
        String contentType = file.getContentType();
        if (contentType == null ||
                !fileStorageConfig.getAllowedTypes().contains(contentType.toLowerCase())) {
            return false;
        }

        // Cross-check the file extension
        String originalName = file.getOriginalFilename();
        if (!StringUtils.hasText(originalName)) {
            return false;
        }

        String extension = getExtension(originalName).toLowerCase();
        return switch (extension) {
            case "jpg", "jpeg" -> contentType.equals("image/jpeg");
            case "png"         -> contentType.equals("image/png");
            case "gif"         -> contentType.equals("image/gif");
            case "webp"        -> contentType.equals("image/webp");
            default            -> false;
        };
    }

    /**
     * {@inheritDoc}
     *
     * Produces: {@code <UUID>.<original-extension>}
     * e.g., "550e8400-e29b-41d4-a716-446655440000.jpg"
     *
     * If the original filename has no extension, no extension is appended.
     */
    @Override
    public String generateUniqueFileName(String originalFileName) {
        String extension = "";
        if (StringUtils.hasText(originalFileName)) {
            extension = getExtension(originalFileName);
            if (!extension.isEmpty()) {
                extension = "." + extension.toLowerCase();
            }
        }
        return UUID.randomUUID() + extension;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Extract the file extension (without the dot) from a filename.
     * Returns an empty string if no dot is found.
     */
    private String getExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return (dotIndex >= 0) ? fileName.substring(dotIndex + 1) : "";
    }

    /**
     * Strip any path separators from a directory name to prevent path traversal.
     * e.g., "../../etc/passwd" → "etcpasswd" (harmless)
     */
    private String sanitizeDirectory(String directory) {
        if (!StringUtils.hasText(directory)) {
            return "misc";
        }
        // Remove any characters that could construct a relative path attack
        return directory.replaceAll("[./\\\\]", "").trim();
    }

    /**
     * Resolve a relative filePath against the upload root and verify it stays
     * within the root (path traversal guard).
     */
    private Path resolveAndValidatePath(String filePath) {
        Path resolved = uploadRootPath.resolve(filePath).normalize();
        if (!resolved.startsWith(uploadRootPath)) {
            throw new BadRequestException("Invalid file path: " + filePath);
        }
        return resolved;
    }
}

