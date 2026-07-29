package com.aditya_nk.blogcms.service.impl;

import com.aditya_nk.blogcms.dto.request.LoginRequest;
import com.aditya_nk.blogcms.dto.request.RefreshTokenRequest;
import com.aditya_nk.blogcms.dto.request.RegisterRequest;
import com.aditya_nk.blogcms.dto.response.AuthResponse;
import com.aditya_nk.blogcms.dto.response.UserResponse;
import com.aditya_nk.blogcms.entity.User;
import com.aditya_nk.blogcms.enums.Role;
import com.aditya_nk.blogcms.security.JwtUtil;
import com.aditya_nk.blogcms.service.AuthService;
import com.aditya_nk.blogcms.service.UserService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of AuthService interface.
 * Handles user registration, login, and token refresh operations.
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Value("${jwt.access-token-expiration}")
    private long accessTokenExpiration;

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // Check if email already exists
        if (userService.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email already registered: " + request.getEmail());
        }

        // Create new user with hashed password
        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .name(request.getName())
                .role(Role.SUBSCRIBER)
                .emailVerified(false)
                .isDeleted(false)
                .build();

        // Save user
        user = userService.createUser(user);

        // Generate tokens and return response
        return buildAuthResponse(user);
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        // Find user by email
        User user = userService.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("Invalid email or password"));

        // Verify password
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invalid email or password");
        }

        // Generate tokens and return response
        return buildAuthResponse(user);
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResponse refreshToken(RefreshTokenRequest request) {
        String refreshToken = request.getRefreshToken();

        // Validate refresh token
        if (!jwtUtil.validateToken(refreshToken)) {
            throw new RuntimeException("Invalid or expired refresh token");
        }

        // Verify it's a refresh token (not an access token)
        Claims claims = jwtUtil.extractAllClaims(refreshToken);
        String tokenType = claims.get("type", String.class);
        if (!"refresh".equals(tokenType)) {
            throw new RuntimeException("Invalid token type");
        }

        // Get user from token
        Long userId = jwtUtil.getUserIdFromToken(refreshToken);
        User user = userService.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Generate new tokens and return response
        return buildAuthResponse(user);
    }

    /**
     * Build AuthResponse with tokens and user info.
     *
     * @param user the authenticated user
     * @return AuthResponse containing tokens and user info
     */
    private AuthResponse buildAuthResponse(User user) {
        String accessToken = jwtUtil.generateAccessToken(user);
        String refreshToken = jwtUtil.generateRefreshToken(user);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(accessTokenExpiration / 1000) // Convert ms to seconds
                .user(UserResponse.fromEntity(user))
                .build();
    }
}
