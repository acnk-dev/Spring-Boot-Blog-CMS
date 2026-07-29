# Commit 15: Create Global Exception Handler

**Date:** February 1, 2026
**Commit Message:** Create Global Exception Handler

---

## Development Mindset & Decision Process

### The Error Handling Layer

**Current State:**
- Controllers throw RuntimeException on errors
- Spring returns default error responses (ugly, inconsistent)
- Validation errors return stack traces
- No custom exception types

**Goal:**
- Create consistent error response format
- Define custom exceptions for different error scenarios
- Handle validation errors gracefully
- Log errors appropriately
- Hide internal details from clients

**Teaching Point:** A global exception handler centralizes error handling. Instead of try-catch in every controller, exceptions bubble up and are handled in one place with consistent formatting.

---

## Decision 1: ErrorResponse Structure

### Standard Error Format

```java
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {
    private LocalDateTime timestamp;
    private int status;
    private String error;
    private String message;
    private String path;
    private List<FieldError> errors;  // For validation errors

    @Data
    @Builder
    public static class FieldError {
        private String field;
        private String message;
    }
}
```

### Field Explanations

| Field | Purpose | Example |
|-------|---------|---------|
| `timestamp` | When error occurred | `2026-02-01T10:30:00` |
| `status` | HTTP status code | `400`, `404`, `500` |
| `error` | Short error type | `"Validation Failed"`, `"Not Found"` |
| `message` | Human-readable description | `"Email already registered"` |
| `path` | Request URI that failed | `/api/v1/auth/register` |
| `errors` | Validation field errors (nullable) | `[{field: "email", message: "Invalid"}]` |

### Why @JsonInclude(NON_NULL)?

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
```

**Without it (validation error):**
```json
{
    "timestamp": "2026-02-01T10:30:00",
    "status": 400,
    "error": "Validation Failed",
    "message": "Invalid input data",
    "path": "/api/v1/auth/register",
    "errors": [{"field": "email", "message": "Invalid email format"}]
}
```

**Without it (not found error):**
```json
{
    "timestamp": "2026-02-01T10:30:00",
    "status": 404,
    "error": "Not Found",
    "message": "User not found with id: 123",
    "path": "/api/v1/users/123",
    "errors": null  // Ugly!
}
```

**With NON_NULL (not found error):**
```json
{
    "timestamp": "2026-02-01T10:30:00",
    "status": 404,
    "error": "Not Found",
    "message": "User not found with id: 123",
    "path": "/api/v1/users/123"
}
```

Null fields are simply omitted for cleaner responses.

---

## Decision 2: Custom Exception Hierarchy

### Exception Types

```
RuntimeException
├── ResourceNotFoundException (404)
├── UnauthorizedException (401)
└── BadRequestException (400)
```

### ResourceNotFoundException (404)

```java
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public ResourceNotFoundException(String resourceName, String fieldName, Object fieldValue) {
        super(String.format("%s not found with %s: '%s'", resourceName, fieldName, fieldValue));
    }
}
```

**Usage:**
```java
// Simple message
throw new ResourceNotFoundException("User not found");

// Formatted message
throw new ResourceNotFoundException("User", "id", 123);
// → "User not found with id: '123'"

throw new ResourceNotFoundException("Post", "slug", "my-post");
// → "Post not found with slug: 'my-post'"
```

### UnauthorizedException (401)

```java
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }

    public UnauthorizedException() {
        super("Authentication required");
    }
}
```

**Usage:**
```java
throw new UnauthorizedException();
// → "Authentication required"

throw new UnauthorizedException("Invalid or expired token");
```

### BadRequestException (400)

```java
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
```

**Usage:**
```java
throw new BadRequestException("Email already registered");
throw new BadRequestException("Invalid refresh token");
```

---

## Decision 3: @RestControllerAdvice

### What It Does

```java
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    // Exception handlers here apply to ALL controllers
}
```

**@RestControllerAdvice = @ControllerAdvice + @ResponseBody**
- Catches exceptions from any controller
- Returns JSON responses (not views)
- Single location for all error handling

### How It Works

```
Controller throws ResourceNotFoundException
           ↓
Spring looks for matching @ExceptionHandler
           ↓
GlobalExceptionHandler.handleResourceNotFoundException()
           ↓
Returns ResponseEntity<ErrorResponse>
           ↓
Jackson serializes to JSON
           ↓
HTTP 404 response with error body
```

---

## Decision 4: Exception Handler Methods

### Validation Errors (400)

```java
@ExceptionHandler(MethodArgumentNotValidException.class)
public ResponseEntity<ErrorResponse> handleValidationException(
        MethodArgumentNotValidException ex,
        HttpServletRequest request
) {
    List<ErrorResponse.FieldError> fieldErrors = ex.getBindingResult()
            .getFieldErrors()
            .stream()
            .map(error -> ErrorResponse.FieldError.builder()
                    .field(error.getField())
                    .message(error.getDefaultMessage())
                    .build())
            .toList();

    ErrorResponse errorResponse = ErrorResponse.builder()
            .timestamp(LocalDateTime.now())
            .status(HttpStatus.BAD_REQUEST.value())
            .error("Validation Failed")
            .message("Invalid input data")
            .path(request.getRequestURI())
            .errors(fieldErrors)
            .build();

    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
}
```

**Example Response:**
```json
{
    "timestamp": "2026-02-01T10:30:00",
    "status": 400,
    "error": "Validation Failed",
    "message": "Invalid input data",
    "path": "/api/v1/auth/register",
    "errors": [
        {"field": "email", "message": "Invalid email format"},
        {"field": "password", "message": "Password must be at least 8 characters"},
        {"field": "name", "message": "Name is required"}
    ]
}
```

### Resource Not Found (404)

```java
@ExceptionHandler(ResourceNotFoundException.class)
public ResponseEntity<ErrorResponse> handleResourceNotFoundException(
        ResourceNotFoundException ex,
        HttpServletRequest request
) {
    ErrorResponse errorResponse = ErrorResponse.builder()
            .timestamp(LocalDateTime.now())
            .status(HttpStatus.NOT_FOUND.value())
            .error("Not Found")
            .message(ex.getMessage())
            .path(request.getRequestURI())
            .build();

    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
}
```

**Example Response:**
```json
{
    "timestamp": "2026-02-01T10:30:00",
    "status": 404,
    "error": "Not Found",
    "message": "User not found with id: '123'",
    "path": "/api/v1/users/123"
}
```

### Security Exceptions

```java
@ExceptionHandler({AuthenticationException.class, BadCredentialsException.class})
public ResponseEntity<ErrorResponse> handleAuthenticationException(...) {
    // Always returns generic message to prevent enumeration
    .message("Invalid email or password")
}

@ExceptionHandler(AccessDeniedException.class)
public ResponseEntity<ErrorResponse> handleAccessDeniedException(...) {
    // 403 Forbidden
    .message("You don't have permission to access this resource")
}
```

### Generic Exception (500)

```java
@ExceptionHandler(Exception.class)
public ResponseEntity<ErrorResponse> handleGenericException(
        Exception ex,
        HttpServletRequest request
) {
    ErrorResponse errorResponse = ErrorResponse.builder()
            .timestamp(LocalDateTime.now())
            .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
            .error("Internal Server Error")
            .message("An unexpected error occurred")  // Hide internal details!
            .path(request.getRequestURI())
            .build();

    log.error("Unexpected error occurred: ", ex);  // Full stack trace in logs

    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
}
```

**Security:** Internal errors return generic message. Full details go to logs, not to client.

---

## Decision 5: HTTP Status Code Mapping

### Status Code Reference

| Exception | Status | When Used |
|-----------|--------|-----------|
| `MethodArgumentNotValidException` | 400 | @Valid fails |
| `BadRequestException` | 400 | Invalid request data |
| `UnauthorizedException` | 401 | Authentication required/failed |
| `AuthenticationException` | 401 | Spring Security auth failure |
| `AccessDeniedException` | 403 | Authenticated but not authorized |
| `ResourceNotFoundException` | 404 | Resource doesn't exist |
| `Exception` (catch-all) | 500 | Unexpected errors |

### 401 vs 403 Explained

```
401 Unauthorized:
- "Who are you?" (identity unknown)
- Missing or invalid credentials
- Solution: Log in

403 Forbidden:
- "I know who you are, but no." (identity known, access denied)
- Valid credentials, insufficient permissions
- Solution: Get higher privileges
```

---

## Decision 6: Logging Strategy

### Log Levels by Severity

```java
// Warning: Expected errors (user mistakes, not found)
log.warn("Validation failed for request {}: {}", uri, errors);
log.warn("Resource not found: {}", ex.getMessage());
log.warn("Unauthorized access attempt: {}", uri);
log.warn("Bad request: {}", ex.getMessage());

// Error: Unexpected errors (bugs, system failures)
log.error("Unexpected error occurred: ", ex);  // Full stack trace
```

**Why log warnings for 4xx?**
- Track patterns (many 404s = bad links somewhere)
- Security monitoring (401s = potential attacks)
- Debug user issues

**Why full stack trace for 500?**
- These are bugs
- Need full context to debug
- Don't expose to client, but log everything

---

## What Works Now After This Commit?

### Consistent Error Responses

**Before (Spring default):**
```json
{
    "timestamp": "2026-02-01T10:30:00.000+00:00",
    "status": 500,
    "error": "Internal Server Error",
    "trace": "java.lang.RuntimeException: Email already registered\n\tat com.studylink...",
    "path": "/api/v1/auth/register"
}
```

**After (our handler):**
```json
{
    "timestamp": "2026-02-01T10:30:00",
    "status": 400,
    "error": "Bad Request",
    "message": "Email already registered",
    "path": "/api/v1/auth/register"
}
```

### Update Services to Use Custom Exceptions

**AuthServiceImpl (update needed):**
```java
// Before
if (userService.existsByEmail(request.getEmail())) {
    throw new RuntimeException("Email already registered");
}

// After
if (userService.existsByEmail(request.getEmail())) {
    throw new BadRequestException("Email already registered");
}

// Before
User user = userService.findByEmail(request.getEmail())
        .orElseThrow(() -> new RuntimeException("Invalid email or password"));

// After
User user = userService.findByEmail(request.getEmail())
        .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));
```

**UserServiceImpl (update needed):**
```java
// Before
.orElseThrow(() -> new RuntimeException("User not found with id: " + id));

// After
.orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
```

---

## Error Response Examples

### Validation Error
```http
POST /api/v1/auth/register
{"email": "bad", "password": "123", "name": ""}

Response: 400 Bad Request
{
    "timestamp": "2026-02-01T10:30:00",
    "status": 400,
    "error": "Validation Failed",
    "message": "Invalid input data",
    "path": "/api/v1/auth/register",
    "errors": [
        {"field": "email", "message": "Invalid email format"},
        {"field": "password", "message": "Password must be at least 8 characters"},
        {"field": "name", "message": "Name is required"}
    ]
}
```

### Resource Not Found
```http
GET /api/v1/users/999

Response: 404 Not Found
{
    "timestamp": "2026-02-01T10:30:00",
    "status": 404,
    "error": "Not Found",
    "message": "User not found with id: '999'",
    "path": "/api/v1/users/999"
}
```

### Authentication Failed
```http
POST /api/v1/auth/login
{"email": "user@example.com", "password": "wrongpassword"}

Response: 401 Unauthorized
{
    "timestamp": "2026-02-01T10:30:00",
    "status": 401,
    "error": "Unauthorized",
    "message": "Invalid email or password",
    "path": "/api/v1/auth/login"
}
```

### Access Denied
```http
DELETE /api/v1/admin/users/123
Authorization: Bearer <subscriber_token>

Response: 403 Forbidden
{
    "timestamp": "2026-02-01T10:30:00",
    "status": 403,
    "error": "Forbidden",
    "message": "You don't have permission to access this resource",
    "path": "/api/v1/admin/users/123"
}
```

### Server Error
```http
POST /api/v1/posts
(Database connection failed)

Response: 500 Internal Server Error
{
    "timestamp": "2026-02-01T10:30:00",
    "status": 500,
    "error": "Internal Server Error",
    "message": "An unexpected error occurred",
    "path": "/api/v1/posts"
}
```

---

## Teaching Talking Points

### For Students New to Exception Handling

**Analogy - Customer Service:**
```
Without GlobalExceptionHandler:
- Each cashier handles complaints differently
- Some share internal drama with customers
- No consistent experience

With GlobalExceptionHandler:
- All complaints go to customer service desk
- Trained staff give professional responses
- Internal issues logged but not shared
- Consistent experience for customers
```

### Common Mistakes

**1. Exposing Internal Details:**
```java
// DON'T
.message(ex.getMessage())  // For all exceptions

// DO
.message("An unexpected error occurred")  // For 500s
log.error("Unexpected error: ", ex);  // Full details in logs
```

**2. Wrong Status Codes:**
```java
// DON'T
throw new BadRequestException("User not found");  // 400, should be 404

// DO
throw new ResourceNotFoundException("User not found");  // 404
```

**3. Missing @RestControllerAdvice:**
```java
// DON'T - Only catches from specific controller
@ControllerAdvice(assignableTypes = AuthController.class)

// DO - Catches from all controllers
@RestControllerAdvice
```

**4. Order Matters:**
```java
// DON'T - Generic handler catches everything
@ExceptionHandler(Exception.class)
public ResponseEntity<?> handleAll(...) { }

@ExceptionHandler(ResourceNotFoundException.class)  // Never reached!
public ResponseEntity<?> handleNotFound(...) { }

// DO - Specific handlers first (Spring handles this automatically)
// But be aware of exception hierarchy
```

---

## Architectural Decisions Record

**ADR-043: Centralized Exception Handling**
- **Status:** Accepted
- **Context:** Need consistent error responses
- **Decision:** Use @RestControllerAdvice for global handling
- **Consequences:**
    - Single source of truth for error formatting
    - Controllers stay clean (no try-catch)
    - Easy to modify error format globally

**ADR-044: Custom Exception Types**
- **Status:** Accepted
- **Context:** RuntimeException doesn't convey meaning
- **Decision:** Create ResourceNotFoundException, UnauthorizedException, BadRequestException
- **Consequences:**
    - Clear semantics in code
    - Automatic HTTP status mapping
    - Easy to add more exception types

**ADR-045: Hide Internal Errors from Clients**
- **Status:** Accepted
- **Context:** 500 errors might expose sensitive info
- **Decision:** Generic message to client, full details to logs
- **Consequences:**
    - Better security (no stack traces)
    - Requires good logging and monitoring
    - Debugging requires log access

**ADR-046: Include Field Errors for Validation**
- **Status:** Accepted
- **Context:** Validation errors need field-level detail
- **Decision:** Include `errors` array with field names and messages
- **Consequences:**
    - Better UX (highlight specific fields)
    - Larger response for validation errors
    - Frontend can map errors to fields

---

## Commit Stats
- **Files created:** 5
    - ErrorResponse.java (37 lines)
    - GlobalExceptionHandler.java (148 lines)
    - ResourceNotFoundException.java (16 lines)
    - UnauthorizedException.java (14 lines)
    - BadRequestException.java (12 lines)
- **Packages created:** 1
    - exception
- **Exception handlers:** 7
    - Validation, NotFound, Unauthorized, BadRequest
    - AuthenticationException, AccessDeniedException, Generic

---

## Testing the Exception Handler

### Unit Test

```java
@WebMvcTest(AuthController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldReturnValidationErrors() throws Exception {
        RegisterRequest request = RegisterRequest.builder()
                .email("invalid")
                .password("123")
                .name("")
                .build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[?(@.field=='email')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='password')]").exists());
    }

    @Test
    void shouldReturnNotFound() throws Exception {
        when(authService.login(any()))
                .thenThrow(new ResourceNotFoundException("User", "email", "test@example.com"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"password\":\"pass\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    @Test
    void shouldReturnBadRequest() throws Exception {
        when(authService.register(any()))
                .thenThrow(new BadRequestException("Email already registered"));

        RegisterRequest request = RegisterRequest.builder()
                .email("existing@example.com")
                .password("password123")
                .name("Test")
                .build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Email already registered"));
    }

    @Test
    void shouldReturnInternalServerError() throws Exception {
        when(authService.login(any()))
                .thenThrow(new RuntimeException("Database connection failed"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"password\":\"pass\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.trace").doesNotExist());  // No stack trace!
    }
}
```

---

## Resources for Students
- @ControllerAdvice: https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-advice.html
- Exception Handling: https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html
- HTTP Status Codes: https://developer.mozilla.org/en-US/docs/Web/HTTP/Status
- Problem Details (RFC 7807): https://datatracker.ietf.org/doc/html/rfc7807
