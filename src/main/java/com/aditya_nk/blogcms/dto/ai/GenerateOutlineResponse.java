package com.aditya_nk.blogcms.dto.ai;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class GenerateOutlineResponse {

    private String topic;
    private String category;
    private List<String> outline;
    private String generatedAt;
}

