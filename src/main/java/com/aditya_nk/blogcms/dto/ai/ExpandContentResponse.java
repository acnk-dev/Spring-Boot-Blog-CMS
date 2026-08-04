package com.aditya_nk.blogcms.dto.ai;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ExpandContentResponse {

    private String expandedContent;
    private int originalWordCount;
    private int expandedWordCount;
    private String generatedAt;
}

