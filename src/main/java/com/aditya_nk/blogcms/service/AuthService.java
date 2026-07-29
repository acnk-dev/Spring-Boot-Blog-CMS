package com.aditya_nk.blogcms.service;

import com.aditya_nk.blogcms.dto.request.LoginRequest;
import com.aditya_nk.blogcms.dto.request.RefreshTokenRequest;
import com.aditya_nk.blogcms.dto.request.RegisterRequest;
import com.aditya_nk.blogcms.dto.response.AuthResponse;

/**
 * Service interface for authentication operations.
 * Defines the contract for user registration, login, and token refresh.
 */
public interface AuthService {

    /**
     * Register a new user.
     *
     * @param request the registration request containing email, password, and name
     * @return AuthResponse containing access token, refresh token, and user info
     */
    AuthResponse register(RegisterRequest request);

    /**
     * Authenticate a user with email and password.
     *
     * @param request the login request containing email and password
     * @return AuthResponse containing access token, refresh token, and user info
     */
    AuthResponse login(LoginRequest request);

    /**
     * Refresh an access token using a valid refresh token.
     *
     * @param request the refresh token request containing the refresh token
     * @return AuthResponse containing new access token, refresh token, and user info
     */
    AuthResponse refreshToken(RefreshTokenRequest request);
}

