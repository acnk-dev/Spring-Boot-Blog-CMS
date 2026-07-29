# Commit 11: Create AuthController

**Date:** February 1, 2026
**Commit Message:** Create AuthController

---

## Development Mindset & Decision Process

### The REST API Layer

**Current State:**
- AuthService ready (register, login, refresh logic)
- DTOs ready (request/response structures)
- JWT and security infrastructure in place
- But no HTTP endpoints to expose the functionality!

**Goal:**
- Create AuthController with REST endpoints
- Expose register, login, and refresh operations
- Apply proper HTTP status codes
- Enable request validation

**Teaching Point:** The Controller is the entry point for HTTP requests. It receives requests, delegates to services, and returns responses. Controllers should be thin - no business logic, just request handling.

---

## Decision 1: API Versioning

### URL Path Versioning

```java
@RequestMapping("/api/v1/auth")
```

**Full endpoint paths:**
```
POST /api/v1/auth/register
POST /api/v1/auth/login
POST /api/v1/auth/refresh
```

### Why Version the API?

**Without versioning:**
```
POST /api/auth/register  (v1)

// Later, breaking change needed...
// All clients break immediately!
```

**With versioning:**
```
POST /api/v1/auth/register  (original)
POST /api/v2/auth/register  (new version with changes)

// Clients can migrate at their own pace
```

### Versioning Strategies

| Strategy | Example | Pros | Cons |
|----------|---------|------|------|
| **URL Path** (our choice) | `/api/v1/auth` | Clear, cacheable, easy routing | URL changes |
| Header | `Accept: application/vnd.api.v1+json` | Clean URLs | Hidden, harder to test |
| Query Param | `/api/auth?version=1` | Simple | Clutters URL, caching issues |

**Teaching Point:** URL path versioning is the most common and pragmatic choice. It's explicit, easy to understand, and works well with API documentation tools.

---

## Decision 2: HTTP Methods and Status Codes

### Endpoint Design

| Endpoint | Method | Success Status | Why |
|----------|--------|----------------|-----|
| `/register` | POST | 201 Created | Creates new resource (user) |
| `/login` | POST | 200 OK | Not creating, just authenticating |
| `/refresh` | POST | 200 OK | Not creating, just refreshing |

### Why POST for All?

**POST is appropriate because:**
- All operations have request bodies
- None are idempotent (repeating creates different results)
- Not fetching resources (so not GET)

**Common mistake:**
```java
// DON'T - GET with sensitive data in URL
@GetMapping("/login")
public ResponseEntity<?> login(@RequestParam String email, @RequestParam String password)
// Password visible in: URL, browser history, server logs, proxy logs!

// DO - POST with body
@PostMapping("/login")
public ResponseEntity<?> login(@RequestBody LoginRequest request)
// Credentials in encrypted request body
```

### Status Code Selection

**201 Created for Register:**
```java
@PostMapping("/register")
public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
    AuthResponse response = authService.register(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
}
```
- A new user resource was created
- Could also include `Location` header pointing to new user

**200 OK for Login/Refresh:**
```java
@PostMapping("/login")
public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
    AuthResponse response = authService.login(request);
    return ResponseEntity.ok(response);
}
```
- No new resource created
- Just performing an operation

---

## Decision 3: Request Validation

### The @Valid Annotation

```java
@PostMapping("/register")
public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
    // If we get here, request is already validated!
}
```

**What happens with @Valid:**

```
1. Request arrives: {"email": "bad", "password": "123", "name": ""}

2. Spring deserializes to RegisterRequest

3. @Valid triggers Jakarta Bean Validation:
   - @Email on email → FAIL ("bad" is not valid email)
   - @Size(min=8) on password → FAIL ("123" too short)
   - @NotBlank on name → FAIL ("" is blank)

4. MethodArgumentNotValidException thrown

5. Controller method NEVER executes

6. Exception handler returns 400 Bad Request (future commit)
```

**Without @Valid:**
```java
// DON'T - no validation!
public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
    // Invalid data reaches service layer
    // Validation annotations are ignored!
}
```

**Teaching Point:** Always use `@Valid` with `@RequestBody`. Without it, your DTO validation annotations do nothing.

---

## Decision 4: Controller Structure

### Thin Controller Pattern

```java
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
```

**Controller responsibilities:**
1. Receive HTTP request
2. Validate input (@Valid)
3. Delegate to service
4. Return HTTP response

**Controller does NOT:**
- Contain business logic
- Access repositories directly
- Handle transactions
- Perform complex data transformations

### Why Thin Controllers?

**Fat Controller (anti-pattern):**
```java
// DON'T
@PostMapping("/register")
public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
    // Validation in controller
    if (request.getEmail() == null) {
        return ResponseEntity.badRequest().body("Email required");
    }

    // Business logic in controller
    if (userRepository.existsByEmail(request.getEmail())) {
        return ResponseEntity.badRequest().body("Email exists");
    }

    // More logic...
    User user = new User();
    user.setEmail(request.getEmail());
    user.setPassword(passwordEncoder.encode(request.getPassword()));
    userRepository.save(user);

    // Token generation in controller
    String token = jwtUtil.generateAccessToken(user);

    return ResponseEntity.ok(new AuthResponse(token));
}
```

**Problems:**
- Hard to test (need HTTP context)
- Business logic duplicated if another controller needs it
- Mixed responsibilities
- Long, hard-to-read methods

**Thin Controller (our approach):**
```java
// DO
@PostMapping("/register")
public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
    AuthResponse response = authService.register(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
}
```

**Benefits:**
- Easy to test (mock service)
- Business logic reusable
- Single responsibility
- Clean, readable

---

## Decision 5: Annotations Explained

### @RestController

```java
@RestController  // = @Controller + @ResponseBody
public class AuthController {
```

**Equivalent to:**
```java
@Controller
public class AuthController {
    @PostMapping("/login")
    @ResponseBody  // Would need this on every method
    public ResponseEntity<AuthResponse> login(...) { }
}
```

**What it does:**
- Marks class as Spring MVC controller
- All methods return data (not view names)
- Response automatically serialized to JSON

### @RequestMapping

```java
@RequestMapping("/api/v1/auth")
```

**Sets base path for all endpoints in controller:**
```
@PostMapping("/register")  → POST /api/v1/auth/register
@PostMapping("/login")     → POST /api/v1/auth/login
@PostMapping("/refresh")   → POST /api/v1/auth/refresh
```

### @RequiredArgsConstructor

```java
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;  // Injected via constructor
}
```

**Lombok generates:**
```java
public AuthController(AuthService authService) {
    this.authService = authService;
}
```

### @PostMapping

```java
@PostMapping("/register")
```

**Shorthand for:**
```java
@RequestMapping(value = "/register", method = RequestMethod.POST)
```

### @Valid and @RequestBody

```java
public ResponseEntity<AuthResponse> register(
    @Valid @RequestBody RegisterRequest request
) {
```

- `@RequestBody` - Deserialize JSON body to object
- `@Valid` - Trigger bean validation on the object

---

## What Works Now After This Commit?

### API Endpoints Available

**Register:**
```http
POST /api/v1/auth/register
Content-Type: application/json

{
    "email": "user@example.com",
    "password": "securePassword123",
    "name": "John Doe"
}

Response: 201 Created
{
    "accessToken": "eyJhbG...",
    "refreshToken": "eyJhbG...",
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

**Login:**
```http
POST /api/v1/auth/login
Content-Type: application/json

{
    "email": "user@example.com",
    "password": "securePassword123"
}

Response: 200 OK
{
    "accessToken": "eyJhbG...",
    "refreshToken": "eyJhbG...",
    "tokenType": "Bearer",
    "expiresIn": 3600,
    "user": { ... }
}
```

**Refresh Token:**
```http
POST /api/v1/auth/refresh
Content-Type: application/json

{
    "refreshToken": "eyJhbG..."
}

Response: 200 OK
{
    "accessToken": "eyJhbG...",  // New token
    "refreshToken": "eyJhbG...", // New token
    "tokenType": "Bearer",
    "expiresIn": 3600,
    "user": { ... }
}
```

### Testing with cURL

```bash
# Register
curl -X POST http://localhost:8081/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"password123","name":"Test User"}'

# Login
curl -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"password123"}'

# Refresh
curl -X POST http://localhost:8081/api/v1/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"eyJhbG..."}'
```

### What Still Doesn't Work

**Current issues:**
1. **Validation errors return 500** - No GlobalExceptionHandler yet
2. **Auth errors return 500** - RuntimeExceptions not handled
3. **Endpoints not protected** - SecurityFilterChain still permitAll
4. **No JWT authentication** - JwtAuthenticationFilter not created

**Error response currently:**
```http
POST /api/v1/auth/register
{"email": "invalid", "password": "123", "name": ""}

Response: 500 Internal Server Error (or Spring's default 400)
{
    "timestamp": "...",
    "status": 500,
    "error": "Internal Server Error",
    "path": "/api/v1/auth/register"
}
```

**After GlobalExceptionHandler (next commits):**
```http
Response: 400 Bad Request
{
    "status": 400,
    "error": "Validation Failed",
    "message": "Invalid input data",
    "details": [
        {"field": "email", "message": "Invalid email format"},
        {"field": "password", "message": "Password must be at least 8 characters"},
        {"field": "name", "message": "Name is required"}
    ]
}
```

---

## Request/Response Flow

```
┌──────────────────────────────────────────────────────────────────────────┐
│                         REQUEST FLOW                                      │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  HTTP Request                                                            │
│  POST /api/v1/auth/register                                              │
│  {"email":"...", "password":"...", "name":"..."}                         │
│                              ↓                                           │
│  ┌─────────────────────────────────────────┐                            │
│  │         DispatcherServlet               │                            │
│  │  (Spring's front controller)            │                            │
│  └─────────────────────────────────────────┘                            │
│                              ↓                                           │
│  ┌─────────────────────────────────────────┐                            │
│  │         @RequestBody                    │                            │
│  │  Jackson deserializes JSON → DTO        │                            │
│  └─────────────────────────────────────────┘                            │
│                              ↓                                           │
│  ┌─────────────────────────────────────────┐                            │
│  │         @Valid                          │                            │
│  │  Bean Validation runs                   │                            │
│  │  (throws if invalid)                    │                            │
│  └─────────────────────────────────────────┘                            │
│                              ↓                                           │
│  ┌─────────────────────────────────────────┐                            │
│  │         AuthController                  │                            │
│  │  register(@Valid @RequestBody request)  │                            │
│  └─────────────────────────────────────────┘                            │
│                              ↓                                           │
│  ┌─────────────────────────────────────────┐                            │
│  │         AuthService                     │                            │
│  │  register(request) → AuthResponse       │                            │
│  └─────────────────────────────────────────┘                            │
│                              ↓                                           │
│  ┌─────────────────────────────────────────┐                            │
│  │         ResponseEntity                  │                            │
│  │  status(201).body(response)             │                            │
│  └─────────────────────────────────────────┘                            │
│                              ↓                                           │
│  ┌─────────────────────────────────────────┐                            │
│  │         Jackson                         │                            │
│  │  Serializes DTO → JSON                  │                            │
│  └─────────────────────────────────────────┘                            │
│                              ↓                                           │
│  HTTP Response                                                           │
│  201 Created                                                             │
│  {"accessToken":"...", "refreshToken":"...", ...}                        │
│                                                                          │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## Teaching Talking Points

### For Students New to REST Controllers

**Analogy - Restaurant Host:**
```
Customer (HTTP Request)
    ↓
Host (Controller)
    - Greets customer (receives request)
    - Checks reservation (@Valid)
    - Seats at table (delegates to service)
    - Gives menu (returns response)
    - Does NOT cook food!
    ↓
Kitchen (Service)
    - Actual work happens here
```

### Common Controller Mistakes

**1. Business Logic in Controller:**
```java
// DON'T
@PostMapping("/register")
public ResponseEntity<?> register(@RequestBody RegisterRequest req) {
    if (userRepository.existsByEmail(req.getEmail())) {
        return ResponseEntity.badRequest().body("Email exists");
    }
    // More logic...
}

// DO
@PostMapping("/register")
public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
    return ResponseEntity.status(HttpStatus.CREATED)
            .body(authService.register(req));
}
```

**2. Forgetting @Valid:**
```java
// DON'T - validation annotations ignored!
public ResponseEntity<?> register(@RequestBody RegisterRequest request)

// DO
public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request)
```

**3. Wrong HTTP Status:**
```java
// DON'T - 200 for creation
@PostMapping("/register")
public ResponseEntity<?> register(...) {
    return ResponseEntity.ok(response);  // 200, should be 201
}

// DO
@PostMapping("/register")
public ResponseEntity<?> register(...) {
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
}
```

**4. Returning Raw Exceptions:**
```java
// DON'T - exposes internal details
@PostMapping("/login")
public ResponseEntity<?> login(@RequestBody LoginRequest req) {
    try {
        return ResponseEntity.ok(authService.login(req));
    } catch (Exception e) {
        return ResponseEntity.badRequest().body(e.getMessage());
        // Could expose: "User not found: john@example.com"
    }
}

// DO - let GlobalExceptionHandler handle it (future commit)
@PostMapping("/login")
public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
    return ResponseEntity.ok(authService.login(req));
    // Exceptions handled centrally with proper error responses
}
```

---

## Architectural Decisions Record

**ADR-030: URL Path API Versioning**
- **Status:** Accepted
- **Context:** Need strategy for API versioning
- **Decision:** Use `/api/v1/` prefix in URL path
- **Consequences:**
    - Clear, explicit versioning
    - Easy to route and cache
    - URL changes between versions

**ADR-031: Thin Controller Pattern**
- **Status:** Accepted
- **Context:** Need clear separation of concerns
- **Decision:** Controllers only handle HTTP, delegate to services
- **Consequences:**
    - Easy to test
    - Business logic reusable
    - More classes (service layer required)

**ADR-032: 201 Created for Register, 200 OK for Login/Refresh**
- **Status:** Accepted
- **Context:** Need appropriate HTTP status codes
- **Decision:** Use 201 when creating resources, 200 otherwise
- **Consequences:**
    - Follows HTTP/REST conventions
    - Clear intent in response codes

---

## Commit Stats
- **Files created:** 1
    - AuthController.java (58 lines)
- **Packages created:** 1
    - controller
- **Endpoints created:** 3
    - POST /api/v1/auth/register
    - POST /api/v1/auth/login
    - POST /api/v1/auth/refresh
- **Annotations used:**
    - @RestController, @RequestMapping
    - @PostMapping, @Valid, @RequestBody
    - @RequiredArgsConstructor

---

## Testing the Controller

### Unit Test Example

```java
@WebMvcTest(AuthController.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void register_shouldReturn201_whenValid() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .email("test@example.com")
                .password("password123")
                .name("Test User")
                .build();

        AuthResponse response = AuthResponse.builder()
                .accessToken("accessToken")
                .refreshToken("refreshToken")
                .tokenType("Bearer")
                .expiresIn(3600L)
                .user(UserResponse.builder()
                        .id(1L)
                        .email("test@example.com")
                        .name("Test User")
                        .role(Role.SUBSCRIBER)
                        .build())
                .build();

        when(authService.register(any(RegisterRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").value("accessToken"))
                .andExpect(jsonPath("$.refreshToken").value("refreshToken"))
                .andExpect(jsonPath("$.user.email").value("test@example.com"));
    }

    @Test
    void register_shouldReturn400_whenInvalidEmail() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .email("invalid-email")
                .password("password123")
                .name("Test User")
                .build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_shouldReturn200_whenValid() throws Exception {
        LoginRequest request = LoginRequest.builder()
                .email("test@example.com")
                .password("password123")
                .build();

        AuthResponse response = AuthResponse.builder()
                .accessToken("accessToken")
                .refreshToken("refreshToken")
                .build();

        when(authService.login(any(LoginRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("accessToken"));
    }

    @Test
    void refresh_shouldReturn200_whenValid() throws Exception {
        RefreshTokenRequest request = RefreshTokenRequest.builder()
                .refreshToken("validRefreshToken")
                .build();

        AuthResponse response = AuthResponse.builder()
                .accessToken("newAccessToken")
                .refreshToken("newRefreshToken")
                .build();

        when(authService.refreshToken(any(RefreshTokenRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("newAccessToken"));
    }
}
```

### Integration Test Example

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthControllerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void fullAuthFlow() {
        // Register
        RegisterRequest registerRequest = RegisterRequest.builder()
                .email("integration@example.com")
                .password("password123")
                .name("Integration Test")
                .build();

        ResponseEntity<AuthResponse> registerResponse = restTemplate.postForEntity(
                "/api/v1/auth/register",
                registerRequest,
                AuthResponse.class
        );

        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registerResponse.getBody().getAccessToken()).isNotBlank();

        // Login
        LoginRequest loginRequest = LoginRequest.builder()
                .email("integration@example.com")
                .password("password123")
                .build();

        ResponseEntity<AuthResponse> loginResponse = restTemplate.postForEntity(
                "/api/v1/auth/login",
                loginRequest,
                AuthResponse.class
        );

        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody().getAccessToken()).isNotBlank();

        // Refresh
        RefreshTokenRequest refreshRequest = RefreshTokenRequest.builder()
                .refreshToken(loginResponse.getBody().getRefreshToken())
                .build();

        ResponseEntity<AuthResponse> refreshResponse = restTemplate.postForEntity(
                "/api/v1/auth/refresh",
                refreshRequest,
                AuthResponse.class
        );

        assertThat(refreshResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshResponse.getBody().getAccessToken()).isNotBlank();
    }
}
```

---

## Resources for Students
- Spring Web MVC: https://docs.spring.io/spring-framework/reference/web/webmvc.html
- REST API Design: https://restfulapi.net/
- HTTP Status Codes: https://httpstatuses.com/
- API Versioning: https://www.freecodecamp.org/news/how-to-version-a-rest-api/
- MockMvc Testing: https://docs.spring.io/spring-framework/reference/testing/spring-mvc-test-framework.html
