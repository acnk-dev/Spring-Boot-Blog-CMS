package com.aditya_nk.blogcms.config;

import com.aditya_nk.blogcms.security.JwtAuthenticationFilter;
import com.aditya_nk.blogcms.security.RateLimitFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitFilter rateLimitFilter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Auth — register, login, refresh are always public
                        .requestMatchers("/api/v1/auth/**").permitAll()

                        // Posts — reading published posts is public; all writes require a token + role
                        .requestMatchers(HttpMethod.GET, "/api/v1/posts/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/posts/**").hasAnyRole("AUTHOR", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/posts/**").hasAnyRole("AUTHOR", "ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/posts/**").hasAnyRole("AUTHOR", "ADMIN")

                        // Categories — browsing is public; taxonomy management is ADMIN-only
                        .requestMatchers(HttpMethod.GET, "/api/v1/categories/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/categories/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/categories/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/categories/**").hasRole("ADMIN")

                        // Tags — browsing is public; tag management is ADMIN-only
                        .requestMatchers(HttpMethod.GET, "/api/v1/tags/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/tags/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/tags/**").hasRole("ADMIN")

                        // Any future endpoint requires authentication by default
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // RateLimiterFilter runs after JWT auth so it can read the authenticated principal
                .addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("http://localhost:3000", "http://localhost:5173"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Authorization"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

