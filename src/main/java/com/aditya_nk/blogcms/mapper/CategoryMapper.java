package com.aditya_nk.blogcms.mapper;

import com.aditya_nk.blogcms.dto.response.CategoryResponse;
import com.aditya_nk.blogcms.entity.Category;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Mapper for converting Category entity to DTO.
 * Provides both instance methods (for Spring injection) and static methods (for convenience).
 */
@Component
public class CategoryMapper {

    /**
     * Convert Category entity to CategoryResponse DTO.
     *
     * @param category the Category entity
     * @return CategoryResponse DTO or null if category is null
     */
    public CategoryResponse toResponse(Category category) {
        return toCategoryResponse(category);
    }

    /**
     * Convert list of Category entities to CategoryResponse DTOs.
     *
     * @param categories list of Category entities
     * @return list of CategoryResponse DTOs
     */
    public List<CategoryResponse> toResponseList(List<Category> categories) {
        return categories.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    // Static methods for convenience

    /**
     * Static method to convert Category to CategoryResponse.
     * Can be used without Spring context.
     */
    public static CategoryResponse toCategoryResponse(Category category) {
        if (category == null) {
            return null;
        }
        return CategoryResponse.builder()
                .id(category.getId())
                .name(category.getName())
                .slug(category.getSlug())
                .description(category.getDescription())
                .createdAt(category.getCreatedAt())
                .updatedAt(category.getUpdatedAt())
                .build();
    }
}

