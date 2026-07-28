package com.aditya_nk.blogcms.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing
public class JpaConfig {
    // Enables @CreationTimestamp and @UpdateTimestamp
}
