package com.aditya_nk.blogcms.dto.ai;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ExcerptRequest {

    @NotBlank
    private String content;

    private int maxLength;
}
