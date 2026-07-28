# Commit 7: Create JWT Utility Class

**Date:** January 29, 2026
**Commit Message:** Create JWT Utility Class

---

## Development Mindset & Decision Process

### The Authentication Token Layer

**Current State:**
- Security configuration in place (BCrypt, CORS, stateless sessions)
- User entity and repository ready
- JWT dependencies available (jjwt-api, jjwt-impl, jjwt-jackson)
- But no way to generate or validate tokens!

**Goal:**
- Create utility class for JWT operations
- Generate access tokens (short-lived, for API access)
- Generate refresh tokens (long-lived, for getting new access tokens)
- Validate and parse tokens
- Extract user information from tokens

**Teaching Point:** We're separating JWT logic into a dedicated utility class. This follows Single Responsibility Principle - one class, one job (token operations). Later, we'll use this utility in filters and services.

---

## Decision 1: Understanding JWT Structure

### What is a JWT?

**JWT = JSON Web Token**

A JWT is a compact, URL-safe way to represent claims between two parties.

```
eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c
└─────────────┬─────────────┘.└────────────────────────────┬────────────────────────────┘.└──────────────┬──────────────┘
           Header                                       Payload                                     Signature
```

**Three Parts (separated by dots):**

#### 1. Header
```json
{
  "alg": "HS256",
  "typ": "JWT"
}
```
- `alg` - Signing algorithm (HS256 = HMAC-SHA256)
- `typ` - Token type

#### 2. Payload (Claims)
```json
{
  "sub": "123",
  "email": "user@example.com",
  "role": "AUTHOR",
  "name": "John Doe",
  "type": "access",
  "iat": 1706500000,
  "exp": 1706503600
}
```
- `sub` - Subject (user ID)
- `iat` - Issued At (timestamp)
- `exp` - Expiration (timestamp)
- Custom claims: email, role, name, type

#### 3. Signature
```
HMACSHA256(
  base64UrlEncode(header) + "." + base64UrlEncode(payload),
  secret
)
```
- Created by hashing header + payload with secret key
- Proves token wasn't tampered with
- Only server with secret can create valid signature

**Teaching Point:** JWTs are not encrypted - they're just Base64 encoded. Anyone can decode and read the payload. The signature only proves authenticity, not confidentiality. Never put sensitive data (passwords, SSN) in JWTs.

### JWT vs Session-Based Auth

**Session-Based (Traditional):**
```
1. User logs in
2. Server creates session, stores in memory/database
3. Server sends session ID in cookie
4. Browser sends cookie with every request
5. Server looks up session to identify user

Problems:
- Server must store all sessions (memory/database)
- Hard to scale (session affinity or shared storage)
- CSRF vulnerabilities with cookies
```

**JWT-Based (Our Approach):**
```
1. User logs in
2. Server creates JWT with user info, signs it
3. Server sends JWT to client
4. Client stores JWT (localStorage/memory)
5. Client sends JWT in Authorization header
6. Server validates signature, trusts payload

Benefits:
- Stateless (no server-side storage)
- Easy to scale (any server can validate)
- No CSRF (not in cookies)
- Works with mobile apps
```

---

## Decision 2: Access Token vs Refresh Token

### Why Two Tokens?

**The Security Dilemma:**
- Short-lived tokens = More secure (less time if stolen)
- Short-lived tokens = Bad UX (frequent re-login)
- Long-lived tokens = Better UX
- Long-lived tokens = Security risk (long exposure if stolen)

**Solution: Two Token Strategy**

| Token | Lifetime | Purpose | Contains |
|-------|----------|---------|----------|
| Access Token | 1 hour | API authentication | User info (email, role, name) |
| Refresh Token | 30 days | Get new access token | Minimal (just type) |

### How It Works

```
Initial Login:
┌─────────┐                    ┌─────────┐
│ Client  │ ─── credentials ──→│ Server  │
│         │ ←── access + refresh tokens ──│         │
└─────────┘                    └─────────┘

API Requests (for 1 hour):
┌─────────┐                    ┌─────────┐
│ Client  │ ─── access token ──→│ Server  │
│         │ ←── data ──────────│         │
└─────────┘                    └─────────┘

After 1 Hour (access token expired):
┌─────────┐                    ┌─────────┐
│ Client  │ ─── refresh token ─→│ Server  │
│         │ ←── new access token│         │
└─────────┘                    └─────────┘

After 30 Days (refresh token expired):
User must log in again with credentials
```

### Our Token Configuration

```properties
jwt.access-token-expiration=3600000      # 1 hour (3600 * 1000 ms)
jwt.refresh-token-expiration=2592000000  # 30 days (30 * 24 * 60 * 60 * 1000 ms)
```

**Why These Values?**

**Access Token (1 hour):**
- Short enough: If stolen, limited damage window
- Long enough: User won't need to refresh constantly
- Industry standard: 15 min - 2 hours typical

**Refresh Token (30 days):**
- "Remember me" functionality
- User stays logged in for a month
- Can be revoked server-side if needed (future enhancement)

**Teaching Point:** These are starting values. Adjust based on security requirements. Banking apps might use 5-minute access tokens. Social media might use 7-day access tokens.

---

## Decision 3: Token Claims Design

### Access Token Claims

```java
public String generateAccessToken(User user) {
    Map<String, Object> claims = new HashMap<>();
    claims.put("email", user.getEmail());
    claims.put("role", user.getRole().name());
    claims.put("name", user.getName());
    claims.put("type", "access");

    return buildToken(claims, user.getId().toString(), accessTokenExpiration);
}
```

**What We Include:**

| Claim | Value | Why |
|-------|-------|-----|
| `sub` (subject) | User ID | Primary identifier, used for lookups |
| `email` | user@example.com | Display, logging, without DB lookup |
| `role` | AUTHOR | Authorization decisions without DB lookup |
| `name` | John Doe | Display in UI without DB lookup |
| `type` | access | Distinguish from refresh token |
| `iat` | timestamp | When token was issued |
| `exp` | timestamp | When token expires |

**Why Include User Info?**

**Without claims (DB lookup every request):**
```java
// Every API request:
Long userId = jwtUtil.getUserIdFromToken(token);
User user = userRepository.findById(userId).orElseThrow();  // DB query!
String role = user.getRole().name();
```

**With claims (no DB lookup):**
```java
// Every API request:
String role = jwtUtil.extractClaim(token, c -> c.get("role", String.class));
// No DB query! Role is in the token
```

**Trade-off:**
- **Pro:** Faster (no DB hit for basic info)
- **Con:** Stale data (if user role changes, old tokens have old role)
- **Solution:** Short access token lifetime + token refresh

### Refresh Token Claims

```java
public String generateRefreshToken(User user) {
    Map<String, Object> claims = new HashMap<>();
    claims.put("type", "refresh");

    return buildToken(claims, user.getId().toString(), refreshTokenExpiration);
}
```

**Minimal Claims:**
- Only `type` and `sub` (user ID)
- No email, role, name
- **Why?** Refresh tokens are only used to get new access tokens, not for authorization

**Teaching Point:** Keep refresh tokens minimal. They're long-lived, so less data = less exposure. When refreshing, we'll load fresh user data from DB anyway.

---

## Decision 4: Secret Key Management

### The Secret Key

```properties
jwt.secret=${JWT_SECRET:your-secret-key-min-256-bits-for-hmac-sha}
```

**What This Means:**
- `${JWT_SECRET:...}` - Use environment variable `JWT_SECRET`
- `:your-secret-key...` - Default value if env var not set
- Default is for development only!

### Key Requirements for HS256

**HMAC-SHA256 requires:**
- Minimum 256 bits (32 bytes) key
- Recommended: 512 bits (64 bytes) for extra security
- Must be kept secret (never commit to git!)

**Our Key Handling:**

```java
private SecretKey getSigningKey() {
    return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
}
```

- `Keys.hmacShaKeyFor()` - JJWT helper to create proper key
- `StandardCharsets.UTF_8` - Consistent byte encoding
- Returns `SecretKey` object for signing/verification

### Production Secret Generation

**Generate a secure secret:**
```bash
# Using OpenSSL (recommended)
openssl rand -base64 64

# Output example:
# K7gNU3sdo+OL0wNhqoVWhr3g6s1xYv72ol/pe/Unols=...

# Set as environment variable
export JWT_SECRET="K7gNU3sdo+OL0wNhqoVWhr3g6s1xYv72ol/pe/Unols=..."
```

**Never Do This:**
```properties
# DON'T commit real secrets!
jwt.secret=my-super-secret-production-key
```

**Do This:**
```properties
# Use environment variable
jwt.secret=${JWT_SECRET}

# Or with fallback for dev only
jwt.secret=${JWT_SECRET:dev-only-not-for-production}
```

**Teaching Point:** The secret key is the crown jewel of JWT security. If leaked, attackers can forge any token. Use environment variables, secrets managers (AWS Secrets Manager, HashiCorp Vault), or Kubernetes secrets in production.

---

## Decision 5: JJWT Library (0.12.3)

### Why JJWT?

**Options Considered:**

| Library | Pros | Cons |
|---------|------|------|
| **JJWT** (our choice) | Most popular, well-maintained, fluent API | 3 dependencies |
| java-jwt (Auth0) | Single dependency | Less fluent API |
| Nimbus JOSE+JWT | Full JOSE support | More complex |
| Spring Security OAuth2 | Integrated with Spring | Heavier, for OAuth flows |

**Why JJWT 0.12.3:**
- Industry standard for Java JWT
- Fluent builder API (readable code)
- Automatic algorithm detection
- Good security defaults
- Active maintenance

### JJWT Modular Design

```xml
<!-- API (compile time) -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.3</version>
</dependency>

<!-- Implementation (runtime) -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.3</version>
    <scope>runtime</scope>
</dependency>

<!-- JSON serialization (runtime) -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.3</version>
    <scope>runtime</scope>
</dependency>
```

**Why Three Dependencies?**
- **jjwt-api:** Interfaces you code against
- **jjwt-impl:** Actual implementation (swappable)
- **jjwt-jackson:** JSON processing with Jackson (could use Gson instead)

**Teaching Point:** This is a common pattern - separate API from implementation. Your code depends only on interfaces, making it easier to swap implementations if needed.

---

## Decision 6: Method Implementations Explained

### Token Generation

```java
private String buildToken(Map<String, Object> claims, String subject, long expiration) {
    return Jwts.builder()
            .claims(claims)
            .subject(subject)
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + expiration))
            .signWith(getSigningKey())
            .compact();
}
```

**Line by Line:**

1. `Jwts.builder()` - Start building a JWT
2. `.claims(claims)` - Add custom claims (email, role, etc.)
3. `.subject(subject)` - Set the `sub` claim (user ID)
4. `.issuedAt(new Date())` - Set `iat` to current time
5. `.expiration(new Date(...))` - Set `exp` to current time + expiration
6. `.signWith(getSigningKey())` - Sign with our secret (HS256 auto-detected)
7. `.compact()` - Build the final JWT string

### Token Validation

```java
public boolean validateToken(String token) {
    try {
        extractAllClaims(token);
        return !isTokenExpired(token);
    } catch (Exception e) {
        return false;
    }
}
```

**Validation Steps:**
1. Parse and verify signature (`extractAllClaims`)
2. Check expiration (`isTokenExpired`)
3. If any exception (invalid signature, malformed, expired), return false

**Exceptions That Can Occur:**
- `SignatureException` - Invalid signature
- `MalformedJwtException` - Invalid JWT format
- `ExpiredJwtException` - Token expired
- `UnsupportedJwtException` - Unsupported JWT
- `IllegalArgumentException` - Empty/null token

### Claims Extraction

```java
public Claims extractAllClaims(String token) {
    return Jwts.parser()
            .verifyWith(getSigningKey())
            .build()
            .parseSignedClaims(token)
            .getPayload();
}
```

**What Happens:**
1. `Jwts.parser()` - Create a parser
2. `.verifyWith(getSigningKey())` - Configure signature verification
3. `.build()` - Build the parser
4. `.parseSignedClaims(token)` - Parse and verify (throws if invalid)
5. `.getPayload()` - Get the claims (payload)

### Flexible Claim Extraction

```java
private <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
    Claims claims = extractAllClaims(token);
    return claimsResolver.apply(claims);
}
```

**Why Function Parameter?**

This allows flexible claim extraction:

```java
// Get subject
String subject = extractClaim(token, Claims::getSubject);

// Get expiration
Date exp = extractClaim(token, Claims::getExpiration);

// Get custom claim
String email = extractClaim(token, claims -> claims.get("email", String.class));
```

**Teaching Point:** Using `Function<Claims, T>` makes the method generic. Instead of writing separate methods for each claim, one method handles all cases.

---

## Decision 7: Token Type Claim

### Why Include Token Type?

```java
claims.put("type", "access");  // or "refresh"
```

**Prevent Token Confusion Attacks:**

Without type checking:
```java
// Attacker could use refresh token as access token!
// Both are valid JWTs signed with same key
```

With type checking (future implementation):
```java
public boolean isAccessToken(String token) {
    String type = extractClaim(token, c -> c.get("type", String.class));
    return "access".equals(type);
}

// In authentication filter:
if (!jwtUtil.isAccessToken(token)) {
    throw new InvalidTokenException("Refresh token cannot be used for API access");
}
```

**Teaching Point:** Always include a token type. It's a simple security measure that prevents tokens from being misused in unintended contexts.

---

## What Works Now After This Commit?

### Application Capabilities

**We Can Now:**

1. **Generate Access Tokens:**
```java
@Autowired
private JwtUtil jwtUtil;

User user = userRepository.findById(1L).orElseThrow();
String accessToken = jwtUtil.generateAccessToken(user);
// Returns: eyJhbGciOiJIUzI1NiJ9.eyJlbWFpbCI6InVzZXJAZXhhbXBsZS5jb20i...
```

2. **Generate Refresh Tokens:**
```java
String refreshToken = jwtUtil.generateRefreshToken(user);
// Returns: eyJhbGciOiJIUzI1NiJ9.eyJ0eXBlIjoicmVmcmVzaCIs...
```

3. **Validate Tokens:**
```java
boolean isValid = jwtUtil.validateToken(accessToken);
// Returns: true (if valid and not expired)
```

4. **Extract User Information:**
```java
Long userId = jwtUtil.getUserIdFromToken(accessToken);
String email = jwtUtil.getEmailFromToken(accessToken);
Claims claims = jwtUtil.extractAllClaims(accessToken);
String role = claims.get("role", String.class);
```

5. **Check Expiration:**
```java
boolean expired = jwtUtil.isTokenExpired(accessToken);
// Returns: false (if within 1 hour of generation)
```

### What Still Doesn't Work

**We Still Cannot:**
- **Authenticate users via HTTP** (no filter to intercept requests)
- **Protect endpoints** (SecurityFilterChain still permitAll)
- **Login users** (no AuthController, no AuthService)
- **Refresh tokens** (no refresh endpoint)
- **Store/revoke refresh tokens** (no token storage)

**Next Steps:**
1. Create JwtAuthenticationFilter (intercept requests, validate tokens)
2. Create DTOs (LoginRequest, RegisterRequest, AuthResponse)
3. Create AuthService (login, register, refresh logic)
4. Create AuthController (REST endpoints)
5. Update SecurityFilterChain to use JWT filter

---

## Security Considerations

### What We Handle

1. **Signature Verification** - Tokens can't be forged
2. **Expiration Checking** - Old tokens rejected
3. **Algorithm Enforcement** - HS256 with strong key

### What We Don't Handle Yet (Future Enhancements)

1. **Token Revocation:**
    - Currently, tokens valid until expiration
    - Solution: Token blacklist or refresh token rotation

2. **Token Refresh Security:**
    - Refresh tokens could be stolen
    - Solution: Store refresh tokens in DB, single-use rotation

3. **Algorithm Confusion:**
    - Attackers might try "none" algorithm
    - JJWT 0.12+ rejects "none" by default ✓

4. **Key Rotation:**
    - What if secret is compromised?
    - Solution: Key versioning (kid claim)

**Teaching Point:** Security is layered. JwtUtil handles token-level security. We'll add request-level security (filter), endpoint-level security (authorization), and operational security (key rotation) in subsequent commits.

---

## Teaching Talking Points

### For Students New to JWT

**Analogy - JWT as a Driver's License:**
```
Driver's License:
- Photo, name, address, DOB (claims/payload)
- Hologram, special printing (signature)
- Expiration date
- Issued by DMV (trusted authority)

JWT:
- User ID, email, role (claims/payload)
- Cryptographic signature
- Expiration timestamp
- Issued by your server (trusted authority)

When you show your license:
- Bouncer doesn't call DMV to verify
- Bouncer checks hologram (signature) and expiration
- Bouncer trusts the license if valid

When you send JWT:
- Server doesn't query database for every request
- Server checks signature and expiration
- Server trusts the token if valid
```

### For Students Asking "Why Not Just Use Sessions?"

**Sessions are fine for:**
- Traditional web apps (server-rendered HTML)
- Single-server deployments
- Apps where server state is acceptable

**JWTs are better for:**
- REST APIs (stateless by design)
- Microservices (each service can validate independently)
- Mobile apps (no cookie support)
- Horizontal scaling (no shared session store needed)

**Teaching Point:** Neither is universally better. Choose based on your architecture. We chose JWT because we're building a REST API that might serve web and mobile clients.

### Common Mistakes to Avoid

**1. Storing Sensitive Data in JWT:**
```java
// DON'T
claims.put("password", user.getPassword());
claims.put("ssn", user.getSsn());

// DO
claims.put("email", user.getEmail());  // OK, not secret
claims.put("role", user.getRole());    // OK, not secret
```

**2. Very Long Expiration:**
```java
// DON'T - 1 year access token
jwt.access-token-expiration=31536000000

// DO - 1 hour access token
jwt.access-token-expiration=3600000
```

**3. Weak Secret:**
```properties
# DON'T
jwt.secret=secret

# DO
jwt.secret=${JWT_SECRET:at-least-32-characters-for-256-bits}
```

**4. Not Checking Token Type:**
```java
// DON'T - Accept any token
if (jwtUtil.validateToken(token)) {
    // Could be refresh token used as access token!
}

// DO - Check type (implement this check)
if (jwtUtil.validateToken(token) && jwtUtil.isAccessToken(token)) {
    // Safe
}
```

---

## Architectural Decisions Record

**ADR-014: Two-Token Strategy (Access + Refresh)**
- **Status:** Accepted
- **Context:** Need balance between security and UX
- **Decision:** Short-lived access tokens (1h) + long-lived refresh tokens (30d)
- **Consequences:**
    - Better security (limited access token exposure)
    - Good UX (infrequent re-login)
    - More complexity (two token types to manage)

**ADR-015: Include User Info in Access Token Claims**
- **Status:** Accepted
- **Context:** Need user info for authorization decisions
- **Decision:** Store email, role, name in access token
- **Consequences:**
    - Faster requests (no DB lookup for basic info)
    - Potential stale data (mitigated by short expiration)
    - Slightly larger tokens

**ADR-016: Use JJWT Library**
- **Status:** Accepted
- **Context:** Need reliable JWT library for Java
- **Decision:** JJWT 0.12.3 (jjwt-api, jjwt-impl, jjwt-jackson)
- **Consequences:**
    - Industry standard, well-tested
    - Three dependencies (modular design)
    - Fluent API, easy to use

**ADR-017: Environment Variable for JWT Secret**
- **Status:** Accepted
- **Context:** Secret key must not be in source code
- **Decision:** Use `${JWT_SECRET:default}` pattern
- **Consequences:**
    - Secure in production (env var)
    - Convenient in development (default value)
    - Must remember to set in production

---

## Commit Stats
- **Files created:** 1
    - JwtUtil.java (87 lines)
- **Files modified:** 1
    - application.properties (+4 lines)
- **Methods implemented:** 8
    - generateAccessToken, generateRefreshToken
    - validateToken, isTokenExpired
    - getUserIdFromToken, getEmailFromToken
    - extractAllClaims, extractClaim (private)
- **Configuration added:**
    - jwt.secret
    - jwt.access-token-expiration
    - jwt.refresh-token-expiration

---

## Testing the JWT Utility

### Manual Testing (After AuthController is Created)

```bash
# Generate tokens via login endpoint (future)
curl -X POST http://localhost:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"user@example.com","password":"password123"}'

# Response:
{
  "accessToken": "eyJhbG...",
  "refreshToken": "eyJhbG...",
  "expiresIn": 3600
}
```

### Decode JWT (For Debugging)

Visit https://jwt.io and paste your token to see:
- Header (algorithm)
- Payload (claims)
- Signature verification (paste your secret)

**Warning:** Never paste production tokens on jwt.io!

### Unit Test Example

```java
@SpringBootTest
class JwtUtilTest {

    @Autowired
    private JwtUtil jwtUtil;

    @Test
    void testGenerateAndValidateAccessToken() {
        User user = User.builder()
            .id(1L)
            .email("test@example.com")
            .name("Test User")
            .role(Role.SUBSCRIBER)
            .build();

        String token = jwtUtil.generateAccessToken(user);

        assertThat(jwtUtil.validateToken(token)).isTrue();
        assertThat(jwtUtil.getUserIdFromToken(token)).isEqualTo(1L);
        assertThat(jwtUtil.getEmailFromToken(token)).isEqualTo("test@example.com");
    }

    @Test
    void testExpiredToken() {
        // Would need to mock time or use very short expiration
        // Or create token with past expiration manually
    }

    @Test
    void testInvalidSignature() {
        String tamperedToken = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.tampered";
        assertThat(jwtUtil.validateToken(tamperedToken)).isFalse();
    }
}
```

---

## Resources for Students
- JWT Introduction: https://jwt.io/introduction
- JJWT GitHub: https://github.com/jwtk/jjwt
- JWT Best Practices: https://auth0.com/blog/jwt-security-best-practices/
- OWASP JWT Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/JSON_Web_Token_for_Java_Cheat_Sheet.html
- Token Storage: https://auth0.com/docs/secure/security-guidance/data-security/token-storage
- Refresh Token Rotation: https://auth0.com/blog/refresh-tokens-what-are-they-and-when-to-use-them/
