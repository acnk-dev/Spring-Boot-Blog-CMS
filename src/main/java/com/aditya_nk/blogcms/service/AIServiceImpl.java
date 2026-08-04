package com.aditya_nk.blogcms.service;

import com.aditya_nk.blogcms.ai.PromptTemplateService;
import com.aditya_nk.blogcms.dto.ai.GenerateOutlineRequest;
import com.aditya_nk.blogcms.dto.ai.GenerateOutlineResponse;
import com.aditya_nk.blogcms.dto.ai.GeneratePostRequest;
import com.aditya_nk.blogcms.dto.ai.GeneratePostResponse;
import com.aditya_nk.blogcms.entity.Category;
import com.aditya_nk.blogcms.exception.ResourceNotFoundException;
import com.aditya_nk.blogcms.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AIServiceImpl implements AIService {

    private final ChatClient chatClient;
    private final PromptTemplateService promptTemplateService;
    private final CategoryRepository categoryRepository;

    @Override
    public GenerateOutlineResponse generateOutline(GenerateOutlineRequest request) {
        log.info("Generating outline for topic: {}", request.getTopic());

        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + request.getCategoryId()));

        String systemPrompt = promptTemplateService.getOutlineSystemPrompt(category);
        String userPrompt = buildOutlineUserPrompt(request);

        String response = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();

        List<String> outline = parseNumberedList(response);
        log.info("Generated outline with {} sections for topic: {}", outline.size(), request.getTopic());

        return GenerateOutlineResponse.builder()
                .topic(request.getTopic())
                .category(category.getName())
                .outline(outline)
                .generatedAt(LocalDateTime.now().toString())
                .build();
    }

    @Override
    public GeneratePostResponse generatePost(GeneratePostRequest request) {
        log.info("Generating post for topic: {} with {} sections", request.getTopic(), request.getOutline().size());

        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + request.getCategoryId()));

        String systemPrompt = promptTemplateService.getPostSystemPrompt(category);
        String userPrompt = buildPostUserPrompt(request);

        String content = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();

        String title = extractTitle(content);
        log.info("Generated post '{}' ({} chars) for topic: {}", title, content.length(), request.getTopic());

        return GeneratePostResponse.builder()
                .title(title)
                .content(content)
                .excerpt("")          // filled in by commit 57
                .suggestedTags(List.of())  // filled in by commit 57
                .generatedAt(LocalDateTime.now().toString())
                .build();
    }

    private String buildOutlineUserPrompt(GenerateOutlineRequest request) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Create a blog post outline for the topic: ").append(request.getTopic());

        if (request.getTargetAudience() != null && !request.getTargetAudience().isBlank()) {
            prompt.append("\nTarget audience: ").append(request.getTargetAudience());
        }
        if (request.getAdditionalInstructions() != null && !request.getAdditionalInstructions().isBlank()) {
            prompt.append("\nAdditional requirements: ").append(request.getAdditionalInstructions());
        }

        return prompt.toString();
    }

    private String buildPostUserPrompt(GeneratePostRequest request) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Write a complete blog post about: ").append(request.getTopic());
        prompt.append("\n\nFollow this outline:\n");

        for (int i = 0; i < request.getOutline().size(); i++) {
            prompt.append(i + 1).append(". ").append(request.getOutline().get(i)).append("\n");
        }

        if (request.getAdditionalInstructions() != null && !request.getAdditionalInstructions().isBlank()) {
            prompt.append("\nAdditional requirements: ").append(request.getAdditionalInstructions());
        }

        return prompt.toString();
    }

    // Parses "1. Introduction\n2. Core Concepts\n..." into a List<String>
    private List<String> parseNumberedList(String response) {
        return Arrays.stream(response.split("\n"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .filter(line -> line.matches("^\\d+[.)].+"))
                .map(line -> line.replaceFirst("^\\d+[.)]\\s*", ""))
                .toList();
    }

    // Extracts the first H1 heading from Markdown content ("# Title" -> "Title")
    private String extractTitle(String content) {
        return Arrays.stream(content.split("\n"))
                .filter(line -> line.startsWith("# "))
                .findFirst()
                .map(line -> line.substring(2).trim())
                .orElse("Untitled Post");
    }
}
