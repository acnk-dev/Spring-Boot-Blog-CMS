package com.aditya_nk.blogcms.dto.ai;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AIUsageResponse {

    private int used;
    private int limit;
    private int remaining;
    private String resetsAt;
}

