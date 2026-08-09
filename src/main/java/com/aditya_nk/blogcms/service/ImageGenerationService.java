package com.aditya_nk.blogcms.service;

import com.aditya_nk.blogcms.dto.ai.GenerateImageRequest;
import com.aditya_nk.blogcms.dto.ai.GenerateImageResponse;

public interface ImageGenerationService {

    GenerateImageResponse generateFeaturedImage(GenerateImageRequest request);
}

