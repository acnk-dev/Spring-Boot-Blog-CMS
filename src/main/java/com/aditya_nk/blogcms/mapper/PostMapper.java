package com.aditya_nk.blogcms.mapper;

import com.aditya_nk.blogcms.dto.response.PostResponse;
import com.aditya_nk.blogcms.entity.Post;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Mapper for converting Post entity to DTO.
 *
 * WARNING: This basic implementation will cause N+1 query problems!
 *
 * When mapping a list of posts:
 * - 1 query to fetch posts
 * - N queries to fetch each post's author (lazy loading)
 * - N queries to fetch each post's category (lazy loading)
 * - N queries to fetch each post's tags (lazy loading)
 *
 * Total: 1 + 3N queries for N posts!
 *
 * Solutions (to be implemented later):
 * 1. Use JOIN FETCH in repository queries
 * 2. Use @EntityGraph
 * 3. Use batch fetching (@BatchSize)
 * 4. Pre-fetch related entities before mapping
 *
 * For now, this mapper works correctly but has performance issues
 * that will be addressed when we optimize queries.
 */
@Component
public class PostMapper {

    private final UserMapper userMapper;
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;

    @Autowired
    public PostMapper(UserMapper userMapper, CategoryMapper categoryMapper, TagMapper tagMapper) {
        this.userMapper = userMapper;
        this.categoryMapper = categoryMapper;
        this.tagMapper = tagMapper;
    }

    /**
     * Convert Post entity to PostResponse DTO.
     *
     * WARNING: This triggers lazy loading of author, category, and tags!
     * Each call may result in 3 additional database queries.
     *
     * @param post the Post entity
     * @return PostResponse DTO or null if post is null
     */
    public PostResponse toResponse(Post post) {
        if (post == null) {
            return null;
        }

        PostResponse response = new PostResponse();
        response.setId(post.getId());
        response.setTitle(post.getTitle());
        response.setSlug(post.getSlug());
        response.setContent(post.getContent());
        response.setExcerpt(post.getExcerpt());
        response.setFeaturedImageUrl(post.getFeaturedImageUrl());
        response.setStatus(post.getStatus());
        response.setViewCount(post.getViewCount());
        response.setLikeCount(post.getLikeCount());
        response.setPublishedAt(post.getPublishedAt());
        response.setCreatedAt(post.getCreatedAt());
        response.setUpdatedAt(post.getUpdatedAt());

        // WARNING: These calls trigger lazy loading!
        // Each one may execute a separate database query
        response.setAuthor(userMapper.toAuthorResponse(post.getUser()));
        response.setCategory(categoryMapper.toResponse(post.getCategory()));
        response.setTags(tagMapper.toResponseList(post.getTags()));

        return response;
    }

    /**
     * Convert list of Post entities to PostResponse DTOs.
     *
     * WARNING: For N posts, this will execute up to 1 + 3N queries!
     * Use with caution for large lists.
     *
     * @param posts list of Post entities
     * @return list of PostResponse DTOs
     */
    public List<PostResponse> toResponseList(List<Post> posts) {
        return posts.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Convert Post to PostResponse without loading relationships.
     * Use this when you only need basic post info.
     *
     * @param post the Post entity
     * @return PostResponse DTO with only direct fields (no author, category, tags)
     */
    public PostResponse toResponseWithoutRelations(Post post) {
        if (post == null) {
            return null;
        }

        PostResponse response = new PostResponse();
        response.setId(post.getId());
        response.setTitle(post.getTitle());
        response.setSlug(post.getSlug());
        response.setContent(post.getContent());
        response.setExcerpt(post.getExcerpt());
        response.setFeaturedImageUrl(post.getFeaturedImageUrl());
        response.setStatus(post.getStatus());
        response.setViewCount(post.getViewCount());
        response.setLikeCount(post.getLikeCount());
        response.setPublishedAt(post.getPublishedAt());
        response.setCreatedAt(post.getCreatedAt());
        response.setUpdatedAt(post.getUpdatedAt());
        // author, category, tags left as null

        return response;
    }

    // Static method for simple cases (doesn't map relationships)

    /**
     * Static method to convert Post to PostResponse.
     * Maps all relationships using static mapper methods.
     *
     * WARNING: Still causes N+1 if Post relationships are lazy loaded!
     */
    public static PostResponse toPostResponse(Post post) {
        if (post == null) {
            return null;
        }
        return PostResponse.builder()
                .id(post.getId())
                .title(post.getTitle())
                .slug(post.getSlug())
                .content(post.getContent())
                .excerpt(post.getExcerpt())
                .featuredImageUrl(post.getFeaturedImageUrl())
                .status(post.getStatus())
                .viewCount(post.getViewCount())
                .likeCount(post.getLikeCount())
                .author(UserMapper.toAuthor(post.getUser()))
                .category(CategoryMapper.toCategoryResponse(post.getCategory()))
                .tags(post.getTags().stream()
                        .map(TagMapper::toTagResponse)
                        .collect(Collectors.toList()))
                .publishedAt(post.getPublishedAt())
                .createdAt(post.getCreatedAt())
                .updatedAt(post.getUpdatedAt())
                .build();
    }
}

