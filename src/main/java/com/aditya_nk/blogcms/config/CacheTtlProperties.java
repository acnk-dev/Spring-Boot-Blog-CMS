package com.aditya_nk.blogcms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "cache.ttl")
@Data
public class CacheTtlProperties {
    private long posts = 300;
    private long categories = 600;
    private long tags = 600;
    private long defaultTtl = 120;
}
