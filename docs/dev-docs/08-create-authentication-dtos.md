# Commit 8: Create Authentication DTOs

**Date:** February 1, 2026
**Commit Message:** Create Authentication DTOs

---

## Development Mindset & Decision Process

### The Data Transfer Layer

**Current State:**
- JWT utility ready (can generate/validate tokens)
- User entity and repository ready
- Security configuration in place
- But no way to receive authentication requests or send responses!

**Goal:**
- Create DTOs for registration requests
- Create DTOs for login requests
- Create DTOs for token refresh requests
- Create DTOs for authentication responses
- Establish validation patterns for user input

**Teaching Point:** DTOs (Data Transfer Objects) are the contract between client and server. They define exactly what data the API accepts and returns, separate from internal domain models. This separation allows us to:
1. Control what fields are exposed
2. Add validation at the API boundary
3. Change internal models without breaking the API

---

## Decision 1: Understanding DTOs vs Entities

### Why Not Use Entity Directly?

**Entity (User.java):**
```java
@Entity
public class User {
    private Long id;
    private String email;
    private String password;      // SECRET! Never expose
    private String name;
    private Role role;
    private Boolean emailVerified;
    private Boolean isDeleted;    // Internal flag
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

**Problems with exposing Entity:**
1. **Security Risk:** Password hash would be serialized to JSON
2. **Over-fetching:** Client receives internal fields (isDeleted, updatedAt)
3. **Tight Coupling:** API changes require entity changes
4. **Validation Mismatch:** Entity validation != input validation

**Solution: Separate DTOs**

```
Client Request → DTO (validated) → Service → Entity → Database
Database → Entity → Service → DTO (filtered) → Client Response
```

### The DTO Pattern

| Class | Purpose | Direction |
|-------|---------|-----------|
| RegisterRequest | Capture registration data | Client → Server |
| LoginRequest | Capture login credentials | Client → Server |
| RefreshTokenRequest | Capture refresh token | Client → Server |
| AuthResponse | Return tokens + user info | Server → Client |
| UserResponse | Return safe user data | Server → Client |

**Teaching Point:** Request DTOs are like forms - they collect specific data with validation. Response DTOs are like reports - they present curated data. Neither should be your entity!

---

## Decision 2: Request DTO Design

### RegisterRequest

```java
public class RegisterRequest {
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
    private String name;
}
```

**Field-by-Field Analysis:**

| Field | Validations | Reasoning |
|-------|-------------|-----------|
| email | @NotBlank, @Email | Required, must be valid email format |
| password | @NotBlank, @Size(min=8) | Required, minimum security standard |
| name | @NotBlank, @Size(min=2, max=100) | Required, reasonable length bounds |

**Why These Validation Rules?**

**Email:**
- `@NotBlank` - Catches null, empty, and whitespace-only
- `@Email` - Validates format (contains @, valid domain structure)

**Password (min 8 characters):**
- NIST guidelines recommend minimum 8 characters
- OWASP recommends 8+ characters
- Balances security with usability
- Note: Additional complexity rules (uppercase, numbers, symbols) can be added later

**Name:**
- `min = 2` - Prevents single-character names
- `max = 100` - Matches entity column constraint
- Allows international names, hyphenated names, etc.

### LoginRequest

```java
public class LoginRequest {
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "Password is required")
    private String password;
}
```

**Note:** No @Size on password for login! User might have an old account with shorter password. We only enforce password rules on registration/password change.

### RefreshTokenRequest

```java
public class RefreshTokenRequest {
    @NotBlank(message = "Refresh token is required")
    private String refreshToken;
}
```

**Minimal design** - only contains what's needed. The token itself will be validated by JwtUtil.

---

## Decision 3: Response DTO Design

### AuthResponse

```java
public class AuthResponse {
    private String accessToken;
    private String refreshToken;

    @Builder.Default
    private String tokenType = "Bearer";

    private Long expiresIn;
    private UserResponse user;
}
```

**Field Explanations:**

| Field | Type | Purpose |
|-------|------|---------|
| accessToken | String | JWT for API authentication |
| refreshToken | String | JWT for obtaining new access tokens |
| tokenType | String | Always "Bearer" (OAuth2 standard) |
| expiresIn | Long | Seconds until access token expires |
| user | UserResponse | Current user information |

**Why Include These Fields?**

**tokenType:**
```http
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
            └── This tells client how to use the token
```

**expiresIn:**
- Client can set refresh timer
- Client can show "session expiring" warnings
- In seconds (OAuth2 convention)

**user:**
- Client immediately has user data
- No need for separate /me endpoint call
- Better UX (instant display of user info)

### UserResponse

```java
public class UserResponse {
    private Long id;
    private String email;
    private String name;
    private Role role;
    private Boolean emailVerified;
    private String bio;
    private String avatarUrl;
    private LocalDateTime createdAt;

    public static UserResponse fromEntity(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .role(user.getRole())
                .emailVerified(user.getEmailVerified())
                .bio(user.getBio())
                .avatarUrl(user.getAvatarUrl())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
```

**What We Expose vs Hide:**

| User Entity Field | In UserResponse? | Reason |
|-------------------|------------------|--------|
| id | ✅ Yes | Client needs for updates |
| email | ✅ Yes | Display, identification |
| password | ❌ No | NEVER expose password hash |
| name | ✅ Yes | Display |
| role | ✅ Yes | UI permissions/features |
| emailVerified | ✅ Yes | Show verification status |
| bio | ✅ Yes | Profile display |
| avatarUrl | ✅ Yes | Profile display |
| isDeleted | ❌ No | Internal soft-delete flag |
| createdAt | ✅ Yes | "Member since" display |
| updatedAt | ❌ No | Internal audit field |

**Teaching Point:** The `fromEntity()` static factory method is a clean pattern for entity-to-DTO conversion. It keeps conversion logic in one place and makes the code readable.

---

## Decision 4: Lombok Annotations

### Annotations Used

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequest { ... }
```

**What Each Does:**

| Annotation | Generates | Why We Need It |
|------------|-----------|----------------|
| @Data | getters, setters, toString, equals, hashCode | Basic POJO functionality |
| @Builder | Builder pattern | Fluent object construction |
| @NoArgsConstructor | No-arg constructor | Jackson JSON deserialization |
| @AllArgsConstructor | All-args constructor | Required by @Builder |

**Why All Four?**

**Jackson Deserialization Requirement:**
```java
// Jackson needs no-arg constructor to create object
RegisterRequest request = objectMapper.readValue(json, RegisterRequest.class);
```

**@Builder for Clean Construction:**
```java
// In tests and service layer
RegisterRequest request = RegisterRequest.builder()
        .email("test@example.com")
        .password("password123")
        .name("Test User")
        .build();
```

**@Builder.Default for Default Values:**
```java
@Builder.Default
private String tokenType = "Bearer";
```
Without `@Builder.Default`, builder would set tokenType to null!

---

## Decision 5: Jakarta Validation Annotations

### Validation Framework

**We're using Jakarta Bean Validation 3.0:**
- Package: `jakarta.validation.constraints`
- Previously: `javax.validation.constraints` (pre-Jakarta EE)
- Spring Boot 3+ uses Jakarta

### Annotations Used

| Annotation | Purpose | Example |
|------------|---------|---------|
| @NotBlank | Not null, not empty, not whitespace | Strings that must have content |
| @Email | Valid email format | Email fields |
| @Size | Length constraints | Password (min), Name (min/max) |

### @NotBlank vs @NotNull vs @NotEmpty

```java
String value = null;
@NotNull    // FAILS - null not allowed
@NotEmpty   // FAILS - null not allowed
@NotBlank   // FAILS - null not allowed

String value = "";
@NotNull    // PASSES - not null
@NotEmpty   // FAILS - empty string
@NotBlank   // FAILS - empty string

String value = "   ";
@NotNull    // PASSES - not null
@NotEmpty   // PASSES - has characters (spaces)
@NotBlank   // FAILS - only whitespace

String value = "hello";
@NotNull    // PASSES
@NotEmpty   // PASSES
@NotBlank   // PASSES
```

**Teaching Point:** For user input strings, @NotBlank is almost always what you want. It catches all cases of "nothing useful provided."

### How Validation Works in Spring

```java
@PostMapping("/register")
public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
    // If validation fails, Spring throws MethodArgumentNotValidException
    // BEFORE this method is even called
}
```

**The `@Valid` annotation triggers validation:**
1. Request body is deserialized to RegisterRequest
2. Spring validates all fields with constraint annotations
3. If violations found, throws exception (we'll handle in global exception handler)
4. If valid, controller method executes

---

## Decision 6: Why Separate Request/Response Packages?

### Package Structure

```
dto/
├── request/
│   ├── RegisterRequest.java
│   ├── LoginRequest.java
│   └── RefreshTokenRequest.java
└── response/
    ├── AuthResponse.java
    └── UserResponse.java
```

**Why This Organization?**

**Alternative 1: Flat Structure**
```
dto/
├── RegisterRequest.java
├── LoginRequest.java
├── RefreshTokenRequest.java
├── AuthResponse.java
├── UserResponse.java
├── PostRequest.java       // Gets crowded
├── PostResponse.java
└── ... 50 more files
```
Problem: Hard to find things in large projects.

**Alternative 2: By Feature**
```
dto/
├── auth/
│   ├── RegisterRequest.java
│   ├── LoginRequest.java
│   └── AuthResponse.java
├── post/
│   ├── CreatePostRequest.java
│   └── PostResponse.java
```
This is also valid! Good for microservices.

**Our Choice: By Direction**
```
dto/
├── request/   ← All incoming data
└── response/  ← All outgoing data
```

Benefits:
- Clear data flow direction
- Easy to apply cross-cutting concerns (validation on requests, serialization on responses)
- Simple for small-to-medium projects

---

## What Works Now After This Commit?

### DTOs Are Ready For Use

**In Future AuthController:**
```java
@PostMapping("/register")
public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
    // Validation happens automatically!
    // If invalid, MethodArgumentNotValidException is thrown

    // Service will use request data
    AuthResponse response = authService.register(request);
    return ResponseEntity.ok(response);
}

@PostMapping("/login")
public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
    AuthResponse response = authService.login(request);
    return ResponseEntity.ok(response);
}

@PostMapping("/refresh")
public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
    AuthResponse response = authService.refreshToken(request);
    return ResponseEntity.ok(response);
}
```

**In Future AuthService:**
```java
public AuthResponse register(RegisterRequest request) {
    // Create user from request
    User user = User.builder()
            .email(request.getEmail())
            .password(passwordEncoder.encode(request.getPassword()))
            .name(request.getName())
            .role(Role.SUBSCRIBER)
            .build();

    user = userRepository.save(user);

    // Generate tokens
    String accessToken = jwtUtil.generateAccessToken(user);
    String refreshToken = jwtUtil.generateRefreshToken(user);

    // Build response
    return AuthResponse.builder()
            .accessToken(accessToken)
            .refreshToken(refreshToken)
            .expiresIn(3600L)  // 1 hour in seconds
            .user(UserResponse.fromEntity(user))
            .build();
}
```

### API Contract Example

**Register Request:**
```http
POST /api/auth/register
Content-Type: application/json

{
    "email": "user@example.com",
    "password": "securePassword123",
    "name": "John Doe"
}
```

**Successful Response:**
```http
HTTP/1.1 200 OK
Content-Type: application/json

{
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 3600,
    "user": {
        "id": 1,
        "email": "user@example.com",
        "name": "John Doe",
        "role": "SUBSCRIBER",
        "emailVerified": false,
        "bio": null,
        "avatarUrl": null,
        "createdAt": "2026-02-01T10:30:00"
    }
}
```

**Validation Error Response (handled by future GlobalExceptionHandler):**
```http
HTTP/1.1 400 Bad Request
Content-Type: application/json

{
    "status": 400,
    "error": "Validation Failed",
    "message": "Invalid input data",
    "details": [
        {
            "field": "email",
            "message": "Invalid email format"
        },
        {
            "field": "password",
            "message": "Password must be at least 8 characters"
        }
    ]
}
```

---

## What Still Doesn't Work

**We Still Cannot:**
- Process authentication requests (no AuthController)
- Execute authentication logic (no AuthService)
- Handle validation errors gracefully (no GlobalExceptionHandler)
- Protect endpoints with tokens (no JwtAuthenticationFilter)

**Next Steps:**
1. Create GlobalExceptionHandler (handle validation errors)
2. Create AuthService (authentication business logic)
3. Create AuthController (REST endpoints)
4. Create JwtAuthenticationFilter (protect endpoints)

---

## Teaching Talking Points

### For Students New to DTOs

**Analogy - DTOs as Airport Security:**
```
Passenger (Raw Request)
    ↓
TSA Checkpoint (DTO Validation)
    ↓
✓ Valid ticket? (required fields)
✓ Valid ID? (format validation)
✓ No prohibited items? (security constraints)
    ↓
Boarding Area (Controller/Service)
```

If validation fails, passenger doesn't even get to the gate (controller doesn't execute).

### For Students Asking "Why Not Just Check in Controller?"

**Without DTOs:**
```java
@PostMapping("/register")
public ResponseEntity<?> register(@RequestBody Map<String, Object> body) {
    // Manual validation nightmare
    String email = (String) body.get("email");
    if (email == null || email.isBlank()) {
        return ResponseEntity.badRequest().body("Email required");
    }
    if (!email.matches("...regex...")) {
        return ResponseEntity.badRequest().body("Invalid email");
    }
    // Repeat for every field...
    // 50 lines of validation code
}
```

**With DTOs:**
```java
@PostMapping("/register")
public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
    // Validation done! Just use the data
    authService.register(request);
}
```

### Common Validation Mistakes

**1. Using @NotNull for Strings:**
```java
// DON'T
@NotNull
private String email;  // Allows empty string ""

// DO
@NotBlank
private String email;  // Requires actual content
```

**2. Forgetting @Valid in Controller:**
```java
// DON'T - validation won't happen!
public ResponseEntity<?> register(@RequestBody RegisterRequest request)

// DO
public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request)
```

**3. Over-validating Passwords:**
```java
// DON'T - too restrictive
@Pattern(regexp = "^(?=.*[A-Z])(?=.*[a-z])(?=.*\\d)(?=.*[@$!%*?&]).*$")
private String password;

// DO - reasonable minimum, educate users
@Size(min = 8, message = "Password must be at least 8 characters")
private String password;
```

Modern security guidance (NIST) recommends length over complexity rules.

---

## Architectural Decisions Record

**ADR-018: Use DTOs for API Requests/Responses**
- **Status:** Accepted
- **Context:** Need to decouple API contract from domain entities
- **Decision:** Create separate DTO classes for requests and responses
- **Consequences:**
    - More classes to maintain
    - Clear API contract
    - Entity changes don't break API
    - Secure (password never exposed)

**ADR-019: Use Jakarta Bean Validation**
- **Status:** Accepted
- **Context:** Need declarative input validation
- **Decision:** Use @NotBlank, @Email, @Size annotations
- **Consequences:**
    - Consistent validation approach
    - Declarative (easy to read/maintain)
    - Integrates with Spring error handling

**ADR-020: Include User in AuthResponse**
- **Status:** Accepted
- **Context:** Client needs user info after login/register
- **Decision:** Embed UserResponse in AuthResponse
- **Consequences:**
    - One less API call for client
    - Slightly larger response payload
    - Better UX (immediate user data)

**ADR-021: Use Static Factory Method for Entity-to-DTO**
- **Status:** Accepted
- **Context:** Need consistent entity-to-DTO conversion
- **Decision:** Add `fromEntity()` method to response DTOs
- **Consequences:**
    - Conversion logic in one place
    - Easy to test
    - Could use MapStruct later for complex mappings

---

## Commit Stats
- **Files created:** 5
    - RegisterRequest.java (27 lines)
    - LoginRequest.java (21 lines)
    - RefreshTokenRequest.java (17 lines)
    - AuthResponse.java (23 lines)
    - UserResponse.java (37 lines)
- **Packages created:** 2
    - dto.request
    - dto.response
- **Validation annotations used:** 7
    - @NotBlank (5 usages)
    - @Email (2 usages)
    - @Size (2 usages)

---

## Testing the DTOs

### Unit Test Examples

```java
@SpringBootTest
class RegisterRequestValidationTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    void validRequest_shouldPassValidation() {
        RegisterRequest request = RegisterRequest.builder()
                .email("test@example.com")
                .password("password123")
                .name("John Doe")
                .build();

        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);
        assertThat(violations).isEmpty();
    }

    @Test
    void invalidEmail_shouldFailValidation() {
        RegisterRequest request = RegisterRequest.builder()
                .email("not-an-email")
                .password("password123")
                .name("John Doe")
                .build();

        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .isEqualTo("Invalid email format");
    }

    @Test
    void shortPassword_shouldFailValidation() {
        RegisterRequest request = RegisterRequest.builder()
                .email("test@example.com")
                .password("short")
                .name("John Doe")
                .build();

        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage())
                .isEqualTo("Password must be at least 8 characters");
    }

    @Test
    void blankName_shouldFailValidation() {
        RegisterRequest request = RegisterRequest.builder()
                .email("test@example.com")
                .password("password123")
                .name("   ")
                .build();

        Set<ConstraintViolation<RegisterRequest>> violations = validator.validate(request);
        assertThat(violations).hasSize(1);
    }
}
```

### UserResponse Mapping Test

```java
@Test
void fromEntity_shouldMapCorrectly() {
    User user = User.builder()
            .id(1L)
            .email("test@example.com")
            .password("hashedPassword")  // Should NOT appear in response
            .name("John Doe")
            .role(Role.AUTHOR)
            .emailVerified(true)
            .bio("A bio")
            .avatarUrl("https://example.com/avatar.jpg")
            .isDeleted(false)  // Should NOT appear in response
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())  // Should NOT appear in response
            .build();

    UserResponse response = UserResponse.fromEntity(user);

    assertThat(response.getId()).isEqualTo(1L);
    assertThat(response.getEmail()).isEqualTo("test@example.com");
    assertThat(response.getName()).isEqualTo("John Doe");
    assertThat(response.getRole()).isEqualTo(Role.AUTHOR);
    // password, isDeleted, updatedAt are not in response
}
```

---

## Resources for Students
- Jakarta Bean Validation: https://beanvalidation.org/2.0/spec/
- Spring Validation: https://docs.spring.io/spring-framework/reference/core/validation/beanvalidation.html
- DTO Pattern: https://martinfowler.com/eaaCatalog/dataTransferObject.html
- Lombok: https://projectlombok.org/features/
- NIST Password Guidelines: https://pages.nist.gov/800-63-3/sp800-63b.html
