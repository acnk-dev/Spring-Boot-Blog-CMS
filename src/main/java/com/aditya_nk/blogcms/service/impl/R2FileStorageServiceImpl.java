package com.aditya_nk.blogcms.service.impl;

import com.aditya_nk.blogcms.config.FileStorageConfig;
import com.aditya_nk.blogcms.config.R2Config;
import com.aditya_nk.blogcms.exception.BadRequestException;
import com.aditya_nk.blogcms.exception.FileStorageException;
import com.aditya_nk.blogcms.exception.ResourceNotFoundException;
import com.aditya_nk.blogcms.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.UUID;

/**
 * Cloudflare R2 implementation of FileStorageService.
 *
 * R2 is S3-compatible object storage, so this talks to it through the AWS
 * S3 SDK (see S3ClientConfig for the endpoint override that points the
 * client at R2 instead of AWS). Active only under the "prod" profile —
 * LocalFileStorageServiceImpl handles every other profile, including the
 * default no-profile case.
 *
 * The bucket is public (see plans/r2-file-storage-integration.md for the
 * public-bucket-vs-presigned-URL trade-off), so storeFile() returns a full,
 * permanent public URL rather than a relative path — unlike
 * LocalFileStorageServiceImpl, which returns a path served through this
 * app's own endpoint.
 */
@Service
@Profile("prod")
@RequiredArgsConstructor
@Slf4j
public class R2FileStorageServiceImpl implements FileStorageService {

    private final S3Client r2Client;
    private final R2Config r2Config;
    private final FileStorageConfig fileStorageConfig;

    /**
     * {@inheritDoc}
     *
     * Steps:
     * 1. Validate the file (type, size, not empty)
     * 2. Generate a UUID-based filename to prevent collisions
     * 3. Upload to R2 under {directory}/{uniqueFileName}
     * 4. Return the full public URL
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

        // 2. Generate unique filename and object key
        String uniqueFileName = generateUniqueFileName(file.getOriginalFilename());
        String safeDirectory = sanitizeDirectory(directory);
        String key = safeDirectory + "/" + uniqueFileName;

        // 3. Upload to R2
        try {
            r2Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(r2Config.getBucketName())
                            .key(key)
                            .contentType(file.getContentType())
                            .build(),
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
            log.info("Stored file in R2: {} ({} bytes)", key, file.getSize());
        } catch (IOException | S3Exception e) {
            throw new FileStorageException("Failed to store file " + uniqueFileName, e);
        }

        // 4. Return the full public URL
        return r2Config.getPublicUrl() + "/" + key;
    }

    /**
     * {@inheritDoc}
     *
     * Deletion is idempotent — R2 (like S3) returns success even if the key
     * does not exist, so no existence check is needed beforehand.
     */
    @Override
    public void deleteFile(String filePath) {
        if (!StringUtils.hasText(filePath)) {
            return;
        }

        String key = keyFromFilePath(filePath);

        try {
            r2Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(r2Config.getBucketName())
                    .key(key)
                    .build());
            log.info("Deleted file from R2: {}", key);
        } catch (S3Exception e) {
            throw new FileStorageException("Failed to delete file: " + filePath, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * Loads the object through its public URL as a {@link UrlResource} —
     * the bucket is public, so no signed request is needed to read it.
     */
    @Override
    public Resource loadFileAsResource(String filePath) {
        String key = keyFromFilePath(filePath);
        String url = r2Config.getPublicUrl() + "/" + key;

        try {
            Resource resource = new UrlResource(url);
            if (resource.exists() && resource.isReadable()) {
                return resource;
            }
            throw new ResourceNotFoundException("File", "path", filePath);
        } catch (MalformedURLException e) {
            throw new FileStorageException("Cannot load file: " + filePath, e);
        } catch (NoSuchKeyException e) {
            throw new ResourceNotFoundException("File", "path", filePath);
        }
    }

    /**
     * {@inheritDoc}
     *
     * Identical logic to LocalFileStorageServiceImpl — validation rules are
     * about the file itself, not the storage backend.
     */
    @Override
    public boolean isValidImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return false;
        }

        String contentType = file.getContentType();
        if (contentType == null ||
                !fileStorageConfig.getAllowedTypes().contains(contentType.toLowerCase())) {
            return false;
        }

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

    private String getExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return (dotIndex >= 0) ? fileName.substring(dotIndex + 1) : "";
    }

    private String sanitizeDirectory(String directory) {
        if (!StringUtils.hasText(directory)) {
            return "misc";
        }
        return directory.replaceAll("[./\\\\]", "").trim();
    }

    /**
     * filePath may be either a bare object key ("posts/uuid.jpg") or a full
     * public URL previously returned by storeFile ("https://cdn.../posts/uuid.jpg").
     * Callers (e.g. PostServiceImpl) persist whatever storeFile returned, so
     * both forms must resolve back to the same R2 key.
     */
    private String keyFromFilePath(String filePath) {
        String prefix = r2Config.getPublicUrl() + "/";
        return filePath.startsWith(prefix) ? filePath.substring(prefix.length()) : filePath;
    }
}
