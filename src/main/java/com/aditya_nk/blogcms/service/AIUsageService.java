package com.aditya_nk.blogcms.service;

import com.aditya_nk.blogcms.dto.ai.AIUsageResponse;
import com.aditya_nk.blogcms.enums.Role;

public interface AIUsageService {

    void checkAndIncrementUsage(Long userId, Role role);

    AIUsageResponse getUsage(Long userId, Role role);
}

