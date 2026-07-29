# Commit 10: Create AuthService for Authentication

**Date:** February 1, 2026
**Commit Message:** Create AuthService for Authentication

---

## Development Mindset & Decision Process

### The Authentication Business Logic Layer

**Current State:**
- JwtUtil ready (token generation/validation)
- UserService ready (user CRUD operations)
- Authentication DTOs ready (request/response structures)
- PasswordEncoder configured (BCrypt in SecurityConfig)
- But no service to orchestrate authentication flow!

**Goal:**
- Create AuthService interface (contract)
- Create AuthServiceImpl (implementation)
- Implement register flow (create user, hash password, generate tokens)
- Implement login flow (verify credentials, generate tokens)
- Implement refresh flow (validate refresh token, generate new tokens)

**Teaching Point:** AuthService orchestrates multiple components (UserService, PasswordEncoder, JwtUtil) to implement authentication flows. This is the "conductor" that coordinates the authentication "orchestra."

---

## Decision 1: Authentication Flow Design

### Registration Flow

```
RegisterRequest → AuthService.register() → AuthResponse

┌─────────────────────────────────────────────────────────────────┐
│                    REGISTRATION FLOW                            │
├─────────────────────────────────────────────────────────────────┤
│  1. Receive RegisterRequest (email, password, name)             │
│                         ↓                                       │
│  2. Check if email exists → userService.existsByEmail()         │
│     └── If exists: throw "Email already registered"             │
│                         ↓                                       │
│  3. Hash password → passwordEncoder.encode()                    │
│                         ↓                                       │
│  4. Create User entity with:                                    │
│     - email, hashed password, name                              │
│     - role = SUBSCRIBER (default)                               │
│     - emailVerified = false                                     │
│     - isDeleted = false                                         │
│                         ↓                                       │
│  5. Save user → userService.createUser()                        │
│                         ↓                                       │
│  6. Generate tokens → jwtUtil.generateAccessToken()             │
│                     → jwtUtil.generateRefreshToken()            │
│                         ↓                                       │
│  7. Return AuthResponse (tokens + user info)                    │
└─────────────────────────────────────────────────────────────────┘
```

### Login Flow

```
LoginRequest → AuthService.login() → AuthResponse

┌─────────────────────────────────────────────────────────────────┐
│                      LOGIN FLOW                                 │
├─────────────────────────────────────────────────────────────────┤
│  1. Receive LoginRequest (email, password)                      │
│                         ↓                                       │
│  2. Find user by email → userService.findByEmail()              │
│     └── If not found: throw "Invalid email or password"         │
│                         ↓                                       │
│  3. Verify password → passwordEncoder.matches()                 │
│     └── If mismatch: throw "Invalid email or password"          │
│                         ↓                                       │
│  4. Generate tokens → jwtUtil.generateAccessToken()             │
│                     → jwtUtil.generateRefreshToken()            │
│                         ↓                                       │
│  5. Return AuthResponse (tokens + user info)                    │
└─────────────────────────────────────────────────────────────────┘
```

### Refresh Token Flow

```
RefreshTokenRequest → AuthService.refreshToken() → AuthResponse

┌─────────────────────────────────────────────────────────────────┐
│                    REFRESH TOKEN FLOW                           │
├─────────────────────────────────────────────────────────────────┤
│  1. Receive RefreshTokenRequest (refreshToken)                  │
│                         ↓                                       │
│  2. Validate token → jwtUtil.validateToken()                    │
│     └── If invalid/expired: throw "Invalid or expired"          │
│                         ↓                                       │
│  3. Verify token type is "refresh"                              │
│     └── If not refresh: throw "Invalid token type"              │
│                         ↓                                       │
│  4. Extract user ID → jwtUtil.getUserIdFromToken()              │
│                         ↓                                       │
│  5. Find user → userService.findById()                          │
│     └── If not found (deleted?): throw "User not found"         │
│                         ↓                                       │
│  6. Generate NEW tokens (both access and refresh)               │
│                         ↓                                       │
│  7. Return AuthResponse (new tokens + user info)                │
└─────────────────────────────────────────────────────────────────┘
```

---

## Decision 2: Password Handling

### Why Hash Passwords?

**Never store plain text passwords!**

```java
// DON'T - Plain text storage
user.setPassword(request.getPassword());

// DO - Hash with BCrypt
user.setPassword(passwordEncoder.encode(request.getPassword()));
```

**If database is breached:**
- Plain text: Attacker has all passwords immediately
- Hashed: Attacker has hashes, needs to crack each one

### BCrypt Deep Dive

**How BCrypt Works:**
```
Input: "password123"
                ↓
BCrypt.encode() with random salt + work factor
                ↓
Output: "$2a$10$N9qo8uLOickgx2ZMRZoMy.Mrq4A4bGqF2QLBdE2dE4cC3f3E7xC7e"
         └─┬─┘└─┘└────────────────────┬────────────────────────────┘
        Version  Work Factor (2^10 = 1024 iterations)    Hash + Salt
```

**Why BCrypt?**
| Feature | Benefit |
|---------|---------|
| Adaptive work factor | Slows down as hardware improves |
| Built-in salt | Each password has unique salt |
| Slow by design | Prevents brute force attacks |
| Industry standard | Well-tested, no known vulnerabilities |

### Password Verification

```java
// Login verification
if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
    throw new RuntimeException("Invalid email or password");
}
```

**How matches() works:**
1. Extract salt from stored hash
2. Hash input password with same salt
3. Compare result with stored hash
4. Return true if equal

**Teaching Point:** Never compare passwords directly. Always use `passwordEncoder.matches()` which handles salt extraction and secure comparison.

---

## Decision 3: Error Messages for Security

### Ambiguous Login Errors

```java
// In login method
User user = userService.findByEmail(request.getEmail())
        .orElseThrow(() -> new RuntimeException("Invalid email or password"));

if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
    throw new RuntimeException("Invalid email or password");
}
```

**Same error for both cases!**

**Why not specific errors?**

```java
// DON'T - Reveals which part is wrong
throw new RuntimeException("Email not found");      // Attacker knows email doesn't exist
throw new RuntimeException("Password incorrect");   // Attacker knows email exists
```

**Security implications:**
- Specific errors enable username enumeration
- Attackers can build list of valid emails
- Then focus password attacks on known accounts

**Teaching Point:** For authentication, always use generic error messages. "Invalid credentials" is better than "User not found" or "Wrong password."

---

## Decision 4: Token Type Verification

### Preventing Token Confusion

```java
// In refreshToken method
Claims claims = jwtUtil.extractAllClaims(refreshToken);
String tokenType = claims.get("type", String.class);
if (!"refresh".equals(tokenType)) {
    throw new RuntimeException("Invalid token type");
}
```

**Why check token type?**

Without this check:
```http
POST /api/auth/refresh
{
    "refreshToken": "<access_token_here>"  // Attacker uses access token!
}
```

The access token is still a valid JWT signed by us, so `validateToken()` passes. But it has a short expiration (1 hour), not 30 days.

**Attack scenario:**
1. Attacker steals access token (easier to intercept - sent with every request)
2. Uses it to get fresh tokens via refresh endpoint
3. Maintains persistent access

**With type checking:**
- Access tokens have `type: "access"`
- Refresh tokens have `type: "refresh"`
- Refresh endpoint rejects access tokens

---

## Decision 5: Default Role Assignment

### New Users Get SUBSCRIBER Role

```java
User user = User.builder()
        .email(request.getEmail())
        .password(passwordEncoder.encode(request.getPassword()))
        .name(request.getName())
        .role(Role.SUBSCRIBER)  // Default role
        .emailVerified(false)
        .isDeleted(false)
        .build();
```

**Role Hierarchy:**
```
ADMIN      (highest) - Full system access
    ↓
AUTHOR     (middle)  - Create content, AI features
    ↓
SUBSCRIBER (lowest)  - Read content, comment
```

**Why SUBSCRIBER as default?**
1. **Least privilege principle** - Start with minimal access
2. **Business model** - AUTHOR is paid tier
3. **Safety** - Can't accidentally grant admin

**Upgrading roles (future):**
```java
// Admin endpoint to upgrade user
@PreAuthorize("hasRole('ADMIN')")
public void promoteToAuthor(Long userId) {
    User user = userService.findById(userId).orElseThrow();
    user.setRole(Role.AUTHOR);
    userService.updateUser(userId, user);
}
```

---

## Decision 6: Token Refresh Strategy

### Full Token Rotation

```java
@Override
public AuthResponse refreshToken(RefreshTokenRequest request) {
    // ... validation ...

    // Generate NEW tokens (both!)
    return buildAuthResponse(user);  // Creates new access AND refresh tokens
}
```

**We issue both new access and refresh tokens.**

**Why rotate refresh token?**

**Without rotation:**
```
Day 1: User gets refresh token (valid 30 days)
Day 15: Token stolen
Day 15-30: Attacker uses same refresh token
Result: Attacker has 15 days of access
```

**With rotation:**
```
Day 1: User gets refresh token A
Day 15: Token A stolen, user refreshes with A, gets token B
Day 16: User refreshes with B, gets token C
Day 16: Attacker tries to use A... REJECTED (user now has C)
```

**Teaching Point:** Refresh token rotation limits the window of opportunity for stolen tokens. Each refresh invalidates the previous token (conceptually - we're not storing tokens yet).

---

## Decision 7: ExpiresIn Calculation

### Milliseconds to Seconds

```java
@Value("${jwt.access-token-expiration}")
private long accessTokenExpiration;  // In milliseconds (from properties)

return AuthResponse.builder()
        .expiresIn(accessTokenExpiration / 1000)  // Convert to seconds
        .build();
```

**Why seconds in response?**
- OAuth2 convention uses seconds
- JavaScript `setTimeout` uses milliseconds (multiply by 1000)
- Easier mental math (3600 seconds = 1 hour)

**Client usage:**
```javascript
const response = await api.login(credentials);
const { accessToken, expiresIn } = response;

// Set refresh timer (refresh 1 minute before expiration)
setTimeout(() => {
    refreshTokens();
}, (expiresIn - 60) * 1000);  // Convert back to ms
```

---

## What Works Now After This Commit?

### Complete Authentication Logic

**Registration:**
```java
@Autowired
private AuthService authService;

RegisterRequest request = RegisterRequest.builder()
        .email("user@example.com")
        .password("securePassword123")
        .name("John Doe")
        .build();

AuthResponse response = authService.register(request);
// response.getAccessToken() → "eyJhbG..."
// response.getRefreshToken() → "eyJhbG..."
// response.getUser().getRole() → SUBSCRIBER
```

**Login:**
```java
LoginRequest request = LoginRequest.builder()
        .email("user@example.com")
        .password("securePassword123")
        .build();

AuthResponse response = authService.login(request);
// Returns tokens + user info
```

**Refresh:**
```java
RefreshTokenRequest request = RefreshTokenRequest.builder()
        .refreshToken("eyJhbG...")
        .build();

AuthResponse response = authService.refreshToken(request);
// Returns fresh tokens + user info
```

### Service Dependencies

```
AuthServiceImpl
    ├── UserService      (find users, check existence, create users)
    ├── PasswordEncoder  (hash and verify passwords)
    └── JwtUtil          (generate and validate tokens)
```

### What Still Doesn't Work

- No REST endpoints (AuthController needed)
- No validation error handling (GlobalExceptionHandler needed)
- No custom exceptions (using RuntimeException)
- No request authentication (JwtAuthenticationFilter needed)
- No endpoint protection (SecurityFilterChain updates needed)

**Next Steps:**
1. Create GlobalExceptionHandler
2. Create custom exceptions (EmailAlreadyExistsException, etc.)
3. Create AuthController
4. Create JwtAuthenticationFilter
5. Update SecurityFilterChain

---

## Security Checklist

### What We Handle

| Security Concern | How We Handle It |
|------------------|------------------|
| Password storage | BCrypt hashing with salt |
| Username enumeration | Generic "Invalid email or password" error |
| Token confusion | Check token type claim |
| Expired tokens | JwtUtil.validateToken() checks expiration |
| Default privileges | New users get SUBSCRIBER role |
| Token refresh | Full rotation (new access + refresh) |

### What We Don't Handle Yet

| Concern | Future Solution |
|---------|-----------------|
| Rate limiting | Spring Security rate limiter or Redis |
| Account lockout | Track failed attempts, lock after N failures |
| Token revocation | Store tokens in Redis, blacklist on logout |
| Refresh token storage | Store in DB for revocation capability |
| Password requirements | Enhanced validation (complexity rules) |
| MFA | Add TOTP/SMS verification layer |

---

## Teaching Talking Points

### For Students New to Auth Services

**Analogy - Airport Security:**
```
Registration = Getting a passport
- Verify identity (email uniqueness)
- Issue documents (tokens)
- Set access level (SUBSCRIBER visa)

Login = Airport check-in
- Verify identity (email + password)
- Issue boarding pass (access token)
- Issue luggage ticket (refresh token)

Token Refresh = Extending visa
- Verify current documents (refresh token)
- Issue new documents (new tokens)
- Old documents no longer valid (rotation)
```

### Common Security Mistakes

**1. Logging Passwords:**
```java
// DON'T
log.info("User {} logging in with password {}", email, password);

// DO
log.info("User {} attempting login", email);
```

**2. Comparing Passwords Directly:**
```java
// DON'T
if (request.getPassword().equals(user.getPassword()))

// DO
if (passwordEncoder.matches(request.getPassword(), user.getPassword()))
```

**3. Specific Auth Errors:**
```java
// DON'T
throw new RuntimeException("User " + email + " not found");

// DO
throw new RuntimeException("Invalid email or password");
```

**4. Not Checking Token Type:**
```java
// DON'T
if (jwtUtil.validateToken(refreshToken)) {
    // Accept any valid token
}

// DO
if (jwtUtil.validateToken(refreshToken) && isRefreshToken(refreshToken)) {
    // Only accept refresh tokens
}
```

---

## Architectural Decisions Record

**ADR-026: BCrypt for Password Hashing**
- **Status:** Accepted
- **Context:** Need secure password storage
- **Decision:** Use Spring Security's BCryptPasswordEncoder
- **Consequences:**
    - Industry standard, proven secure
    - Adaptive work factor for future hardware
    - Slower than alternatives (by design)

**ADR-027: Generic Authentication Error Messages**
- **Status:** Accepted
- **Context:** Need to prevent username enumeration
- **Decision:** Use "Invalid email or password" for all auth failures
- **Consequences:**
    - Better security (no information leakage)
    - Slightly worse UX (user doesn't know which field is wrong)
    - Accept trade-off for security

**ADR-028: Full Token Rotation on Refresh**
- **Status:** Accepted
- **Context:** Need to limit stolen token lifetime
- **Decision:** Issue both new access and refresh tokens on refresh
- **Consequences:**
    - Better security (old tokens become useless)
    - More tokens generated (minimal overhead)
    - Requires client to store new refresh token

**ADR-029: SUBSCRIBER as Default Role**
- **Status:** Accepted
- **Context:** Need default role for new users
- **Decision:** All new registrations get SUBSCRIBER role
- **Consequences:**
    - Follows least privilege principle
    - Supports freemium business model
    - Requires explicit promotion for elevated access

---

## Commit Stats
- **Files created:** 2
    - AuthService.java (interface, 35 lines)
    - AuthServiceImpl.java (implementation, 107 lines)
- **Methods implemented:** 3 + 1 helper
    - register(RegisterRequest)
    - login(LoginRequest)
    - refreshToken(RefreshTokenRequest)
    - buildAuthResponse(User) - private helper
- **Dependencies used:**
    - UserService (user operations)
    - PasswordEncoder (BCrypt)
    - JwtUtil (token operations)

---

## Testing the AuthService

### Unit Test Example

```java
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private UserService userService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtUtil jwtUtil;

    @InjectMocks
    private AuthServiceImpl authService;

    @Test
    void register_shouldCreateUserAndReturnTokens() {
        RegisterRequest request = RegisterRequest.builder()
                .email("test@example.com")
                .password("password123")
                .name("Test User")
                .build();

        User savedUser = User.builder()
                .id(1L)
                .email("test@example.com")
                .name("Test User")
                .role(Role.SUBSCRIBER)
                .emailVerified(false)
                .build();

        when(userService.existsByEmail("test@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashedPassword");
        when(userService.createUser(any(User.class))).thenReturn(savedUser);
        when(jwtUtil.generateAccessToken(savedUser)).thenReturn("accessToken");
        when(jwtUtil.generateRefreshToken(savedUser)).thenReturn("refreshToken");

        AuthResponse response = authService.register(request);

        assertThat(response.getAccessToken()).isEqualTo("accessToken");
        assertThat(response.getRefreshToken()).isEqualTo("refreshToken");
        assertThat(response.getUser().getEmail()).isEqualTo("test@example.com");
        assertThat(response.getUser().getRole()).isEqualTo(Role.SUBSCRIBER);

        verify(userService).existsByEmail("test@example.com");
        verify(passwordEncoder).encode("password123");
        verify(userService).createUser(any(User.class));
    }

    @Test
    void register_shouldThrow_whenEmailExists() {
        RegisterRequest request = RegisterRequest.builder()
                .email("existing@example.com")
                .password("password123")
                .name("Test User")
                .build();

        when(userService.existsByEmail("existing@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Email already registered");

        verify(userService, never()).createUser(any());
    }

    @Test
    void login_shouldReturnTokens_whenCredentialsValid() {
        LoginRequest request = LoginRequest.builder()
                .email("test@example.com")
                .password("password123")
                .build();

        User user = User.builder()
                .id(1L)
                .email("test@example.com")
                .password("hashedPassword")
                .name("Test User")
                .role(Role.SUBSCRIBER)
                .build();

        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "hashedPassword")).thenReturn(true);
        when(jwtUtil.generateAccessToken(user)).thenReturn("accessToken");
        when(jwtUtil.generateRefreshToken(user)).thenReturn("refreshToken");

        AuthResponse response = authService.login(request);

        assertThat(response.getAccessToken()).isEqualTo("accessToken");
        assertThat(response.getRefreshToken()).isEqualTo("refreshToken");
    }

    @Test
    void login_shouldThrow_whenUserNotFound() {
        LoginRequest request = LoginRequest.builder()
                .email("notfound@example.com")
                .password("password123")
                .build();

        when(userService.findByEmail("notfound@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Invalid email or password");
    }

    @Test
    void login_shouldThrow_whenPasswordWrong() {
        LoginRequest request = LoginRequest.builder()
                .email("test@example.com")
                .password("wrongPassword")
                .build();

        User user = User.builder()
                .id(1L)
                .email("test@example.com")
                .password("hashedPassword")
                .build();

        when(userService.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongPassword", "hashedPassword")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Invalid email or password");
    }

    @Test
    void refreshToken_shouldReturnNewTokens_whenRefreshTokenValid() {
        RefreshTokenRequest request = RefreshTokenRequest.builder()
                .refreshToken("validRefreshToken")
                .build();

        User user = User.builder()
                .id(1L)
                .email("test@example.com")
                .name("Test User")
                .role(Role.SUBSCRIBER)
                .build();

        Claims claims = mock(Claims.class);
        when(claims.get("type", String.class)).thenReturn("refresh");

        when(jwtUtil.validateToken("validRefreshToken")).thenReturn(true);
        when(jwtUtil.extractAllClaims("validRefreshToken")).thenReturn(claims);
        when(jwtUtil.getUserIdFromToken("validRefreshToken")).thenReturn(1L);
        when(userService.findById(1L)).thenReturn(Optional.of(user));
        when(jwtUtil.generateAccessToken(user)).thenReturn("newAccessToken");
        when(jwtUtil.generateRefreshToken(user)).thenReturn("newRefreshToken");

        AuthResponse response = authService.refreshToken(request);

        assertThat(response.getAccessToken()).isEqualTo("newAccessToken");
        assertThat(response.getRefreshToken()).isEqualTo("newRefreshToken");
    }

    @Test
    void refreshToken_shouldThrow_whenTokenInvalid() {
        RefreshTokenRequest request = RefreshTokenRequest.builder()
                .refreshToken("invalidToken")
                .build();

        when(jwtUtil.validateToken("invalidToken")).thenReturn(false);

        assertThatThrownBy(() -> authService.refreshToken(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Invalid or expired refresh token");
    }

    @Test
    void refreshToken_shouldThrow_whenAccessTokenUsed() {
        RefreshTokenRequest request = RefreshTokenRequest.builder()
                .refreshToken("accessTokenMisused")
                .build();

        Claims claims = mock(Claims.class);
        when(claims.get("type", String.class)).thenReturn("access");  // Wrong type!

        when(jwtUtil.validateToken("accessTokenMisused")).thenReturn(true);
        when(jwtUtil.extractAllClaims("accessTokenMisused")).thenReturn(claims);

        assertThatThrownBy(() -> authService.refreshToken(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Invalid token type");
    }
}
```

### Integration Test Example

```java
@SpringBootTest
@Transactional
class AuthServiceIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Test
    void fullAuthenticationFlow() {
        // 1. Register
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("integration@example.com")
                .password("password123")
                .name("Integration Test")
                .build();

        AuthResponse registerResponse = authService.register(registerRequest);

        assertThat(registerResponse.getAccessToken()).isNotBlank();
        assertThat(registerResponse.getRefreshToken()).isNotBlank();
        assertThat(registerResponse.getUser().getRole()).isEqualTo(Role.SUBSCRIBER);

        // 2. Login with same credentials
        LoginRequest loginRequest = LoginRequest.builder()
                .email("integration@example.com")
                .password("password123")
                .build();

        AuthResponse loginResponse = authService.login(loginRequest);

        assertThat(loginResponse.getAccessToken()).isNotBlank();
        assertThat(loginResponse.getUser().getEmail()).isEqualTo("integration@example.com");

        // 3. Refresh token
        RefreshTokenRequest refreshRequest = RefreshTokenRequest.builder()
                .refreshToken(loginResponse.getRefreshToken())
                .build();

        AuthResponse refreshResponse = authService.refreshToken(refreshRequest);

        assertThat(refreshResponse.getAccessToken()).isNotBlank();
        // New tokens should be different
        assertThat(refreshResponse.getAccessToken())
                .isNotEqualTo(loginResponse.getAccessToken());
    }

    @Test
    void register_shouldFailForDuplicateEmail() {
        RegisterRequest request = RegisterRequest.builder()
                .email("duplicate@example.com")
                .password("password123")
                .name("First User")
                .build();

        authService.register(request);

        RegisterRequest duplicateRequest = RegisterRequest.builder()
                .email("duplicate@example.com")
                .password("password456")
                .name("Second User")
                .build();

        assertThatThrownBy(() -> authService.register(duplicateRequest))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Email already registered");
    }
}
```

---

## Resources for Students
- BCrypt Explained: https://auth0.com/blog/hashing-in-action-understanding-bcrypt/
- OWASP Authentication: https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html
- JWT Best Practices: https://curity.io/resources/learn/jwt-best-practices/
- Spring Security Password Storage: https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html
- Token Refresh Strategies: https://auth0.com/blog/refresh-tokens-what-are-they-and-when-to-use-them/
