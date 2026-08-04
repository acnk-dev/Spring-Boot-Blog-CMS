package com.aditya_nk.blogcms.dto.ai;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class GeneratePostResponse {

    private String title;
    private String content;
    private String excerpt;
    private List<String> suggestedTags;
    private String generatedAt;
}

