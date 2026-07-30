package com.aditya_nk.blogcms.service.impl;

import com.aditya_nk.blogcms.dto.request.CreateTagRequest;
import com.aditya_nk.blogcms.dto.response.TagResponse;
import com.aditya_nk.blogcms.entity.Tag;
import com.aditya_nk.blogcms.exception.BadRequestException;
import com.aditya_nk.blogcms.exception.ResourceNotFoundException;
import com.aditya_nk.blogcms.mapper.TagMapper;
import com.aditya_nk.blogcms.repository.TagRepository;
import com.aditya_nk.blogcms.service.TagService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Implementation of TagService interface.
 * Provides business logic for tag management operations.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TagServiceImpl implements TagService {

    private final TagRepository tagRepository;
    private final TagMapper tagMapper;

    private static final Pattern NON_LATIN = Pattern.compile("[^\\w-]");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s]");
    private static final Pattern MULTIPLE_HYPHENS = Pattern.compile("-+");

    @Override
    @Transactional
    public TagResponse createTag(CreateTagRequest request) {
        String normalizedName = normalizeTagName(request.getName());

        // Check if tag already exists
        if (tagRepository.existsByName(normalizedName)) {
            throw new BadRequestException("Tag with name '" + normalizedName + "' already exists");
        }

        // Generate slug from name
        String slug = generateSlug(normalizedName);

        // Ensure slug is unique
        slug = ensureUniqueSlug(slug, null);

        Tag tag = Tag.builder()
                .name(normalizedName)
                .slug(slug)
                .build();

        Tag savedTag = tagRepository.save(tag);

        return tagMapper.toResponse(savedTag);
    }

    @Override
    public List<TagResponse> getAllTags() {
        List<Tag> tags = tagRepository.findAllByOrderByNameAsc();

        return tags.stream()
                .map(tagMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public TagResponse getTagById(Long id) {
        Tag tag = tagRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tag", "id", id));

        return tagMapper.toResponse(tag);
    }

    @Override
    public TagResponse getTagBySlug(String slug) {
        Tag tag = tagRepository.findBySlug(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Tag", "slug", slug));

        return tagMapper.toResponse(tag);
    }

    @Override
    @Transactional
    public void deleteTag(Long id) {
        Tag tag = tagRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tag", "id", id));

        // Tags can be deleted even if posts use them
        // The post_tags junction table has ON DELETE CASCADE
        // Posts will simply lose this tag
        tagRepository.delete(tag);
    }

    @Override
    public List<TagResponse> searchTags(String searchTerm) {
        List<Tag> tags = tagRepository.searchByName(searchTerm);

        return tags.stream()
                .map(tagMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<TagResponse> findTagsByPrefix(String prefix) {
        List<Tag> tags = tagRepository.findByNameStartingWith(prefix);

        return tags.stream()
                .map(tagMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public List<TagResponse> getOrCreateTags(Set<String> tagNames) {
        if (tagNames == null || tagNames.isEmpty()) {
            return Collections.emptyList();
        }

        // Normalize all tag names
        Set<String> normalizedNames = tagNames.stream()
                .map(this::normalizeTagName)
                .filter(name -> !name.isBlank())
                .collect(Collectors.toSet());

        // Find existing tags
        Set<Tag> existingTags = tagRepository.findByNameIn(normalizedNames);
        Set<String> existingNames = existingTags.stream()
                .map(Tag::getName)
                .collect(Collectors.toSet());

        // Create missing tags
        List<Tag> newTags = normalizedNames.stream()
                .filter(name -> !existingNames.contains(name))
                .map(name -> {
                    String slug = generateSlug(name);
                    slug = ensureUniqueSlug(slug, null);
                    return Tag.builder()
                            .name(name)
                            .slug(slug)
                            .build();
                })
                .collect(Collectors.toList());

        // Save new tags
        List<Tag> savedNewTags = tagRepository.saveAll(newTags);

        // Combine existing and new tags
        List<Tag> allTags = new ArrayList<>(existingTags);
        allTags.addAll(savedNewTags);

        return allTags.stream()
                .map(tagMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<TagResponse> getPopularTags(int limit) {
        List<Tag> tags = tagRepository.findPopularTags(limit);

        return tags.stream()
                .map(tagMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public boolean existsByName(String name) {
        return tagRepository.existsByName(normalizeTagName(name));
    }

    /**
     * Normalize a tag name to lowercase.
     * Tags should be case-insensitive.
     */
    private String normalizeTagName(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().toLowerCase(Locale.ENGLISH);
    }

    /**
     * Generate a URL-friendly slug from a name.
     */
    private String generateSlug(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }

        String normalized = Normalizer.normalize(name, Normalizer.Form.NFD);
        String slug = WHITESPACE.matcher(normalized).replaceAll("-");
        slug = NON_LATIN.matcher(slug).replaceAll("");
        slug = MULTIPLE_HYPHENS.matcher(slug).replaceAll("-");
        slug = slug.toLowerCase(Locale.ENGLISH);
        slug = slug.replaceAll("^-|-$", "");

        return slug;
    }

    /**
     * Ensure the slug is unique by appending a number if necessary.
     */
    private String ensureUniqueSlug(String baseSlug, String currentSlug) {
        if (baseSlug.isEmpty()) {
            baseSlug = "tag";
        }

        // If generated slug matches current slug, keep it
        if (baseSlug.equals(currentSlug)) {
            return currentSlug;
        }

        String slug = baseSlug;
        int counter = 1;

        while (tagRepository.existsBySlug(slug) && !slug.equals(currentSlug)) {
            slug = baseSlug + "-" + counter;
            counter++;
        }

        return slug;
    }
}

