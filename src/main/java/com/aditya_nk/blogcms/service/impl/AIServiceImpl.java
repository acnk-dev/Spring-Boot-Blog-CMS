package com.aditya_nk.blogcms.service.impl;

import com.aditya_nk.blogcms.ai.PromptTemplateService;
import com.aditya_nk.blogcms.dto.ai.*;
import com.aditya_nk.blogcms.entity.Category;
import com.aditya_nk.blogcms.exception.ResourceNotFoundException;
import com.aditya_nk.blogcms.repository.CategoryRepository;
import com.aditya_nk.blogcms.service.AIService;
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

        Category category = findCategory(request.getCategoryId());
        String response = chatClient.prompt()
                .system(promptTemplateService.getOutlineSystemPrompt(category))
                .user(buildOutlineUserPrompt(request))
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

        Category category = findCategory(request.getCategoryId());
        String content = chatClient.prompt()
                .system(promptTemplateService.getPostSystemPrompt(category))
                .user(buildPostUserPrompt(request))
                .call()
                .content();

        String title = extractTitle(content);

        ExcerptRequest excerptRequest = new ExcerptRequest();
        excerptRequest.setContent(content);
        String excerpt = generateExcerpt(excerptRequest);

        TagSuggestionRequest tagRequest = new TagSuggestionRequest();
        tagRequest.setContent(content);
        List<String> tags = suggestTags(tagRequest);

        log.info("Generated post '{}' ({} chars) for topic: {}", title, content.length(), request.getTopic());

        return GeneratePostResponse.builder()
                .title(title)
                .content(content)
                .excerpt(excerpt)
                .suggestedTags(tags)
                .generatedAt(LocalDateTime.now().toString())
                .build();
    }

    @Override
    public ExpandContentResponse expandContent(ExpandContentRequest request) {
        log.info("Expanding content, original length: {} chars", request.getContent().length());

        Category category = findCategory(request.getCategoryId());

        StringBuilder userPrompt = new StringBuilder("Expand and improve the following content:\n\n");
        userPrompt.append(request.getContent());
        if (request.getFocusAreas() != null && !request.getFocusAreas().isBlank()) {
            userPrompt.append("\n\nFocus on: ").append(request.getFocusAreas());
        }

        String expandedContent = chatClient.prompt()
                .system(promptTemplateService.getExpandSystemPrompt(category))
                .user(userPrompt.toString())
                .call()
                .content();

        log.info("Content expanded from {} to {} words",
                countWords(request.getContent()), countWords(expandedContent));

        return ExpandContentResponse.builder()
                .expandedContent(expandedContent)
                .originalWordCount(countWords(request.getContent()))
                .expandedWordCount(countWords(expandedContent))
                .generatedAt(LocalDateTime.now().toString())
                .build();
    }

    @Override
    public String generateExcerpt(ExcerptRequest request) {
        log.info("Generating excerpt for content ({} chars)", request.getContent().length());

        int maxLength = request.getMaxLength() > 0 ? request.getMaxLength() : 200;
        String userPrompt = String.format(
                "Create an excerpt (max %d characters) for this blog post:\n\n%s",
                maxLength, request.getContent());

        return chatClient.prompt()
                .system(promptTemplateService.getExcerptSystemPrompt())
                .user(userPrompt)
                .call()
                .content()
                .trim();
    }

    @Override
    public List<String> suggestTags(TagSuggestionRequest request) {
        log.info("Suggesting tags for content ({} chars)", request.getContent().length());

        int maxTags = request.getMaxTags() > 0 ? request.getMaxTags() : 5;
        String userPrompt = String.format(
                "Suggest %d relevant tags for this blog post:\n\n%s",
                maxTags, request.getContent());

        String response = chatClient.prompt()
                .system(promptTemplateService.getTagSuggestionSystemPrompt())
                .user(userPrompt)
                .call()
                .content();

        return Arrays.stream(response.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(tag -> !tag.isEmpty())
                .limit(maxTags)
                .toList();
    }

    private Category findCategory(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + categoryId));
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

    private int countWords(String content) {
        if (content == null || content.isBlank()) return 0;
        return content.split("\\s+").length;
    }
}
