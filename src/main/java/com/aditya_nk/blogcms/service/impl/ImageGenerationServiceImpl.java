package com.aditya_nk.blogcms.service.impl;

import com.aditya_nk.blogcms.ai.ImagePromptAgent;
import com.aditya_nk.blogcms.config.FileStorageConfig;
import com.aditya_nk.blogcms.dto.ai.GenerateImageRequest;
import com.aditya_nk.blogcms.dto.ai.GenerateImageResponse;
import com.aditya_nk.blogcms.entity.Category;
import com.aditya_nk.blogcms.exception.FileStorageException;
import com.aditya_nk.blogcms.exception.ResourceNotFoundException;
import com.aditya_nk.blogcms.repository.CategoryRepository;
import com.aditya_nk.blogcms.service.ImageGenerationService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageGenerationServiceImpl implements ImageGenerationService {

    private static final String GEMINI_IMAGE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-image:generateContent";
    private static final String PLACEHOLDER_URL = "/images/placeholder-featured.png";

    private final ImagePromptAgent imagePromptAgent;
    private final CategoryRepository categoryRepository;
    private final FileStorageConfig fileStorageConfig;

    @Value("${GEMINI_API_KEY:}")
    private String geminiApiKey;

    @Value("${ai.image.enabled:true}")
    private boolean imageEnabled;

    private RestClient restClient;
    private Path aiImagesDir;

    @PostConstruct
    public void init() {
        restClient = RestClient.create();
        aiImagesDir = Paths.get(fileStorageConfig.getUploadDir())
                .toAbsolutePath()
                .normalize()
                .resolve("ai-images");
        try {
            Files.createDirectories(aiImagesDir);
            log.info("AI images directory initialised at: {}", aiImagesDir);
        } catch (IOException e) {
            throw new FileStorageException("Cannot create ai-images directory", e);
        }
    }

    @Override
    public GenerateImageResponse generateFeaturedImage(GenerateImageRequest request) {
        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Category not found with id: " + request.getCategoryId()));

        // Agent 1 — always runs (ChatClient/Gemini generates the image description)
        String imagePrompt = imagePromptAgent.generateImagePrompt(
                request.getTitle(),
                category.getName(),
                request.getStyle());

        if (!imageEnabled) {
            log.warn("Image generation disabled (ai.image.enabled=false) — returning placeholder");
            return GenerateImageResponse.builder()
                    .imageUrl(PLACEHOLDER_URL)
                    .generatedPrompt(imagePrompt)
                    .generatedAt(LocalDateTime.now().toString())
                    .build();
        }

        // Agent 2 — calls Gemini image generation REST API
        log.info("Agent 2 — calling Gemini image generation for prompt: {}", imagePrompt);
        String imageUrl = callImagenAndSave(imagePrompt);

        return GenerateImageResponse.builder()
                .imageUrl(imageUrl)
                .generatedPrompt(imagePrompt)
                .generatedAt(LocalDateTime.now().toString())
                .build();
    }

    @SuppressWarnings("unchecked")
    private String callImagenAndSave(String imagePrompt) {
        Map<String, Object> requestBody = Map.of(
                "contents", List.of(Map.of(
                        "parts", List.of(Map.of("text", imagePrompt))
                )),
                "generationConfig", Map.of(
                        "responseModalities", List.of("IMAGE")
                )
        );

        Map<String, Object> response = restClient.post()
                .uri(GEMINI_IMAGE_URL)
                .header("x-goog-api-key", geminiApiKey)
                .header("Content-Type", "application/json")
                .body(requestBody)
                .retrieve()
                .body(Map.class);

        if (response == null || !response.containsKey("candidates")) {
            throw new FileStorageException("Gemini image generation returned an empty response", null);
        }

        List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.get("candidates");
        if (candidates == null || candidates.isEmpty()) {
            throw new FileStorageException("Gemini image generation returned no candidates", null);
        }

        Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
        List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");

        String base64Image = null;
        for (Map<String, Object> part : parts) {
            if (part.containsKey("inlineData")) {
                Map<String, Object> inlineData = (Map<String, Object>) part.get("inlineData");
                base64Image = (String) inlineData.get("data");
                break;
            }
        }

        if (base64Image == null) {
            throw new FileStorageException("Gemini image generation returned no image data in parts", null);
        }

        byte[] imageBytes = Base64.getDecoder().decode(base64Image);

        return saveImageBytes(imageBytes);
    }

    private String saveImageBytes(byte[] imageBytes) {
        String fileName = "ai-generated-" + UUID.randomUUID() + ".png";
        Path targetPath = aiImagesDir.resolve(fileName);

        try {
            Files.write(targetPath, imageBytes);
            log.info("Saved AI-generated image: {} ({} bytes)", targetPath, imageBytes.length);
        } catch (IOException e) {
            throw new FileStorageException("Failed to save generated image: " + fileName, e);
        }

        return "/uploads/ai-images/" + fileName;
    }
}


