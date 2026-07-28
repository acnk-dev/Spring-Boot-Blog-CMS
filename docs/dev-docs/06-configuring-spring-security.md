# Commit 6: Configure Spring Security Basic Setup

**Date:** January 29, 2026
**Commit Message:** Configure Spring Security basic setup

---

## Development Mindset & Decision Process

### The Security Foundation

**Current State:**
- User entity and repository exist
- `spring-boot-starter-security` dependency already added
- JWT dependencies ready (jjwt-api, jjwt-impl, jjwt-jackson)
- But Spring Security is blocking everything with default config!

**The Problem:**
When you add `spring-boot-starter-security`, Spring Boot auto-configures:
- Basic authentication for all endpoints
- A generated password printed in logs
- No CORS support
- Default form login page

```
Using generated security password: 8a2d3c1f-4567-89ab-cdef-0123456789ab
```

**Goal:**
- Disable default security temporarily (for development)
- Configure CORS for frontend communication
- Set up password encoder for future authentication
- Create foundation for JWT-based authentication

**Teaching Point:** We're building the security infrastructure first. Even though we permit all requests now, we're setting up the pieces (encoder, CORS, session management) that we'll need when we add JWT authentication.

---

## Decision 1: Password Encoder with BCrypt

### Why BCrypt?

**Never Store Plain Text Passwords:**
```java
// NEVER DO THIS!
user.setPassword("myPassword123");
userRepository.save(user);

// If database is breached, all passwords exposed!
```

**Always Hash Passwords:**
```java
// DO THIS!
String hashed = passwordEncoder.encode("myPassword123");
// Result: $2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/X4E7jVxQ3TvY7OxfW
user.setPassword(hashed);
userRepository.save(user);

// Even if database is breached, passwords are safe!
```

### BCrypt Properties

**Why BCrypt over other algorithms?**

| Algorithm | Speed | Security | Notes |
|-----------|-------|----------|-------|
| MD5 | Very Fast | Weak | Broken, never use for passwords |
| SHA-256 | Fast | Moderate | Too fast, easy to brute force |
| BCrypt | Slow (configurable) | Strong | Designed for passwords |
| Argon2 | Slow | Strongest | Newer, good alternative |
| PBKDF2 | Slow | Strong | NIST recommended |

**BCrypt is designed to be slow.** This is a feature, not a bug!

- Fast hash = Attacker can try billions of passwords per second
- Slow hash = Attacker can only try thousands per second
- BCrypt's "work factor" controls how slow

### Understanding BCrypt Strength

```java
new BCryptPasswordEncoder(12);  // Strength = 12
```

**What is Strength (Work Factor)?**
- Number of iterations = 2^strength
- Strength 12 = 2^12 = 4,096 iterations
- Each increment doubles the time to hash

**Strength Comparison:**

| Strength | Iterations | Time per Hash | Use Case |
|----------|------------|---------------|----------|
| 4 | 16 | ~1ms | Never use (too fast) |
| 10 | 1,024 | ~100ms | Minimum recommended |
| 12 | 4,096 | ~250-400ms | Good balance (our choice) |
| 14 | 16,384 | ~1 second | High security |
| 16 | 65,536 | ~4 seconds | Paranoid level |

**Why We Chose 12:**
- Takes ~250-400ms to hash (good protection against brute force)
- Acceptable user experience for login/registration
- Industry standard for web applications
- Can increase later as hardware gets faster

**Teaching Point:** Password hashing should be slow enough to frustrate attackers but fast enough for good UX. 250ms is imperceptible to users but devastating to attackers.

### BCrypt Output Explained

```
$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/X4E7jVxQ3TvY7OxfW
 │  │  └─────────────────────────────────────────────────────────┘
 │  │                              Hash + Salt
 │  └── Strength/Cost (12)
 └───── Algorithm version (2a = BCrypt)
```

**Components:**
- `$2a$` - Algorithm identifier (BCrypt version 2a)
- `$12$` - Work factor (strength)
- `LQv3c1yqBWVHxkd0LHAkCO` - Salt (22 characters, auto-generated)
- `Yz6TtxMQJqhN8/X4E7jVxQ3TvY7OxfW` - Hash (31 characters)

**Teaching Point:** BCrypt includes the salt in the output. No need for separate salt column! Each password gets a unique random salt, so identical passwords produce different hashes.

### Password Verification

```java
// Registration: Hash and store
String rawPassword = "myPassword123";
String hashedPassword = passwordEncoder.encode(rawPassword);
user.setPassword(hashedPassword);

// Login: Compare
boolean matches = passwordEncoder.matches("myPassword123", user.getPassword());
// Returns true if password matches

boolean wrong = passwordEncoder.matches("wrongPassword", user.getPassword());
// Returns false
```

**Why `matches()` instead of comparing hashes?**
- BCrypt generates random salt for each hash
- Same password produces different hash each time
- `matches()` extracts salt from stored hash and re-computes

---

## Decision 2: Security Filter Chain Configuration

### What is SecurityFilterChain?

**Spring Security works through filters:**
```
HTTP Request
    │
    ▼
┌─────────────────────────────────────────────────┐
│           Security Filter Chain                 │
├─────────────────────────────────────────────────┤
│  1. CorsFilter          (CORS handling)         │
│  2. CsrfFilter          (CSRF protection)       │
│  3. AuthenticationFilter (Login/JWT)            │
│  4. AuthorizationFilter (Access control)        │
│  5. ExceptionTranslation (Error handling)       │
│  ... 10+ more filters ...                       │
└─────────────────────────────────────────────────┘
    │
    ▼
Controller/Endpoint
```

**Teaching Point:** Every HTTP request passes through these filters. By configuring `SecurityFilterChain`, we control what each filter does.

### Our Configuration Explained

```java
@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session
                    .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    .anyRequest().permitAll());

    return http.build();
}
```

**Line by line:**

#### 1. CORS Configuration
```java
.cors(cors -> cors.configurationSource(corsConfigurationSource()))
```
- Enables CORS filter
- Uses our custom `CorsConfigurationSource` bean
- Allows frontend (different origin) to call our API

#### 2. CSRF Disabled
```java
.csrf(csrf -> csrf.disable())
```
- Disables Cross-Site Request Forgery protection
- **Why disable?** We're building a stateless REST API with JWT
- CSRF protection is for session-based apps with cookies
- JWT in Authorization header is immune to CSRF attacks

**Teaching Point:** CSRF attacks exploit the browser automatically sending cookies. Since JWT is sent manually in headers (not automatically by browser), CSRF isn't a threat.

#### 3. Stateless Sessions
```java
.sessionManagement(session -> session
        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
```
- Tells Spring Security not to create HTTP sessions
- No session = No server-side state = Better scalability
- Every request must include JWT token for authentication

**Session Policies:**

| Policy | Description | Use Case |
|--------|-------------|----------|
| ALWAYS | Always create session | Traditional web apps |
| IF_REQUIRED | Create if needed (default) | Mixed apps |
| NEVER | Don't create, but use if exists | Legacy support |
| STATELESS | Never create or use | REST APIs, JWT |

#### 4. Permit All Requests (Temporary)
```java
.authorizeHttpRequests(auth -> auth
        .anyRequest().permitAll())
```
- Currently allows all requests without authentication
- **This is temporary!** For initial development/testing
- Will be changed when we add JWT authentication

**Future Configuration (Preview):**
```java
.authorizeHttpRequests(auth -> auth
        .requestMatchers("/api/auth/**").permitAll()
        .requestMatchers("/api/public/**").permitAll()
        .requestMatchers(HttpMethod.GET, "/api/posts/**").permitAll()
        .requestMatchers("/api/admin/**").hasRole("ADMIN")
        .anyRequest().authenticated())
```

### Spring Security 6.x Lambda DSL

**Old Style (Spring Security 5.x):**
```java
http
    .cors().and()
    .csrf().disable()
    .sessionManagement()
        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
        .and()
    .authorizeRequests()
        .anyRequest().permitAll();
```

**New Style (Spring Security 6.x - What We Use):**
```java
http
    .cors(cors -> cors.configurationSource(corsConfigurationSource()))
    .csrf(csrf -> csrf.disable())
    .sessionManagement(session -> session
            .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
    .authorizeHttpRequests(auth -> auth
            .anyRequest().permitAll());
```

**Why Lambda DSL?**
- More readable (each config in its own block)
- Better IDE support (autocomplete within lambdas)
- Required in Spring Security 6.x (old style deprecated)
- Explicit about what's being configured

---

## Decision 3: CORS Configuration

### What is CORS?

**Cross-Origin Resource Sharing** - Browser security feature that blocks requests from different origins.

**Same Origin:**
```
Frontend: http://localhost:3000
Backend:  http://localhost:3000  ✓ Same origin
```

**Different Origin (CORS needed):**
```
Frontend: http://localhost:3000
Backend:  http://localhost:8080  ✗ Different port = Different origin
```

**Without CORS Config:**
```
Browser Console Error:
Access to fetch at 'http://localhost:8080/api/users' from origin
'http://localhost:3000' has been blocked by CORS policy:
No 'Access-Control-Allow-Origin' header is present.
```

### Our CORS Configuration Explained

```java
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
```

#### Allowed Origins
```java
configuration.setAllowedOrigins(List.of("http://localhost:3000", "http://localhost:5173"));
```
- `localhost:3000` - React default dev server (Create React App, Next.js)
- `localhost:5173` - Vite default dev server (Vue, React with Vite)
- **Production:** Replace with actual domain (`https://myblog.com`)

**Security Warning:** Never use `*` for allowed origins in production with credentials!

#### Allowed Methods
```java
configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
```
- `GET` - Read data
- `POST` - Create data
- `PUT` - Replace data
- `DELETE` - Remove data
- `PATCH` - Partial update
- `OPTIONS` - Preflight requests (browser sends before actual request)

#### Allowed Headers
```java
configuration.setAllowedHeaders(List.of("*"));
```
- Accepts any header from frontend
- Includes `Content-Type`, `Authorization`, custom headers
- Could be more restrictive: `List.of("Content-Type", "Authorization")`

#### Exposed Headers
```java
configuration.setExposedHeaders(List.of("Authorization"));
```
- **Critical for JWT!**
- By default, browsers only expose "simple" headers to JavaScript
- `Authorization` header with JWT must be explicitly exposed
- Frontend can read: `response.headers.get('Authorization')`

#### Allow Credentials
```java
configuration.setAllowCredentials(true);
```
- Allows cookies and Authorization headers
- Required for JWT in `Authorization` header
- **Note:** Cannot use `*` for origins when credentials are true

#### Max Age (Preflight Cache)
```java
configuration.setMaxAge(3600L);
```
- Cache preflight responses for 1 hour (3600 seconds)
- Reduces OPTIONS requests for same endpoint
- Browser caches "this origin can access this endpoint"

### CORS Preflight Flow

```
1. Frontend wants to POST to /api/users

2. Browser sends OPTIONS request (preflight):
   OPTIONS /api/users
   Origin: http://localhost:3000
   Access-Control-Request-Method: POST
   Access-Control-Request-Headers: Content-Type, Authorization

3. Server responds:
   Access-Control-Allow-Origin: http://localhost:3000
   Access-Control-Allow-Methods: GET, POST, PUT, DELETE, PATCH, OPTIONS
   Access-Control-Allow-Headers: *
   Access-Control-Max-Age: 3600

4. Browser caches response, sends actual POST request

5. Browser allows frontend to read response
```

**Teaching Point:** Preflight only happens for "complex" requests (POST with JSON, custom headers). Simple GET requests don't trigger preflight.

---

## Decision 4: Using @EnableWebSecurity

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
```

### What Does @EnableWebSecurity Do?

**Without @EnableWebSecurity:**
- Spring Boot auto-configuration applies
- Default security rules (all endpoints protected)
- Generated password
- Form login enabled

**With @EnableWebSecurity:**
- Disables auto-configuration
- Your `SecurityFilterChain` bean takes full control
- No default password
- No form login (unless you configure it)

**Teaching Point:** `@EnableWebSecurity` says "I'll handle security myself." Spring Boot steps back and lets your configuration take over completely.

### Order of Precedence

```
1. @EnableWebSecurity + your SecurityFilterChain → Full control
2. No @EnableWebSecurity but SecurityFilterChain bean → Merges with defaults
3. No configuration at all → Spring Boot defaults
```

---

## What Works Now After This Commit?

### Application Capabilities

**We Can Now:**

1. **Access All Endpoints Without Authentication:**
```bash
curl http://localhost:8080/api/anything
# Works! No 401 Unauthorized
```

2. **Make Requests from Frontend:**
```javascript
// From React app on localhost:3000
fetch('http://localhost:8080/api/users')
    .then(response => response.json())
    .then(data => console.log(data));
// Works! No CORS error
```

3. **Hash Passwords (When We Need To):**
```java
@Autowired
private PasswordEncoder passwordEncoder;

String hashed = passwordEncoder.encode("myPassword123");
// Returns BCrypt hash with strength 12
```

4. **Verify Passwords:**
```java
boolean valid = passwordEncoder.matches("myPassword123", hashedPassword);
// Returns true if matches
```

### What Still Doesn't Work

**We Still Cannot:**
- **Authenticate users** (no JWT service, no authentication filter)
- **Protect endpoints** (everything is permitAll)
- **Register users** (no DTOs, no service, no controller)
- **Login users** (no authentication endpoint)

**Next Steps:**
1. Create DTOs (RegisterRequest, LoginRequest, AuthResponse)
2. Create JwtService (token generation/validation)
3. Create JwtAuthenticationFilter
4. Create UserService with authentication logic
5. Create AuthController
6. Update SecurityFilterChain with proper authorization rules

---

## Teaching Talking Points

### For Students New to Spring Security

**Analogy:**
"Think of Spring Security as a bouncer at a club:
- The bouncer checks everyone at the door (Filter Chain)
- Currently, we told the bouncer to let everyone in (permitAll)
- Later, we'll give the bouncer a guest list (JWT validation)
- We also set up the password vault (BCrypt) for when we need it"

**Why Configure Now?**
- Need CORS working for frontend development
- Need password encoder before creating users
- Building foundation incrementally

### For Students Asking "Why Disable Security?"

**It's Not Disabled - It's Configured!**

We still have:
- CORS protection (only allowed origins)
- No session hijacking risk (stateless)
- Password encoding ready

We temporarily removed:
- Authentication requirement (will add with JWT)
- Authorization rules (will add endpoint-specific rules)

**Teaching Point:** "permitAll" doesn't mean "no security." It means "no authentication required." CORS, HTTPS, input validation are still security layers.

### Common Mistakes to Avoid

**1. Using permitAll in Production:**
```java
// DON'T keep this forever!
.anyRequest().permitAll()

// DO evolve to proper rules:
.requestMatchers("/api/auth/**").permitAll()
.anyRequest().authenticated()
```

**2. CORS with Wildcard and Credentials:**
```java
// DON'T - Browser will reject!
configuration.setAllowedOrigins(List.of("*"));
configuration.setAllowCredentials(true);

// DO - Specific origins with credentials:
configuration.setAllowedOrigins(List.of("http://localhost:3000"));
configuration.setAllowCredentials(true);
```

**3. Weak BCrypt Strength:**
```java
// DON'T - Too fast, easy to brute force
new BCryptPasswordEncoder(4);

// DO - Use 10 or higher
new BCryptPasswordEncoder(12);
```

**4. Storing Raw Passwords:**
```java
// NEVER!
user.setPassword(request.getPassword());

// ALWAYS hash!
user.setPassword(passwordEncoder.encode(request.getPassword()));
```

---

## Architectural Decisions Record

**ADR-010: Use BCrypt with Strength 12 for Password Hashing**
- **Status:** Accepted
- **Context:** Need secure password storage for user authentication
- **Decision:** BCrypt with strength/cost factor of 12
- **Consequences:**
    - ~250-400ms per hash operation (acceptable UX)
    - Strong protection against brute force attacks
    - Industry standard, well-tested algorithm
    - Can increase strength later as hardware improves

**ADR-011: Stateless Session Management for REST API**
- **Status:** Accepted
- **Context:** Building REST API with JWT authentication
- **Decision:** Use `SessionCreationPolicy.STATELESS`
- **Consequences:**
    - No server-side session storage
    - Better horizontal scalability
    - Every request must include JWT
    - Cannot use session-based features

**ADR-012: CORS Configuration for Local Development**
- **Status:** Accepted
- **Context:** Frontend runs on different port during development
- **Decision:** Allow localhost:3000 and localhost:5173 origins
- **Consequences:**
    - Frontend can communicate with backend
    - Must update for production domains
    - Credentials enabled for Authorization header

**ADR-013: Temporary PermitAll for Initial Development**
- **Status:** Temporary (will be revised)
- **Context:** Need working endpoints before JWT implementation
- **Decision:** Allow all requests without authentication
- **Consequences:**
    - Faster development iteration
    - **Must be changed before production!**
    - Will be updated when JWT authentication is added

---

## Security Checklist for Future

Before going to production, ensure:

- [ ] Replace permitAll with proper authorization rules
- [ ] Add JWT authentication filter
- [ ] Update CORS origins to production domain
- [ ] Enable HTTPS (TLS)
- [ ] Add rate limiting
- [ ] Configure security headers (CSP, HSTS, etc.)
- [ ] Implement account lockout after failed attempts
- [ ] Add audit logging for security events
- [ ] Review and test all endpoints

---

## Commit Stats
- **Files created:** 1
    - SecurityConfig.java (49 lines)
- **Beans defined:** 3
    - PasswordEncoder (BCryptPasswordEncoder, strength 12)
    - SecurityFilterChain (stateless, permitAll)
    - CorsConfigurationSource (localhost origins)
- **Security features configured:**
    - Password hashing ready
    - CORS enabled
    - CSRF disabled (REST API)
    - Stateless sessions

---

## Resources for Students
- Spring Security Reference: https://docs.spring.io/spring-security/reference/index.html
- BCrypt Explained: https://auth0.com/blog/hashing-in-action-understanding-bcrypt/
- CORS in Spring: https://www.baeldung.com/spring-cors
- Spring Security 6 Migration: https://docs.spring.io/spring-security/reference/migration/index.html
- OWASP Password Storage: https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html
- JWT Security Best Practices: https://auth0.com/blog/jwt-security-best-practices/