package com.aditya_nk.blogcms.mapper;

import com.aditya_nk.blogcms.dto.response.AuthorResponse;
import com.aditya_nk.blogcms.dto.response.UserResponse;
import com.aditya_nk.blogcms.entity.User;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Mapper for converting User entity to various DTOs.
 * Provides both instance methods (for Spring injection) and static methods (for convenience).
 */
@Component
public class UserMapper {

    /**
     * Convert User entity to full UserResponse DTO.
     * Used for profile pages and admin views.
     *
     * @param user the User entity
     * @return UserResponse DTO or null if user is null
     */
    public UserResponse toResponse(User user) {
        return toUserResponse(user);
    }

    /**
     * Convert User entity to AuthorResponse DTO.
     * Used for embedding author info in post responses.
     * Contains only public-facing fields (id, name, avatarUrl).
     *
     * @param user the User entity
     * @return AuthorResponse DTO or null if user is null
     */
    public AuthorResponse toAuthorResponse(User user) {
        return toAuthor(user);
    }

    /**
     * Convert list of User entities to UserResponse DTOs.
     *
     * @param users list of User entities
     * @return list of UserResponse DTOs
     */
    public List<UserResponse> toResponseList(List<User> users) {
        return users.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    // Static methods for convenience (used in DTO fromEntity methods)

    /**
     * Static method to convert User to UserResponse.
     * Can be used without Spring context.
     */
    public static UserResponse toUserResponse(User user) {
        if (user == null) {
            return null;
        }
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .role(user.getRole())
                .emailVerified(user.getEmailVerified())
                .bio(user.getBio())
                .avatarUrl(user.getAvatarUrl())
                .createdAt(user.getCreatedAt())
                .build();
    }

    /**
     * Static method to convert User to AuthorResponse.
     * Can be used without Spring context.
     */
    public static AuthorResponse toAuthor(User user) {
        if (user == null) {
            return null;
        }
        return AuthorResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .avatarUrl(user.getAvatarUrl())
                .build();
    }
}

