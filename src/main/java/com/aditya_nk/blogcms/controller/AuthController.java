package com.aditya_nk.blogcms.controller;

import com.aditya_nk.blogcms.dto.request.LoginRequest;
import com.aditya_nk.blogcms.dto.request.RefreshTokenRequest;
import com.aditya_nk.blogcms.dto.request.RegisterRequest;
import com.aditya_nk.blogcms.dto.response.AuthResponse;
import com.aditya_nk.blogcms.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller for authentication operations.
 * Handles user registration, login, and token refresh.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * Register a new user.
     *
     * @param request the registration request containing email, password, and name
     * @return AuthResponse with tokens and user info
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Authenticate a user with email and password.
     *
     * @param request the login request containing email and password
     * @return AuthResponse with tokens and user info
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Refresh an access token using a valid refresh token.
     *
     * @param request the refresh token request
     * @return AuthResponse with new tokens and user info
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        AuthResponse response = authService.refreshToken(request);
        return ResponseEntity.ok(response);
    }
}

