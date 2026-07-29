# Commit 12: Create JwtAuthenticationFilter

**Date:** February 1, 2026
**Commit Message:** Create JwtAuthenticationFilter

---

## Development Mindset & Decision Process

### The Request Authentication Layer

**Current State:**
- JwtUtil ready (token validation/parsing)
- AuthController ready (login returns tokens)
- SecurityConfig permits all requests (no protection)
- But no mechanism to authenticate requests using JWT!

**Goal:**
- Create JwtAuthenticationFilter to intercept requests
- Extract and validate JWT from Authorization header
- Set authentication in SecurityContext
- Create CustomUserDetailsService to load user data

**Teaching Point:** In stateless JWT authentication, every request must be authenticated independently. The filter runs before controllers, validates the token, and tells Spring Security "this user is authenticated with these roles."

---

## Decision 1: Understanding the Filter Chain

### Where Our Filter Fits

```
HTTP Request
     ↓
┌─────────────────────────────────────────────────────────────┐
│                  SPRING SECURITY FILTER CHAIN               │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  SecurityContextPersistenceFilter                           │
│      └── Initializes SecurityContext                        │
│                     ↓                                       │
│  CorsFilter                                                 │
│      └── Handles CORS preflight                             │
│                     ↓                                       │
│  ★ JwtAuthenticationFilter (OUR FILTER) ★                  │
│      └── Validates JWT, sets authentication                 │
│                     ↓                                       │
│  UsernamePasswordAuthenticationFilter                       │
│      └── Form login (we're not using this)                  │
│                     ↓                                       │
│  ExceptionTranslationFilter                                 │
│      └── Handles auth exceptions                            │
│                     ↓                                       │
│  FilterSecurityInterceptor                                  │
│      └── Enforces authorization rules                       │
│                                                             │
└─────────────────────────────────────────────────────────────┘
     ↓
Controller
```

### Why OncePerRequestFilter?

```java
public class JwtAuthenticationFilter extends OncePerRequestFilter {
```

**The Problem:**
- Filters can execute multiple times per request (forwards, includes)
- We only want to validate JWT once

**OncePerRequestFilter Guarantees:**
- Exactly one execution per request
- Tracks via request attribute
- Standard practice for authentication filters

---

## Decision 2: Token Extraction

### Authorization Header Format

```http
GET /api/v1/posts HTTP/1.1
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIi...
              └──┬──┘ └────────────────┬────────────────────┘
              Scheme              Token
```

**OAuth2 Bearer Token Standard:**
- Prefix: "Bearer " (with space)
- Case-sensitive
- Token follows immediately

### Extraction Logic

```java
private String extractJwtFromRequest(HttpServletRequest request) {
    String bearerToken = request.getHeader(AUTHORIZATION_HEADER);

    if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
        return bearerToken.substring(BEARER_PREFIX.length());
    }

    return null;
}
```

**Step by step:**
1. Get "Authorization" header value
2. Check if it exists and starts with "Bearer "
3. Extract everything after "Bearer " (7 characters)
4. Return null if no valid token

**Why return null instead of throwing?**
- Some endpoints are public (no token needed)
- Filter should not block, just not authenticate
- Authorization rules decide if authentication is required

---

## Decision 3: Authentication Flow

### The doFilterInternal Method

```java
@Override
protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
) throws ServletException, IOException {

    try {
        // 1. Extract JWT from request
        String jwt = extractJwtFromRequest(request);

        // 2. Validate and process token
        if (StringUtils.hasText(jwt) && jwtUtil.validateToken(jwt)) {
            // 3. Extract user ID from token
            Long userId = jwtUtil.getUserIdFromToken(jwt);

            // 4. Load user details
            UserDetails userDetails = userDetailsService.loadUserById(userId);

            // 5. Create authentication token
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            userDetails,
                            null,  // No credentials needed (already authenticated via JWT)
                            userDetails.getAuthorities()
                    );

            // 6. Set request details
            authentication.setDetails(
                    new WebAuthenticationDetailsSource().buildDetails(request)
            );

            // 7. Set authentication in context
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
    } catch (Exception e) {
        log.error("Cannot set user authentication: {}", e.getMessage());
    }

    // 8. Continue filter chain (always!)
    filterChain.doFilter(request, response);
}
```

### Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                    JWT AUTHENTICATION FLOW                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Request with "Authorization: Bearer <token>"                       │
│                          ↓                                          │
│  ┌─────────────────────────────────────┐                           │
│  │   extractJwtFromRequest()           │                           │
│  │   Returns: "eyJhbG..." or null      │                           │
│  └─────────────────────────────────────┘                           │
│                          ↓                                          │
│        Token exists? ──No──→ Continue (unauthenticated)            │
│              │                                                      │
│             Yes                                                     │
│              ↓                                                      │
│  ┌─────────────────────────────────────┐                           │
│  │   jwtUtil.validateToken(jwt)        │                           │
│  │   Checks: signature, expiration     │                           │
│  └─────────────────────────────────────┘                           │
│                          ↓                                          │
│        Valid? ──No──→ Continue (unauthenticated)                   │
│              │                                                      │
│             Yes                                                     │
│              ↓                                                      │
│  ┌─────────────────────────────────────┐                           │
│  │   jwtUtil.getUserIdFromToken(jwt)   │                           │
│  │   Returns: 123 (user ID)            │                           │
│  └─────────────────────────────────────┘                           │
│                          ↓                                          │
│  ┌─────────────────────────────────────┐                           │
│  │   userDetailsService.loadUserById() │                           │
│  │   Returns: UserDetails with roles   │                           │
│  └─────────────────────────────────────┘                           │
│                          ↓                                          │
│  ┌─────────────────────────────────────┐                           │
│  │   Create Authentication object      │                           │
│  │   - principal: UserDetails          │                           │
│  │   - credentials: null               │                           │
│  │   - authorities: [ROLE_SUBSCRIBER]  │                           │
│  └─────────────────────────────────────┘                           │
│                          ↓                                          │
│  ┌─────────────────────────────────────┐                           │
│  │   SecurityContextHolder             │                           │
│  │   .getContext()                     │                           │
│  │   .setAuthentication(auth)          │                           │
│  └─────────────────────────────────────┘                           │
│                          ↓                                          │
│  Continue filter chain (authenticated!)                             │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Decision 4: CustomUserDetailsService

### Why We Need It

**Spring Security expects UserDetails:**
```java
public interface UserDetails {
    Collection<? extends GrantedAuthority> getAuthorities();
    String getPassword();
    String getUsername();
    boolean isAccountNonExpired();
    boolean isAccountNonLocked();
    boolean isCredentialsNonExpired();
    boolean isEnabled();
}
```

**Our User entity is not UserDetails, so we need an adapter.**

### Implementation

```java
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserService userService;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userService.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        return new org.springframework.security.core.userdetails.User(
                user.getEmail(),
                user.getPassword(),
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
        );
    }

    public UserDetails loadUserById(Long userId) {
        User user = userService.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        return new org.springframework.security.core.userdetails.User(
                user.getEmail(),
                user.getPassword(),
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
        );
    }
}
```

### Two Load Methods

| Method | Used By | When |
|--------|---------|------|
| `loadUserByUsername(email)` | Spring Security (form login) | Standard interface method |
| `loadUserById(id)` | Our JWT filter | When we have user ID from token |

**Why loadUserById?**
- JWT contains user ID in `sub` claim
- More efficient than loading by email
- ID is indexed, fast lookup

### Role to Authority Mapping

```java
new SimpleGrantedAuthority("ROLE_" + user.getRole().name())
```

**User Role → Spring Authority:**
- `SUBSCRIBER` → `ROLE_SUBSCRIBER`
- `AUTHOR` → `ROLE_AUTHOR`
- `ADMIN` → `ROLE_ADMIN`

**Why "ROLE_" prefix?**
- Spring Security convention
- `hasRole('ADMIN')` checks for `ROLE_ADMIN`
- `hasAuthority('ROLE_ADMIN')` is equivalent

---

## Decision 5: UsernamePasswordAuthenticationToken

### Why This Token Type?

```java
UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(
                userDetails,    // principal
                null,           // credentials
                userDetails.getAuthorities()  // authorities
        );
```

**Despite the name, it's used for any authenticated principal:**
- Constructor with 3 args marks it as authenticated
- Constructor with 2 args (principal, credentials) is unauthenticated

**Why null credentials?**
- Password already validated (via JWT signature)
- No need to keep password in memory
- Security best practice

### WebAuthenticationDetailsSource

```java
authentication.setDetails(
        new WebAuthenticationDetailsSource().buildDetails(request)
);
```

**Captures request metadata:**
- Remote IP address
- Session ID (if any)
- Useful for audit logging

---

## Decision 6: Error Handling Strategy

### Silent Failure Approach

```java
try {
    // Authentication logic
} catch (Exception e) {
    log.error("Cannot set user authentication: {}", e.getMessage());
}

// Always continue
filterChain.doFilter(request, response);
```

**Why not throw exceptions?**

**Scenario 1: Invalid token on public endpoint**
```
GET /api/v1/posts (public)
Authorization: Bearer <invalid_token>

Expected: Return posts (token irrelevant for public endpoint)
If we threw: 401 Unauthorized (wrong!)
```

**Scenario 2: Invalid token on protected endpoint**
```
GET /api/v1/admin/users (protected)
Authorization: Bearer <invalid_token>

Our filter: Doesn't set authentication
FilterSecurityInterceptor: Sees no authentication, returns 401
Result: Correct behavior without filter throwing
```

**Teaching Point:** The filter's job is to authenticate if possible. The authorization rules (configured in SecurityFilterChain) decide what happens if authentication fails.

---

## What Works Now After This Commit?

### Filter Is Ready (But Not Registered)

**The filter exists but isn't in the security chain yet!**

Next commit will update SecurityConfig:
```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        // ... existing config ...
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

    return http.build();
}
```

### How It Will Work

**Request with valid token:**
```http
GET /api/v1/protected-resource
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...

Filter:
1. Extracts token
2. Validates token ✓
3. Loads UserDetails for user ID 123
4. Sets authentication with ROLE_SUBSCRIBER
5. Continues to controller

Controller:
- SecurityContext has authenticated user
- @PreAuthorize("hasRole('SUBSCRIBER')") passes
- Returns resource
```

**Request without token:**
```http
GET /api/v1/public-resource

Filter:
1. No Authorization header
2. extractJwtFromRequest returns null
3. Skips authentication
4. Continues to controller

Controller:
- SecurityContext has no authentication
- Public endpoint, no authorization required
- Returns resource
```

**Request with invalid token:**
```http
GET /api/v1/protected-resource
Authorization: Bearer invalid.token.here

Filter:
1. Extracts token
2. validateToken returns false
3. Skips authentication
4. Continues to controller

FilterSecurityInterceptor:
- No authentication in context
- Protected endpoint requires authentication
- Returns 401 Unauthorized
```

---

## Security Considerations

### What We Handle

| Concern | How |
|---------|-----|
| Token signature verification | `jwtUtil.validateToken()` |
| Token expiration | `jwtUtil.validateToken()` checks exp claim |
| Token type confusion | Filter processes any valid token (type checked elsewhere) |
| Exception leakage | Catch-all with generic error message |
| Multiple filter execution | `OncePerRequestFilter` base class |

### What We Don't Handle Yet

| Concern | Future Solution |
|---------|-----------------|
| Token blacklisting | Store revoked tokens in Redis |
| Concurrent session limit | Track active tokens per user |
| Token binding | Bind token to IP/device fingerprint |
| Refresh token on access | Automatic token refresh |

---

## Teaching Talking Points

### For Students New to Security Filters

**Analogy - Airport Security Checkpoint:**
```
Passenger (Request) arrives at checkpoint (Filter)

Security Guard (Filter):
1. "May I see your ID?" (Extract token)
2. Scans ID for validity (Validate token)
3. Looks up in system (Load UserDetails)
4. Issues boarding pass (Set Authentication)
5. "You may proceed" (Continue filter chain)

No ID?
- Guard lets you through
- But you can't board certain flights (protected endpoints)

Fake ID?
- Guard lets you through (doesn't throw you out)
- But system shows you're not verified
- Can't access restricted areas
```

### Common Filter Mistakes

**1. Throwing Exceptions:**
```java
// DON'T
if (!jwtUtil.validateToken(jwt)) {
    throw new UnauthorizedException("Invalid token");
}

// DO
if (StringUtils.hasText(jwt) && jwtUtil.validateToken(jwt)) {
    // Set authentication
}
// Don't throw, just don't authenticate
```

**2. Forgetting to Continue Chain:**
```java
// DON'T - Blocks all requests!
protected void doFilterInternal(...) {
    if (jwt == null) {
        return;  // Filter chain broken!
    }
}

// DO - Always continue
protected void doFilterInternal(...) {
    // ... authentication logic ...
    filterChain.doFilter(request, response);  // Always!
}
```

**3. Using Regular Filter:**
```java
// DON'T - May execute multiple times
public class JwtFilter implements Filter {

// DO - Guaranteed once per request
public class JwtFilter extends OncePerRequestFilter {
```

**4. Hardcoding User Loading:**
```java
// DON'T - Tight coupling
User user = userRepository.findById(userId).get();

// DO - Use service with proper abstraction
UserDetails userDetails = userDetailsService.loadUserById(userId);
```

---

## Architectural Decisions Record

**ADR-033: OncePerRequestFilter Base Class**
- **Status:** Accepted
- **Context:** Need filter that runs exactly once per request
- **Decision:** Extend `OncePerRequestFilter` instead of implementing `Filter`
- **Consequences:**
    - Guaranteed single execution
    - Cleaner code (no manual tracking)
    - Standard Spring Security practice

**ADR-034: Silent Authentication Failure**
- **Status:** Accepted
- **Context:** What to do when token is invalid
- **Decision:** Log error, don't set authentication, continue filter chain
- **Consequences:**
    - Public endpoints still work with bad tokens
    - Authorization layer handles access decisions
    - Better separation of concerns

**ADR-035: Load User by ID (Not Email)**
- **Status:** Accepted
- **Context:** JWT contains user ID, not email
- **Decision:** Add `loadUserById()` method to UserDetailsService
- **Consequences:**
    - More efficient (ID is primary key)
    - Consistent with JWT payload design
    - Extra method in UserDetailsService

**ADR-036: Single Role per User**
- **Status:** Accepted
- **Context:** How many roles can a user have
- **Decision:** One role per user, mapped to one authority
- **Consequences:**
    - Simple role hierarchy (SUBSCRIBER < AUTHOR < ADMIN)
    - Easy to understand and implement
    - May need multiple roles later (can expand)

---

## Commit Stats
- **Files created:** 2
    - JwtAuthenticationFilter.java (89 lines)
    - CustomUserDetailsService.java (52 lines)
- **Classes created:** 2
- **Key methods:**
    - `doFilterInternal()` - Main filter logic
    - `extractJwtFromRequest()` - Token extraction
    - `loadUserByUsername()` - Standard UserDetailsService method
    - `loadUserById()` - Custom method for JWT authentication

---

## Testing the Filter

### Unit Test Example

```java
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private CustomUserDetailsService userDetailsService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @InjectMocks
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldSetAuthentication_whenValidToken() throws Exception {
        // Given
        String token = "valid.jwt.token";
        Long userId = 1L;

        UserDetails userDetails = new org.springframework.security.core.userdetails.User(
                "test@example.com",
                "password",
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_SUBSCRIBER"))
        );

        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(jwtUtil.validateToken(token)).thenReturn(true);
        when(jwtUtil.getUserIdFromToken(token)).thenReturn(userId);
        when(userDetailsService.loadUserById(userId)).thenReturn(userDetails);

        // When
        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // Then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
                .isEqualTo("test@example.com");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void shouldNotSetAuthentication_whenNoToken() throws Exception {
        // Given
        when(request.getHeader("Authorization")).thenReturn(null);

        // When
        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // Then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void shouldNotSetAuthentication_whenInvalidToken() throws Exception {
        // Given
        String token = "invalid.jwt.token";
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(jwtUtil.validateToken(token)).thenReturn(false);

        // When
        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // Then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void shouldNotSetAuthentication_whenMalformedHeader() throws Exception {
        // Given
        when(request.getHeader("Authorization")).thenReturn("NotBearer token");

        // When
        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // Then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void shouldContinueFilterChain_whenExceptionOccurs() throws Exception {
        // Given
        String token = "valid.jwt.token";
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(jwtUtil.validateToken(token)).thenThrow(new RuntimeException("JWT error"));

        // When
        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // Then
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);  // Still continues!
    }
}
```

### CustomUserDetailsService Test

```java
@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    @Mock
    private UserService userService;

    @InjectMocks
    private CustomUserDetailsService customUserDetailsService;

    @Test
    void loadUserByUsername_shouldReturnUserDetails() {
        User user = User.builder()
                .id(1L)
                .email("test@example.com")
                .password("hashedPassword")
                .role(Role.AUTHOR)
                .build();

        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        UserDetails userDetails = customUserDetailsService.loadUserByUsername("test@example.com");

        assertThat(userDetails.getUsername()).isEqualTo("test@example.com");
        assertThat(userDetails.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_AUTHOR");
    }

    @Test
    void loadUserByUsername_shouldThrow_whenUserNotFound() {
        when(userService.findByEmail("notfound@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customUserDetailsService.loadUserByUsername("notfound@example.com"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void loadUserById_shouldReturnUserDetails() {
        User user = User.builder()
                .id(1L)
                .email("test@example.com")
                .password("hashedPassword")
                .role(Role.ADMIN)
                .build();

        when(userService.findById(1L)).thenReturn(Optional.of(user));

        UserDetails userDetails = customUserDetailsService.loadUserById(1L);

        assertThat(userDetails.getUsername()).isEqualTo("test@example.com");
        assertThat(userDetails.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }
}
```

---

## Resources for Students
- Spring Security Filters: https://docs.spring.io/spring-security/reference/servlet/architecture.html
- OncePerRequestFilter: https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/filter/OncePerRequestFilter.html
- UserDetailsService: https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/user-details-service.html
- SecurityContextHolder: https://docs.spring.io/spring-security/reference/servlet/authentication/architecture.html
- Bearer Token: https://oauth.net/2/bearer-tokens/
