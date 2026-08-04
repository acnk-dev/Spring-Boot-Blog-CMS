package com.aditya_nk.blogcms.security;

import com.aditya_nk.blogcms.config.RateLimitConfig;
import com.aditya_nk.blogcms.entity.User;
import com.aditya_nk.blogcms.enums.Role;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Distributed rate-limiting filter that enforces per-role request quotas.
 *
 * <p>This filter runs once per HTTP request (courtesy of {@link OncePerRequestFilter})
 * <em>after</em> {@link JwtAuthenticationFilter} has already populated the
 * {@link SecurityContextHolder}. That ordering is important: by the time this filter
 * runs, {@code SecurityContextHolder.getContext().getAuthentication()} already holds
 * the authenticated user — so we can read their role without touching the database.</p>
 *
 * <h2>Algorithm (token bucket)</h2>
 * <ol>
 *   <li>Each user/IP gets their own "bucket" stored in Redis.</li>
 *   <li>The bucket starts full (e.g. 100 tokens for SUBSCRIBER).</li>
 *   <li>Every request tries to consume 1 token ({@code bucket.tryConsume(1)}).</li>
 *   <li>If consumption succeeds, the request proceeds.</li>
 *   <li>If the bucket is empty, the filter short-circuits with HTTP 429.</li>
 *   <li>Tokens refill continuously over time (greedy refill) up to the bucket capacity.</li>
 * </ol>
 *
 * <h2>Bucket key strategy</h2>
 * <ul>
 *   <li>Authenticated: {@code "rate_limit:<username>:<role>"}</li>
 *   <li>Unauthenticated: {@code "rate_limit:anon:<client-ip>"}</li>
 * </ul>
 * Using the username rather than the user ID makes Redis keys human-readable
 * during debugging; the role suffix lets us spot-check limits by role in Redis CLI.
 *
 * <h2>Skipped paths</h2>
 * <ul>
 *   <li>{@code /actuator/**} — health/metrics endpoints must not be throttled</li>
 * </ul>
 */
@Slf4j
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final LettuceBasedProxyManager<byte[]> rateLimitProxyManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RateLimitFilter(LettuceBasedProxyManager<byte[]> rateLimitProxyManager) {
        this.rateLimitProxyManager = rateLimitProxyManager;
    }

    private static final String KEY_PREFIX = "rate_limit:";

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getServletPath();
        return path.startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String bucketKeyStr = resolveBucketKey(request);
        // The ProxyManager is keyed by byte[] (raw Redis protocol type).
        // Convert human-readable string key to UTF-8 bytes.
        byte[] bucketKey = bucketKeyStr.getBytes(StandardCharsets.UTF_8);
        Bandwidth bandwidth = resolveBandwidth(request);

        BucketConfiguration configuration = BucketConfiguration.builder()
                .addLimit(bandwidth)
                .build();

        // builder().build() looks up the bucket in Redis by key; if it does not exist yet,
        // Bucket4j atomically initialises it with the provided configuration.
        Bucket bucket = rateLimitProxyManager.builder()
                .build(bucketKey, configuration);

        if (bucket.tryConsume(1)) {
            long remaining = bucket.getAvailableTokens();
            response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
            log.debug("Rate limit OK — key={}, remaining={}", bucketKeyStr, remaining);
            filterChain.doFilter(request, response);
        } else {
            log.warn("Rate limit exceeded — key={}", bucketKeyStr);
            sendTooManyRequestsResponse(response, bucketKeyStr);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Builds a Redis key that uniquely identifies the rate-limit bucket for
     * this request's caller.
     */
    private String resolveBucketKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (isAuthenticated(auth)) {
            User user = (User) auth.getPrincipal();
            return KEY_PREFIX + user.getUsername() + ":" + user.getRole().name();
        }

        // Anonymous caller — key by IP so each client gets its own bucket
        String ip = resolveClientIp(request);
        return KEY_PREFIX + "anon:" + ip;
    }

    /**
     * Selects the correct {@link Bandwidth} for this request's caller.
     * Anonymous callers receive the SUBSCRIBER limit as a conservative default.
     */
    private Bandwidth resolveBandwidth(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (!isAuthenticated(auth)) {
            return RateLimitConfig.SUBSCRIBER_BANDWIDTH;
        }

        User user = (User) auth.getPrincipal();
        Role role = user.getRole();

        return switch (role) {
            case ADMIN -> RateLimitConfig.ADMIN_BANDWIDTH;
            case AUTHOR -> RateLimitConfig.AUTHOR_BANDWIDTH;
            case SUBSCRIBER -> RateLimitConfig.SUBSCRIBER_BANDWIDTH;
        };
    }

    /**
     * Returns true when the {@link Authentication} represents a fully authenticated
     * user (not anonymous and not null).
     */
    private boolean isAuthenticated(Authentication auth) {
        return auth != null && auth.isAuthenticated()
                && auth.getPrincipal() instanceof User;
    }

    /**
     * Extracts the real client IP, respecting the {@code X-Forwarded-For} header
     * that load balancers / reverse proxies inject.
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // X-Forwarded-For may be a comma-separated list; take the first entry
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Writes a JSON 429 response and halts further filter chain processing.
     */
    private void sendTooManyRequestsResponse(HttpServletResponse response, String bucketKey)
            throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("X-RateLimit-Remaining", "0");
        // Retry-After tells HTTP clients how many seconds to wait before retrying.
        // 3600 = one hour, matching the refill window defined in RateLimitConfig.
        response.setHeader("Retry-After", "3600");

        Map<String, Object> body = Map.of(
                "status", 429,
                "error", "Too Many Requests",
                "message", "Rate limit exceeded. Try again later."
        );

        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
