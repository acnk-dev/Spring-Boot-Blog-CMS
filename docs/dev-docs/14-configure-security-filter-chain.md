# Commit 14: Configure SecurityFilterChain with JWT

**Date:** February 1, 2026
**Commit Message:** Configure SecurityFilterChain with JWT

---

## Development Mindset & Decision Process

### The Security Integration Point

**Current State:**
- JwtAuthenticationFilter ready (validates tokens, sets authentication)
- UserDetailsServiceImpl ready (loads user data)
- AuthController ready (register, login, refresh endpoints)
- SecurityConfig allows all requests (no protection!)

**Goal:**
- Register JwtAuthenticationFilter in the security filter chain
- Define which endpoints are public vs protected
- Enable JWT-based authentication for protected endpoints

**Teaching Point:** This commit is the "switch flip" that activates JWT security. Before: all requests allowed. After: only authenticated requests reach protected endpoints.

---

## Decision 1: Filter Chain Position

### Adding Our Filter

```java
.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
```

**What this means:**
- Our filter runs BEFORE `UsernamePasswordAuthenticationFilter`
- `UsernamePasswordAuthenticationFilter` handles form login (we don't use it)
- By running first, JWT auth takes precedence

### Filter Chain Order

```
┌─────────────────────────────────────────────────────────────┐
│                SECURITY FILTER CHAIN                        │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  1. SecurityContextPersistenceFilter                        │
│     └── Creates SecurityContext                             │
│                                                             │
│  2. CorsFilter                                              │
│     └── Handles CORS preflight (OPTIONS)                    │
│                                                             │
│  3. CsrfFilter (DISABLED)                                   │
│     └── We use JWT, not cookies                             │
│                                                             │
│  ★ 4. JwtAuthenticationFilter (OUR FILTER) ★               │
│     └── Validates JWT, sets authentication                  │
│                                                             │
│  5. UsernamePasswordAuthenticationFilter                    │
│     └── Form login (we skip this)                           │
│                                                             │
│  6. ExceptionTranslationFilter                              │
│     └── Converts auth exceptions to responses               │
│                                                             │
│  7. FilterSecurityInterceptor                               │
│     └── Enforces authorization rules                        │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### Why Before UsernamePasswordAuthenticationFilter?

**Alternative positions:**

| Position | Effect |
|----------|--------|
| Before UPAF (our choice) | JWT checked first, UPAF never processes |
| After UPAF | UPAF might interfere, unnecessary processing |
| At end | Too late, authorization already checked |

---

## Decision 2: Authorization Rules

### Our Configuration

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/v1/auth/**").permitAll()
    .requestMatchers("/api/v1/posts/**").permitAll()
    .anyRequest().authenticated()
)
```

### Rule Breakdown

| Pattern | Access | Reason |
|---------|--------|--------|
| `/api/v1/auth/**` | Public | Register, login, refresh don't need auth |
| `/api/v1/posts/**` | Public | Reading posts is public (blog CMS) |
| `anyRequest()` | Authenticated | Everything else requires valid JWT |

### How Rules Are Evaluated

```
Request: GET /api/v1/posts/123

1. Check "/api/v1/auth/**" → No match
2. Check "/api/v1/posts/**" → MATCH! → permitAll()
3. Request proceeds (no auth needed)

Request: GET /api/v1/users/me

1. Check "/api/v1/auth/**" → No match
2. Check "/api/v1/posts/**" → No match
3. Check anyRequest() → MATCH! → authenticated()
4. Check SecurityContext for authentication
   - Has valid auth? → Request proceeds
   - No auth? → 401 Unauthorized
```

**Teaching Point:** Rules are evaluated in order. First match wins. Put specific rules before general rules.

---

## Decision 3: Session Management

### Stateless Sessions

```java
.sessionManagement(session -> session
    .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
```

**What this means:**
- Spring Security will NOT create HTTP sessions
- No JSESSIONID cookie
- Each request is independent (stateless)

### Session Policies

| Policy | Behavior | Use Case |
|--------|----------|----------|
| `ALWAYS` | Always create session | Traditional web apps |
| `IF_REQUIRED` | Create if needed | Default behavior |
| `NEVER` | Don't create (but use if exists) | Hybrid apps |
| **`STATELESS`** | Never create or use | JWT/API authentication |

**Why STATELESS for JWT?**
- JWT contains all auth info (self-contained)
- No server-side session storage needed
- Easier horizontal scaling (no session affinity)
- Follows REST principles

---

## Decision 4: CSRF Disabled

### Why Disable CSRF?

```java
.csrf(csrf -> csrf.disable())
```

**CSRF (Cross-Site Request Forgery) Protection:**
- Prevents malicious sites from making requests on user's behalf
- Works via tokens in cookies/headers
- Designed for cookie-based session authentication

**Why we disable it:**
- We use JWT in Authorization header, not cookies
- CSRF attacks target cookie-based auth
- JWT is not automatically sent by browser
- Attacker can't steal JWT from cross-origin request

### Security Comparison

| Auth Method | CSRF Risk | Why |
|-------------|-----------|-----|
| Session + Cookie | HIGH | Browser auto-sends cookies |
| JWT in Cookie | HIGH | Browser auto-sends |
| **JWT in Header** | LOW | App must explicitly add header |

---

## Decision 5: CORS Configuration (Unchanged)

### Existing Configuration

```java
@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(List.of(
        "http://localhost:3000",    // React default
        "http://localhost:5173"     // Vite default
    ));
    configuration.setAllowedMethods(List.of(
        "GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"
    ));
    configuration.setAllowedHeaders(List.of("*"));
    configuration.setExposedHeaders(List.of("Authorization"));
    configuration.setAllowCredentials(true);
    configuration.setMaxAge(3600L);
    // ...
}
```

**Key for JWT:**
- `setAllowedHeaders(List.of("*"))` - Allows Authorization header
- `setExposedHeaders(List.of("Authorization"))` - Exposes to JavaScript

---

## What Works Now After This Commit?

### Complete JWT Authentication Flow!

**1. Register (Public):**
```http
POST /api/v1/auth/register
Content-Type: application/json

{"email": "user@example.com", "password": "password123", "name": "User"}

Response: 201 Created
{"accessToken": "eyJ...", "refreshToken": "eyJ...", ...}
```

**2. Login (Public):**
```http
POST /api/v1/auth/login
Content-Type: application/json

{"email": "user@example.com", "password": "password123"}

Response: 200 OK
{"accessToken": "eyJ...", "refreshToken": "eyJ...", ...}
```

**3. Access Protected Endpoint:**
```http
GET /api/v1/users/me
Authorization: Bearer eyJ...

Response: 200 OK (if valid token)
Response: 401 Unauthorized (if invalid/missing token)
```

**4. Access Public Endpoint:**
```http
GET /api/v1/posts/123

Response: 200 OK (no token needed)
```

### Testing with cURL

```bash
# 1. Register
curl -X POST http://localhost:8081/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"password123","name":"Test User"}'

# Save the accessToken from response

# 2. Access protected endpoint without token (should fail)
curl http://localhost:8081/api/v1/users/me
# Response: 401 Unauthorized

# 3. Access protected endpoint with token (should work)
curl http://localhost:8081/api/v1/users/me \
  -H "Authorization: Bearer <your_access_token>"
# Response: 200 OK (once endpoint is implemented)

# 4. Access public endpoint (no token needed)
curl http://localhost:8081/api/v1/posts
# Response: 200 OK (once endpoint is implemented)
```

---

## Security Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                    COMPLETE AUTH FLOW                               │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌───────────────────────────────────────────────────────┐         │
│  │              PUBLIC ENDPOINTS                          │         │
│  │  /api/v1/auth/**  →  No token required                │         │
│  │  /api/v1/posts/** →  No token required                │         │
│  └───────────────────────────────────────────────────────┘         │
│                                                                     │
│  ┌───────────────────────────────────────────────────────┐         │
│  │              PROTECTED ENDPOINTS                       │         │
│  │  /api/v1/users/** →  Valid JWT required               │         │
│  │  /api/v1/admin/** →  Valid JWT required               │         │
│  │  Everything else  →  Valid JWT required               │         │
│  └───────────────────────────────────────────────────────┘         │
│                                                                     │
│  Request with "Authorization: Bearer <token>"                       │
│                          ↓                                          │
│  ┌─────────────────────────────────────┐                           │
│  │   JwtAuthenticationFilter           │                           │
│  │   1. Extract token from header      │                           │
│  │   2. Validate signature & expiry    │                           │
│  │   3. Load UserDetails               │                           │
│  │   4. Set Authentication             │                           │
│  └─────────────────────────────────────┘                           │
│                          ↓                                          │
│  ┌─────────────────────────────────────┐                           │
│  │   FilterSecurityInterceptor         │                           │
│  │   - Check: Is endpoint protected?   │                           │
│  │   - Check: Is user authenticated?   │                           │
│  │   - If no auth: 401 Unauthorized    │                           │
│  │   - If auth: Continue to controller │                           │
│  └─────────────────────────────────────┘                           │
│                          ↓                                          │
│  ┌─────────────────────────────────────┐                           │
│  │   Controller                        │                           │
│  │   - Process request                 │                           │
│  │   - Return response                 │                           │
│  └─────────────────────────────────────┘                           │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Future Endpoint Security (Preview)

### Role-Based Access Control

```java
// Future configuration
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/v1/auth/**").permitAll()
    .requestMatchers(HttpMethod.GET, "/api/v1/posts/**").permitAll()
    .requestMatchers(HttpMethod.POST, "/api/v1/posts/**").hasAnyRole("AUTHOR", "ADMIN")
    .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
    .anyRequest().authenticated()
)
```

### Method-Level Security

```java
// Enable in config
@EnableMethodSecurity

// Use in controllers
@PreAuthorize("hasRole('ADMIN')")
public void deleteUser(Long id) { ... }

@PreAuthorize("hasRole('AUTHOR') or #userId == authentication.principal.id")
public Post updatePost(Long userId, Post post) { ... }
```

---

## Teaching Talking Points

### For Students New to Spring Security

**Analogy - Building Security:**
```
Your Application = Office Building

Without Security (before this commit):
- Front door is wide open
- Anyone walks in anywhere
- No badge checks

With Security (after this commit):
- Front door has guard (JwtAuthenticationFilter)
- Guard checks your badge (validates JWT)
- Lobby is public (/api/v1/auth/**)
- Cafeteria is public (/api/v1/posts/**)
- Offices require badge (anyRequest().authenticated())
```

### Common Configuration Mistakes

**1. Wrong Rule Order:**
```java
// DON'T - anyRequest() catches everything first!
.requestMatchers("/api/v1/auth/**").permitAll()  // Never reached!
.anyRequest().authenticated()
.requestMatchers("/api/v1/posts/**").permitAll() // Never reached!

// DO - Specific rules first
.requestMatchers("/api/v1/auth/**").permitAll()
.requestMatchers("/api/v1/posts/**").permitAll()
.anyRequest().authenticated()
```

**2. Forgetting Filter Registration:**
```java
// DON'T - Filter exists but not in chain
// (No addFilterBefore call)

// DO
.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
```

**3. CSRF with JWT:**
```java
// DON'T - CSRF enabled with JWT
// Causes 403 on POST requests
.csrf(Customizer.withDefaults())

// DO - Disable CSRF for JWT
.csrf(csrf -> csrf.disable())
```

**4. Sessions with JWT:**
```java
// DON'T - Sessions with JWT (defeats purpose)
.sessionManagement(session -> session
    .sessionCreationPolicy(SessionCreationPolicy.ALWAYS))

// DO - Stateless for JWT
.sessionManagement(session -> session
    .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
```

---

## Architectural Decisions Record

**ADR-040: JWT Filter Before UsernamePasswordAuthenticationFilter**
- **Status:** Accepted
- **Context:** Need to position JWT filter in chain
- **Decision:** Add before UsernamePasswordAuthenticationFilter
- **Consequences:**
    - JWT auth takes precedence
    - Form login filter never processes (we don't use it)
    - Standard position for token-based filters

**ADR-041: Public Posts Endpoints**
- **Status:** Accepted
- **Context:** Should reading posts require authentication?
- **Decision:** `/api/v1/posts/**` is permitAll
- **Consequences:**
    - Anyone can read posts (blog CMS purpose)
    - Writing/editing will be protected at method level
    - Need to refine for POST/PUT/DELETE later

**ADR-042: Stateless Session Management**
- **Status:** Accepted
- **Context:** Session management policy for JWT auth
- **Decision:** SessionCreationPolicy.STATELESS
- **Consequences:**
    - No server-side sessions
    - Each request authenticated independently
    - Easier horizontal scaling
    - No "remember me" via session

---

## Commit Stats
- **Files modified:** 1
    - SecurityConfig.java
- **Lines changed:** ~15
- **Key changes:**
    - Added JwtAuthenticationFilter injection
    - Updated authorizeHttpRequests rules
    - Added addFilterBefore configuration

---

## Testing the Security Configuration

### Integration Test

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityConfigIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void publicEndpoints_shouldBeAccessibleWithoutToken() {
        // Auth endpoints
        ResponseEntity<String> authResponse = restTemplate.getForEntity(
                "/api/v1/auth/login",
                String.class
        );
        // Should not be 401 (might be 405 Method Not Allowed for GET, but not 401)
        assertThat(authResponse.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);

        // Posts endpoints
        ResponseEntity<String> postsResponse = restTemplate.getForEntity(
                "/api/v1/posts",
                String.class
        );
        assertThat(postsResponse.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedEndpoints_shouldRequireToken() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/users/me",
                String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedEndpoints_shouldAcceptValidToken() {
        // First, register and get token
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("securitytest@example.com")
                .password("password123")
                .name("Security Test")
                .build();

        ResponseEntity<AuthResponse> authResponse = restTemplate.postForEntity(
                "/api/v1/auth/register",
                registerRequest,
                AuthResponse.class
        );

        String token = authResponse.getBody().getAccessToken();

        // Now access protected endpoint with token
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me",
                HttpMethod.GET,
                entity,
                String.class
        );

        // Should not be 401 (might be 404 if endpoint doesn't exist yet)
        assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void invalidToken_shouldReturn401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("invalid.token.here");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me",
                HttpMethod.GET,
                entity,
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
```

### MockMvc Test

```java
@WebMvcTest
@AutoConfigureMockMvc
class SecurityConfigMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private UserDetailsServiceImpl userDetailsService;

    @Test
    void publicEndpoint_shouldNotRequireAuth() throws Exception {
        mockMvc.perform(get("/api/v1/posts"))
                .andExpect(status().isNotEqualTo(401));
    }

    @Test
    void protectedEndpoint_withoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpoint_withValidToken_shouldPass() throws Exception {
        String token = "valid.jwt.token";

        when(jwtUtil.validateToken(token)).thenReturn(true);
        when(jwtUtil.getUserIdFromToken(token)).thenReturn(1L);
        when(userDetailsService.loadUserById(1L)).thenReturn(
                new org.springframework.security.core.userdetails.User(
                        "test@example.com",
                        "password",
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_SUBSCRIBER"))
                )
        );

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotEqualTo(401));
    }
}
```

---

## Resources for Students
- Spring Security Filter Chain: https://docs.spring.io/spring-security/reference/servlet/architecture.html
- Authorization: https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html
- Session Management: https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html
- CSRF: https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html
- CORS: https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html
