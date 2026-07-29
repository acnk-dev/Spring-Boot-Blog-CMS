package com.aditya_nk.blogcms.dto.response;

import com.aditya_nk.blogcms.entity.User;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthorResponse {

    private Long id;
    private String name;
    private String avatarUrl;

    public static AuthorResponse fromEntity(User user) {
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
