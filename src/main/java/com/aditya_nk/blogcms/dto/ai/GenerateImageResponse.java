package com.aditya_nk.blogcms.dto.ai;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class GenerateImageResponse {

    private String imageUrl;
    private String generatedPrompt;
    private String generatedAt;
}

