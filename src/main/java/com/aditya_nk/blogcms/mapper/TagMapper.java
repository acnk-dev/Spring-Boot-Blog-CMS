package com.aditya_nk.blogcms.mapper;

import com.aditya_nk.blogcms.dto.response.TagResponse;
import com.aditya_nk.blogcms.entity.Tag;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mapper for converting Tag entity to DTO.
 * Provides both instance methods (for Spring injection) and static methods (for convenience).
 */
@Component
public class TagMapper {

    /**
     * Convert Tag entity to TagResponse DTO.
     *
     * @param tag the Tag entity
     * @return TagResponse DTO or null if tag is null
     */
    public TagResponse toResponse(Tag tag) {
        return toTagResponse(tag);
    }

    /**
     * Convert list of Tag entities to TagResponse DTOs.
     *
     * @param tags list of Tag entities
     * @return list of TagResponse DTOs
     */
    public List<TagResponse> toResponseList(List<Tag> tags) {
        return tags.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Convert set of Tag entities to list of TagResponse DTOs.
     * Useful for Post.tags which is a Set.
     *
     * @param tags set of Tag entities
     * @return list of TagResponse DTOs
     */
    public List<TagResponse> toResponseList(Set<Tag> tags) {
        return tags.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Convert collection of Tag entities to list of TagResponse DTOs.
     * Generic method that works with any collection type.
     *
     * @param tags collection of Tag entities
     * @return list of TagResponse DTOs
     */
    public List<TagResponse> toResponseList(Collection<Tag> tags) {
        return tags.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    // Static methods for convenience

    /**
     * Static method to convert Tag to TagResponse.
     * Can be used without Spring context.
     */
    public static TagResponse toTagResponse(Tag tag) {
        if (tag == null) {
            return null;
        }
        return TagResponse.builder()
                .id(tag.getId())
                .name(tag.getName())
                .slug(tag.getSlug())
                .createdAt(tag.getCreatedAt())
                .updatedAt(tag.getUpdatedAt())
                .build();
    }
}

