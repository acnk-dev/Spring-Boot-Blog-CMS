package com.aditya_nk.blogcms.service.impl;

import com.aditya_nk.blogcms.dto.request.CreatePostRequest;
import com.aditya_nk.blogcms.dto.request.UpdatePostRequest;
import com.aditya_nk.blogcms.dto.response.PostResponse;
import com.aditya_nk.blogcms.entity.Category;
import com.aditya_nk.blogcms.entity.Post;
import com.aditya_nk.blogcms.entity.Tag;
import com.aditya_nk.blogcms.entity.User;
import com.aditya_nk.blogcms.enums.PostStatus;
import com.aditya_nk.blogcms.enums.Role;
import com.aditya_nk.blogcms.exception.ResourceNotFoundException;
import com.aditya_nk.blogcms.exception.UnauthorizedException;
import com.aditya_nk.blogcms.mapper.PostMapper;
import com.aditya_nk.blogcms.repository.CategoryRepository;
import com.aditya_nk.blogcms.repository.PostRepository;
import com.aditya_nk.blogcms.repository.TagRepository;
import com.aditya_nk.blogcms.repository.UserRepository;
import com.aditya_nk.blogcms.service.FileStorageService;
import com.aditya_nk.blogcms.service.PostService;
import com.aditya_nk.blogcms.util.SlugUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementation of PostService interface.
 * Provides business logic for post management operations.
 *
 * N+1 QUERY PROBLEM - SOLVED!
 *
 * The N+1 problem has been fixed using @EntityGraph in PostRepository.
 * All list-returning methods now eagerly fetch user, category, and tags
 * in a single query using LEFT JOIN FETCH.
 *
 * Before fix (N+1 problem):
 * - 1 query to fetch posts
 * - N queries to fetch each post's author
 * - N queries to fetch each post's category
 * - N queries to fetch each post's tags
 * - Total: 1 + 3N queries for N posts!
 *
 * After fix (@EntityGraph):
 * - 1 query with LEFT JOIN FETCH for all relationships
 * - Total: 1 query for N posts!
 *
 * @see com.studylink.blogcms.repository.PostRepository
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PostServiceImpl implements PostService {

    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final TagRepository tagRepository;
    private final PostMapper postMapper;
    private final FileStorageService fileStorageService;

    @Override
    @Transactional
    @CacheEvict(value = "posts", allEntries = true)
    public PostResponse createPost(CreatePostRequest request, Long userId) {
        // Find the author
        User author = userRepository.findById(userId)
                .filter(user -> !user.getIsDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        // Find the category
        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category", "id", request.getCategoryId()));

        // Find tags if provided
        Set<Tag> tags = new HashSet<>();
        if (request.getTagIds() != null && !request.getTagIds().isEmpty()) {
            tags = new HashSet<>(tagRepository.findAllById(request.getTagIds()));
        }

        // Generate unique slug from title
        String slug = SlugUtil.generateUniqueSlug(request.getTitle(), postRepository);

        // Create the post
        Post post = Post.builder()
                .user(author)
                .category(category)
                .title(request.getTitle())
                .slug(slug)
                .content(request.getContent())
                .excerpt(request.getExcerpt())
                .featuredImageUrl(request.getFeaturedImageUrl())
                .status(request.getStatus() != null ? request.getStatus() : PostStatus.DRAFT)
                .tags(tags)
                .build();

        // Set publishedAt if publishing immediately
        if (post.getStatus() == PostStatus.PUBLISHED) {
            post.setPublishedAt(LocalDateTime.now());
        }

        Post savedPost = postRepository.save(post);

        return postMapper.toResponse(savedPost);
    }

    @Override
    @Transactional
    @CacheEvict(value = "posts", allEntries = true)
    public PostResponse updatePost(Long postId, UpdatePostRequest request, Long userId) {
        Post post = postRepository.findById(postId)
                .filter(p -> !p.getIsDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Post", "id", postId));

        // Check if user is the author
        if (!post.getUser().getId().equals(userId)) {
            throw new UnauthorizedException("You are not authorized to update this post");
        }

        // Update title and regenerate slug if title changed
        if (request.getTitle() != null) {
            post.setTitle(request.getTitle());
            String newSlug = SlugUtil.generateUniqueSlug(request.getTitle(), post.getSlug(), postRepository);
            post.setSlug(newSlug);
        }

        // Update content
        if (request.getContent() != null) {
            post.setContent(request.getContent());
        }

        // Update excerpt
        if (request.getExcerpt() != null) {
            post.setExcerpt(request.getExcerpt());
        }

        // Update featured image
        if (request.getFeaturedImageUrl() != null) {
            post.setFeaturedImageUrl(request.getFeaturedImageUrl());
        }

        // Update category
        if (request.getCategoryId() != null) {
            Category category = categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category", "id", request.getCategoryId()));
            post.setCategory(category);
        }

        // Update tags
        if (request.getTagIds() != null) {
            Set<Tag> tags = new HashSet<>(tagRepository.findAllById(request.getTagIds()));
            post.setTags(tags);
        }

        // Update status
        if (request.getStatus() != null) {
            PostStatus oldStatus = post.getStatus();
            post.setStatus(request.getStatus());

            // Set publishedAt when publishing for the first time
            if (oldStatus == PostStatus.DRAFT && request.getStatus() == PostStatus.PUBLISHED) {
                post.setPublishedAt(LocalDateTime.now());
            }
        }

        Post savedPost = postRepository.save(post);

        return postMapper.toResponse(savedPost);
    }

    @Override
    @Transactional
    @CacheEvict(value = "posts", allEntries = true)
    public void deletePost(Long postId, Long userId) {
        Post post = postRepository.findById(postId)
                .filter(p -> !p.getIsDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Post", "id", postId));

        // Check if user is the author
        if (!post.getUser().getId().equals(userId)) {
            throw new UnauthorizedException("You are not authorized to delete this post");
        }

        // Soft delete
        post.setIsDeleted(true);
        postRepository.save(post);
    }

    @Override
    @Cacheable(value = "posts", key = "#postId")
    public PostResponse getPostById(Long postId) {
        Post post = postRepository.findById(postId)
                .filter(p -> !p.getIsDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Post", "id", postId));

        return postMapper.toResponse(post);
    }

    @Override
    @Cacheable(value = "posts", key = "#slug")
    public PostResponse getPostBySlug(String slug) {
        Post post = postRepository.findBySlugAndIsDeletedFalse(slug)
                .orElseThrow(() -> new ResourceNotFoundException("Post", "slug", slug));

        return postMapper.toResponse(post);
    }

    @Override
    @Cacheable(value = "posts", key = "'all_' + #page + '_' + #size + '_' + #sortBy + '_' + #sortDir", condition = "#page == 0")
    public Page<PostResponse> getAllPosts(int page, int size, String sortBy, String sortDir) {
        Sort sort = sortDir.equalsIgnoreCase("asc")
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        Pageable pageable = PageRequest.of(page, size, sort);

        // @EntityGraph in repository eagerly fetches user, category, and tags
        // in a single query using LEFT JOIN FETCH
        Page<Post> posts = postRepository.findByStatusAndIsDeletedFalse(
                PostStatus.PUBLISHED,
                pageable
        );

        // No N+1 problem - relationships already loaded!
        return posts.map(postMapper::toResponse);
    }

    @Override
    public Page<PostResponse> getPostsByUser(Long userId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

        Page<Post> posts = postRepository.findByUserIdAndIsDeletedFalse(userId, pageable);

        // No N+1 problem - @EntityGraph eagerly fetches relationships
        return posts.map(postMapper::toResponse);
    }

    @Override
    public Page<PostResponse> searchPosts(String query, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);

        // Step 1: GIN index scan — returns a page of matching IDs with total count metadata
        Page<Long> idPage = postRepository.findPublishedPostIdsByFullTextSearch(query, pageable);

        if (idPage.isEmpty()) {
            return Page.empty(pageable);
        }

        // Step 2: load full entities with associations for this page of IDs
        // @EntityGraph in repository ensures user, category, tags are fetched
        // in a single JOIN FETCH — no N+1 problem
        List<Post> posts = postRepository.findByIdIn(idPage.getContent());

        // Re-apply the Step 1 ordering (IN clause in JPQL does not preserve order)
        Map<Long, Post> byId = posts.stream()
                .collect(Collectors.toMap(Post::getId, p -> p));
        List<PostResponse> ordered = idPage.getContent().stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(postMapper::toResponse)
                .collect(Collectors.toList());

        return new PageImpl<>(ordered, pageable, idPage.getTotalElements());
    }

    @Override
    @Cacheable(value = "posts", key = "'category_' + #categoryId + '_' + #page + '_' + #size")
    public Page<PostResponse> getPostsByCategory(Long categoryId, int page, int size) {
        // Verify category exists
        if (!categoryRepository.existsById(categoryId)) {
            throw new ResourceNotFoundException("Category", "id", categoryId);
        }

        Pageable pageable = PageRequest.of(page, size, Sort.by("publishedAt").descending());

        Page<Post> posts = postRepository.findByCategoryIdAndStatusAndIsDeletedFalse(
                categoryId,
                PostStatus.PUBLISHED,
                pageable
        );

        // No N+1 problem - @EntityGraph eagerly fetches relationships
        return posts.map(postMapper::toResponse);
    }

    @Override
    public List<PostResponse> getPostsByTag(Long tagId) {
        // Verify tag exists
        if (!tagRepository.existsById(tagId)) {
            throw new ResourceNotFoundException("Tag", "id", tagId);
        }

        List<Post> posts = postRepository.findByTagsIdAndStatusAndIsDeletedFalse(
                tagId,
                PostStatus.PUBLISHED
        );

        // No N+1 problem - @EntityGraph eagerly fetches relationships
        return posts.stream()
                .map(postMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void incrementViewCount(Long postId) {
        // Check if post exists
        if (!postRepository.existsById(postId)) {
            throw new ResourceNotFoundException("Post", "id", postId);
        }

        // Atomic increment - no N+1 problem here!
        postRepository.incrementViewCount(postId);
    }

    @Override
    @Transactional
    @CacheEvict(value = "posts", allEntries = true)
    public PostResponse publishPost(Long postId, Long userId) {
        Post post = postRepository.findById(postId)
                .filter(p -> !p.getIsDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Post", "id", postId));

        // Check if user is the author
        if (!post.getUser().getId().equals(userId)) {
            throw new UnauthorizedException("You are not authorized to publish this post");
        }

        // Set status and publish date
        post.setStatus(PostStatus.PUBLISHED);
        if (post.getPublishedAt() == null) {
            post.setPublishedAt(LocalDateTime.now());
        }

        Post savedPost = postRepository.save(post);

        return postMapper.toResponse(savedPost);
    }

    @Override
    @Transactional
    @CacheEvict(value = "posts", allEntries = true)
    public PostResponse uploadFeaturedImage(Long postId, MultipartFile file, Long userId) {
        // 1. Find post
        Post post = postRepository.findById(postId)
                .filter(p -> !p.getIsDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Post", "id", postId));

        // 2. Find requesting user
        User user = userRepository.findById(userId)
                .filter(u -> !u.getIsDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        // 3. Verify ownership or ADMIN role
        boolean isOwner = post.getUser().getId().equals(userId);
        boolean isAdmin = user.getRole() == Role.ADMIN;

        if (!isOwner && !isAdmin) {
            throw new UnauthorizedException("You are not authorized to upload an image for this post");
        }

        // 4. Delete old featured image if present
        if (post.getFeaturedImageUrl() != null && !post.getFeaturedImageUrl().isBlank()) {
            fileStorageService.deleteFile(post.getFeaturedImageUrl());
        }

        // 5. Store new file
        String newImagePath = fileStorageService.storeFile(file, "posts");

        // 6. Update and save
        post.setFeaturedImageUrl(newImagePath);
        Post savedPost = postRepository.save(post);

        return postMapper.toResponse(savedPost);
    }
}
