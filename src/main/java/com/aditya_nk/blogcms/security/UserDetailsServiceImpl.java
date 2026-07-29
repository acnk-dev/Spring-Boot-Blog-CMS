package com.aditya_nk.blogcms.security;

import com.aditya_nk.blogcms.entity.User;
import com.aditya_nk.blogcms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Implementation of Spring Security's UserDetailsService.
 * Loads user-specific data for authentication.
 *
 * This service is used by Spring Security to authenticate users
 * and by the JwtAuthenticationFilter to load user details from tokens.
 */
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    /**
     * Load user by email (used as username in this application).
     *
     * @param email the user's email address
     * @return UserDetails containing user information for Spring Security
     * @throws UsernameNotFoundException if user not found or is deleted
     */
    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmailAndIsDeletedFalse(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + email));

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getEmail())
                .password(user.getPassword())
                .authorities("ROLE_" + user.getRole().name())
                .accountExpired(false)
                .accountLocked(false)
                .credentialsExpired(false)
                .disabled(user.getIsDeleted())
                .build();
    }

    /**
     * Load user by ID (used by JWT filter when user ID is extracted from token).
     *
     * @param userId the user's ID
     * @return UserDetails containing user information for Spring Security
     * @throws UsernameNotFoundException if user not found or is deleted
     */
    public UserDetails loadUserById(Long userId) throws UsernameNotFoundException {
        User user = userRepository.findById(userId)
                .filter(u -> !u.getIsDeleted())
                .orElseThrow(() -> new UsernameNotFoundException("User not found with id: " + userId));

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getEmail())
                .password(user.getPassword())
                .authorities("ROLE_" + user.getRole().name())
                .accountExpired(false)
                .accountLocked(false)
                .credentialsExpired(false)
                .disabled(user.getIsDeleted())
                .build();
    }
}

