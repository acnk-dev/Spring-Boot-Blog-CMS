package com.aditya_nk.blogcms.service;

import com.aditya_nk.blogcms.dto.ai.GenerateOutlineRequest;
import com.aditya_nk.blogcms.dto.ai.GenerateOutlineResponse;
import com.aditya_nk.blogcms.dto.ai.GeneratePostRequest;
import com.aditya_nk.blogcms.dto.ai.GeneratePostResponse;

public interface AIService {

    GenerateOutlineResponse generateOutline(GenerateOutlineRequest request);

    GeneratePostResponse generatePost(GeneratePostRequest request);
}

