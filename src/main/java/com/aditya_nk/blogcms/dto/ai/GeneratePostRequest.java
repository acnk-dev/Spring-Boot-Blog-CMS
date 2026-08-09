package com.aditya_nk.blogcms.dto.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class GeneratePostRequest {

    @NotBlank
    private String topic;

    @NotNull
    private Long categoryId;

    @NotEmpty
    private List<String> outline;

    private String additionalInstructions;
}

