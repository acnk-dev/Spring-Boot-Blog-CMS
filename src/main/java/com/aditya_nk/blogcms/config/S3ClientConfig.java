package com.aditya_nk.blogcms.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * Wires an AWS S3 SDK client to Cloudflare R2's S3-compatible endpoint.
 *
 * R2 speaks the S3 API but is not AWS — three settings distinguish an R2
 * client from a real AWS S3 client:
 *   1. endpointOverride  — points at R2 instead of s3.amazonaws.com
 *   2. region             — R2 has no regions; "auto" is required by the SDK
 *      but ignored by R2 itself
 *   3. credentials        — R2 API token, not an AWS IAM key pair
 *
 * Only active under the "prod" profile — this bean, and the client it
 * produces, are unused (and unnecessary) when running locally against disk
 * storage via LocalFileStorageServiceImpl.
 */
@Configuration
@Profile("prod")
@RequiredArgsConstructor
public class S3ClientConfig {

    private final R2Config r2Config;

    @Bean
    public S3Client r2Client() {
        String endpoint = "https://" + r2Config.getAccountId() + ".r2.cloudflarestorage.com";

        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                r2Config.getAccessKeyId(),
                                r2Config.getSecretAccessKey())))
                .build();
    }
}
