package com.aditya_nk.blogcms.dto.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ExpandContentRequest {

    @NotBlank
    private String content;

    @NotNull
    private Long categoryId;

    private String focusAreas;
}

