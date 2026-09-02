package com.aditya_nk.blogcms.dto.ai;

import lombok.Builder;
import lombok.Getter;

/**
 * Immediate 202 body every dispatch endpoint returns. Generic across job
 * types on purpose — /outline, /post, etc. reuse this same shape once
 * they're migrated, instead of each endpoint inventing its own envelope.
 */
@Getter
@Builder
public class AIJobResponse {

    private String jobId;
    private String status;
}
