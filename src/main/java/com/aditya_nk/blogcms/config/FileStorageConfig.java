package com.aditya_nk.blogcms.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

/**
 * Type-safe configuration properties for file storage.
 *
 * Bound from application.properties using the "file" prefix:
 *
 *   file.upload-dir=uploads
 *   file.max-size=5242880
 *   file.allowed-types=image/jpeg,image/png,image/gif,image/webp
 *
 * Using @ConfigurationProperties instead of @Value gives us:
 * - A single, discoverable place for all file-related settings
 * - IDE auto-completion when editing application.properties
 * - Validation via @Validated (future addition)
 * - Easy mocking in tests (just set POJO fields)
 */
@Configuration
@ConfigurationProperties(prefix = "file")
@Getter
@Setter
public class FileStorageConfig {

    /**
     * Root directory for all uploaded files, relative to the working directory.
     * Defaults to "uploads" so the app works out of the box without configuration.
     * In production, override with an absolute path (e.g., /var/data/blogcms/uploads).
     */
    private String uploadDir = "uploads";

    /**
     * Maximum allowed file size in bytes.
     * Default: 5 MB (5 * 1024 * 1024 = 5,242,880 bytes).
     */
    private long maxSize = 5_242_880L;

    /**
     * Comma-separated list of allowed MIME types.
     * Parsed into a List<String> by Spring's binder automatically.
     */
    private List<String> allowedTypes = Arrays.asList(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp"
    );
}

