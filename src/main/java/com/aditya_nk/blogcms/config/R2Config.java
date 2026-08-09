package com.aditya_nk.blogcms.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Type-safe configuration properties for Cloudflare R2 object storage.
 *
 * Bound from application.properties using the "r2" prefix:
 *
 *   r2.account-id=...
 *   r2.access-key-id=...
 *   r2.secret-access-key=...
 *   r2.bucket-name=...
 *   r2.public-url=...
 *
 * Only active under the "prod" profile, where R2FileStorageServiceImpl
 * replaces LocalFileStorageServiceImpl as the FileStorageService bean.
 */
@Configuration
@ConfigurationProperties(prefix = "r2")
@Getter
@Setter
public class R2Config {

    /**
     * Cloudflare account ID — used to build the R2 S3 API endpoint:
     * https://{accountId}.r2.cloudflarestorage.com
     */
    private String accountId;

    /**
     * R2 API token access key ID.
     */
    private String accessKeyId;

    /**
     * R2 API token secret access key.
     */
    private String secretAccessKey;

    /**
     * Name of the R2 bucket that stores post images and avatars.
     */
    private String bucketName;

    /**
     * Public base URL the bucket is served from — either the r2.dev dev
     * subdomain or a custom domain mapped to the bucket. Used to build the
     * URL returned by R2FileStorageServiceImpl#storeFile, e.g.
     * "https://cdn.example.com/posts/uuid.jpg".
     */
    private String publicUrl;
}
