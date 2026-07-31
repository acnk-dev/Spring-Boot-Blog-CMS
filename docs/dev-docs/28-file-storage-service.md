# Commit 27: Create FileStorageService with Local Implementation

**Date:** March 20, 2026
**Commit Message:** Create FileStorageService for local file storage

---

## Overview

This commit adds file upload and retrieval capabilities to the Blog CMS. Authors need to attach featured images to posts and upload profile avatars. This requires:

1. A place to receive uploaded files (HTTP multipart request)
2. Validation — reject non-images and oversized files
3. A place to persist those files (initially: local disk)
4. A way to serve them back to readers

### Files added

| File | Purpose |
|------|---------|
| `FileStorageService.java` | Interface defining the storage contract |
| `LocalFileStorageServiceImpl.java` | Disk-based implementation |
| `FileStorageConfig.java` | Type-safe configuration properties |
| `FileStorageException.java` | New exception for I/O failures |
| `application.properties` | Added `file.*` properties |

---

## Architecture Decisions

### Decision 1: Interface + Implementation Pattern

```
FileStorageService          ← interface (the contract)
    │
    ├── LocalFileStorageServiceImpl   ← current: writes to disk
    └── S3FileStorageServiceImpl      ← future: writes to AWS S3
```

Any class that needs to store a file injects `FileStorageService`, not the concrete class:

```java
// In PostService or a future FileController:
private final FileStorageService fileStorageService;  // ✅ depends on interface

// NOT:
private final LocalFileStorageServiceImpl localStorage;  // ❌ locked to disk
```

**Why this matters:** When you switch to S3, you add `S3FileStorageServiceImpl`, annotate it with `@Primary` or a profile (`@Profile("prod")`), and delete nothing. All callers — PostService, a future AvatarController — work without a single change.

This is the **Strategy Pattern**: the algorithm (where to store) is encapsulated behind an interface and can be swapped at runtime or deployment time.

---

### Decision 2: Local Storage First (Before S3)

Starting with local disk keeps the system simple while the product is under active development:

| Concern | Local Disk | S3 |
|---------|-----------|-----|
| Setup time | 0 (no AWS account needed) | ~30 min |
| Cost | Free | Pay per GB + requests |
| Testing | `Files.createTempDir()` | Needs LocalStack or mocking |
| Visibility | Open file explorer | AWS Console |
| Latency | Sub-millisecond | ~50–200ms |
| Scale | Single server | Unlimited |
| Redundancy | Manual backups | Built-in (11 nines durability) |

The right time to migrate to S3 is when you need horizontal scaling (multiple app servers) or production durability guarantees. With the interface in place, that migration is a one-sprint task, not a cross-cutting refactor.

---

### Decision 3: Strategy Pattern for Future S3 Migration

The full migration path from local to S3 requires only **adding** code, never changing existing code (Open/Closed Principle):

**Step 1:** Add the S3 dependency to `pom.xml`:
```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3</artifactId>
</dependency>
```

**Step 2:** Create `S3FileStorageServiceImpl`:
```java
@Service
@Profile("prod")           // active only in prod
@RequiredArgsConstructor
public class S3FileStorageServiceImpl implements FileStorageService {

    private final S3Client s3Client;
    private final FileStorageConfig config;

    @Override
    public String storeFile(MultipartFile file, String directory) {
        String key = directory + "/" + generateUniqueFileName(file.getOriginalFilename());
        s3Client.putObject(PutObjectRequest.builder()
                .bucket(config.getBucketName())
                .key(key)
                .contentType(file.getContentType())
                .build(),
                RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        return key;
    }
    // ... other methods
}
```

**Step 3:** Add S3 properties to `application-prod.properties`:
```properties
file.bucket-name=my-blogcms-uploads
```

**Step 4:** Run with `--spring.profiles.active=prod`. Done.

`LocalFileStorageServiceImpl` (annotated `@Profile("dev")`) stays unchanged. Both implementations coexist.

---

## File Storage Strategy

### Directory Structure

```
uploads/                        ← file.upload-dir (configurable root)
├── posts/                      ← featured images for blog posts
│   ├── 550e8400-...-0000.jpg
│   ├── 6ba7b810-...-0000.png
│   └── ...
└── avatars/                    ← user profile pictures
    ├── 6ba7b811-...-0000.webp
    └── ...
```

The `directory` parameter in `storeFile(file, directory)` is caller-supplied:
```java
// In PostService:
fileStorageService.storeFile(image, "posts");

// In future UserService:
fileStorageService.storeFile(avatar, "avatars");
```

This keeps the controller and service clean — they declare their intent, the storage service handles the path.

---

### Unique Filename Generation (UUID + Extension)

```
Client uploads:   "my-photo.jpg"
Stored as:        "550e8400-e29b-41d4-a716-446655440000.jpg"
```

**Why not keep the original filename?**

| Problem with original names | Example |
|-----------------------------|---------|
| Collision | Two users upload "photo.jpg" — second overwrites first |
| Path traversal attack | Filename `../../etc/passwd` climbs out of upload dir |
| Shell injection | Filename `; rm -rf /` causes damage if ever shell-executed |
| Encoding issues | Spaces, Unicode, emoji in filenames break URLs and some filesystems |

**Why UUID v4?**
- 2¹²² possible values — collision probability is astronomically low
- Cryptographically random — unguessable (no enumeration attacks)
- Standard format recognized by every system
- No server state needed (no counter, no DB lookup)

**Extension is preserved** for two reasons:
1. Operating systems and browsers use it to determine how to open a file
2. Some CDNs and image processors require it for correct MIME type detection

---

## Security Considerations

### 1. File Type Validation

Two-layer check in `isValidImage()`:

```
Layer 1: MIME type (Content-Type header)
    "image/jpeg" ∈ allowedTypes? → pass

Layer 2: File extension cross-check
    "jpg" or "jpeg" AND Content-Type == "image/jpeg"? → pass
```

If either check fails, `BadRequestException` is thrown before any bytes are written to disk.

**What this catches:**
- A `.php` file renamed to `exploit.jpg` — extension check fails
- A text file sent with `Content-Type: image/jpeg` — extension doesn't match

**What this does NOT catch (known limitation):**
- A valid JPEG with malicious EXIF payload
- A polyglot file (valid JPEG that is also valid JavaScript)

**Future hardening:** Use Apache Tika to inspect magic bytes:
```java
// Future: add to isValidImage()
Tika tika = new Tika();
String detectedType = tika.detect(file.getInputStream());
return allowedTypes.contains(detectedType);
```

---

### 2. File Size Limits (DoS Prevention)

```properties
file.max-size=5242880   # 5 MB
```

Without a size limit, a single malicious request could:
- Fill the server's disk (denial of service)
- Exhaust server memory if the file is buffered in RAM
- Time out the connection (slow loris variant)

Spring Boot's own multipart limit is a second line of defense:
```properties
# Add to application.properties for defense in depth:
spring.servlet.multipart.max-file-size=5MB
spring.servlet.multipart.max-request-size=10MB
```

The Spring limit is enforced before the request even reaches the controller. The service-level check is a belt-and-suspenders guard.

---

### 3. Path Traversal Prevention

**The attack:**
```
directory = "../../etc"
filename  = "passwd"
Result:   uploadRoot/../../etc/passwd  → /etc/passwd
```

**How we prevent it:**

**Step 1 — sanitize the directory parameter:**
```java
private String sanitizeDirectory(String directory) {
    return directory.replaceAll("[./\\\\]", "").trim();
    // "../../etc" → "etcpasswd" (harmless)
}
```

**Step 2 — validate the resolved path stays within the root:**
```java
Path resolved = uploadRootPath.resolve(filePath).normalize();
if (!resolved.startsWith(uploadRootPath)) {
    throw new BadRequestException("Invalid file path: " + filePath);
}
```

`Path.normalize()` collapses `..` segments. `startsWith()` then confirms the result is still a descendant of `uploadRootPath`. Any traversal attempt throws `BadRequestException` before any I/O occurs.

---

### 4. FileStorageException vs BadRequestException

| Exception | HTTP Status | Meaning |
|-----------|------------|---------|
| `BadRequestException` | 400 | Client error — client can fix it (wrong type, too large, bad path) |
| `FileStorageException` | 500 | Server error — client cannot fix it (disk full, permission denied) |

Using separate exceptions lets `GlobalExceptionHandler` return the right HTTP status code without conditionals.

---

## Configuration Properties

### @ConfigurationProperties vs @Value

```java
// @Value approach (fragile):
@Value("${file.upload-dir}")
private String uploadDir;

@Value("${file.max-size}")
private long maxSize;
// Must repeat @Value on every field, typos cause startup failure

// @ConfigurationProperties approach (our choice):
@ConfigurationProperties(prefix = "file")
public class FileStorageConfig {
    private String uploadDir;
    private long maxSize;
    private List<String> allowedTypes;
}
// All file.* properties in one place, IDE auto-completes them
```

**Benefits of `@ConfigurationProperties`:**
1. **Discoverability** — all file settings are in one class
2. **Type safety** — `long maxSize` not `String maxSize` (no parsing errors)
3. **IDE support** — generate `spring-configuration-metadata.json` for auto-complete
4. **List binding** — comma-separated values bind to `List<String>` automatically
5. **Testability** — in tests, just `new FileStorageConfig()` and set fields

### Externalized Configuration Benefits

```properties
# Development (application.properties):
file.upload-dir=uploads          # relative path, next to the jar

# Production (environment variable override):
FILE_UPLOAD_DIR=/var/data/blogcms/uploads   # absolute, on a mounted volume
FILE_MAX_SIZE=10485760                       # 10 MB in prod
```

Spring Boot's property hierarchy means environment variables (or Docker secrets) automatically override `application.properties` without code changes. The upload directory can be a Docker volume, an NFS mount, or a GCS FUSE mount — the code doesn't care.

---

## Code Flow Diagram

```
HTTP POST /api/v1/files/upload
  multipart/form-data: file=<bytes>, directory="posts"
    │
    ▼
┌──────────────────────────────────────┐
│  Spring DispatcherServlet            │
│  Parses multipart → MultipartFile   │
└──────────────────────────────────────┘
    │
    ▼
┌──────────────────────────────────────┐
│  (Future) FileController             │
│  @PreAuthorize("hasAnyRole(...)")    │
│  calls fileStorageService.storeFile()│
└──────────────────────────────────────┘
    │
    ▼
┌──────────────────────────────────────┐
│  LocalFileStorageServiceImpl         │
│                                      │
│  1. file.isEmpty()?       → 400     │
│  2. isValidImage(file)?   → 400     │
│  3. file.getSize() > max? → 400     │
│  4. sanitizeDirectory()              │
│  5. resolveAndValidatePath()  → 400 │
│  6. Files.createDirectories()        │
│  7. generateUniqueFileName()         │
│  8. Files.copy() → disk      → 500  │
│                                      │
│  Returns: "posts/UUID.jpg"           │
└──────────────────────────────────────┘
    │
    ▼
Caller persists "posts/UUID.jpg" in DB
(e.g., post.setFeaturedImageUrl("posts/UUID.jpg"))
    │
    ▼
HTTP 201 { "filePath": "posts/UUID.jpg" }
```

---

## Key Concepts

### MultipartFile

`MultipartFile` is Spring's abstraction for a file uploaded via `multipart/form-data`:

```java
file.getOriginalFilename()  // client-supplied name (untrusted!)
file.getContentType()       // declared MIME type (untrusted!)
file.getSize()              // byte count
file.getInputStream()       // the actual file bytes
file.isEmpty()              // true if 0 bytes
```

**Teaching Point:** `getOriginalFilename()` and `getContentType()` come from the HTTP request — the client controls them. Never use them directly for security decisions. Our `isValidImage()` cross-checks both.

---

### Resource Loading

`Resource` is Spring's unified interface for anything that can be read:

```java
Resource resource = new UrlResource(path.toUri());
resource.exists()       // does the file exist?
resource.isReadable()   // can we open it?
resource.getInputStream()  // read the bytes
```

A controller can stream a `Resource` directly to the HTTP response:
```java
@GetMapping("/files/{filePath}")
public ResponseEntity<Resource> serveFile(@PathVariable String filePath) {
    Resource resource = fileStorageService.loadFileAsResource(filePath);
    return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                    "inline; filename=\"" + resource.getFilename() + "\"")
            .body(resource);
}
```

Spring handles chunked streaming — the file is never fully loaded into heap memory.

---

### @PostConstruct

```java
@PostConstruct
public void init() {
    uploadRootPath = Paths.get(fileStorageConfig.getUploadDir())
            .toAbsolutePath().normalize();
    Files.createDirectories(uploadRootPath);
}
```

`@PostConstruct` runs once after Spring finishes dependency injection. It's the correct place for initialization logic that needs injected values (like `fileStorageConfig`).

**Why not use the constructor?**
At constructor time, `@Value`/`@ConfigurationProperties` fields are not yet injected. `@PostConstruct` guarantees they are.

**Fail-fast principle:** If the directory can't be created (permissions, invalid path), the app throws at startup — not on the first upload at 2am. This is preferable.

---

## Error Handling

| Scenario | Exception | HTTP | Message |
|----------|-----------|------|---------|
| Empty file | `BadRequestException` | 400 | "File must not be empty" |
| Wrong MIME type | `BadRequestException` | 400 | "Invalid file type. Allowed types: ..." |
| File too large | `BadRequestException` | 400 | "File size exceeds the maximum allowed size of 5 MB" |
| Path traversal attempt | `BadRequestException` | 400 | "Invalid file path: ..." |
| Disk full | `FileStorageException` | 500 | "Failed to store file ..." |
| Permission denied | `FileStorageException` | 500 | "Cannot create storage directory: ..." |
| File not found on load | `ResourceNotFoundException` | 404 | "File not found with path: ..." |

`GlobalExceptionHandler` (already in the project) maps these exception types to their HTTP responses consistently.

---

## Testing Guide

### Unit Test: File Validation

```java
@ExtendWith(MockitoExtension.class)
class LocalFileStorageServiceImplTest {

    @InjectMocks
    private LocalFileStorageServiceImpl service;

    @Mock
    private FileStorageConfig config;

    @BeforeEach
    void setUp() {
        when(config.getAllowedTypes())
                .thenReturn(List.of("image/jpeg", "image/png", "image/gif", "image/webp"));
        when(config.getMaxSize()).thenReturn(5_242_880L);
        when(config.getUploadDir()).thenReturn(System.getProperty("java.io.tmpdir") + "/test-uploads");
        service.init();
    }

    @Test
    void isValidImage_validJpeg_returnsTrue() {
        MockMultipartFile file = new MockMultipartFile(
                "image", "photo.jpg", "image/jpeg", new byte[100]);
        assertTrue(service.isValidImage(file));
    }

    @Test
    void isValidImage_wrongContentType_returnsFalse() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "script.php", "application/x-php", new byte[100]);
        assertFalse(service.isValidImage(file));
    }

    @Test
    void isValidImage_mismatchedExtension_returnsFalse() {
        // Content-Type says JPEG but extension is PNG — mismatch
        MockMultipartFile file = new MockMultipartFile(
                "image", "photo.png", "image/jpeg", new byte[100]);
        assertFalse(service.isValidImage(file));
    }

    @Test
    void storeFile_tooLarge_throwsBadRequest() {
        byte[] bigFile = new byte[6 * 1024 * 1024]; // 6 MB
        MockMultipartFile file = new MockMultipartFile(
                "image", "big.jpg", "image/jpeg", bigFile);

        assertThrows(BadRequestException.class,
                () -> service.storeFile(file, "posts"));
    }

    @Test
    void generateUniqueFileName_preservesExtension() {
        String result = service.generateUniqueFileName("my-photo.jpg");
        assertTrue(result.endsWith(".jpg"));
        assertEquals(36 + 4, result.length()); // UUID(36) + ".jpg"(4)
    }

    @Test
    void generateUniqueFileName_twoCallsProduceDifferentNames() {
        String name1 = service.generateUniqueFileName("photo.jpg");
        String name2 = service.generateUniqueFileName("photo.jpg");
        assertNotEquals(name1, name2);
    }
}
```

---

### Integration Test: File Upload End-to-End

```java
@SpringBootTest
@AutoConfigureMockMvc
class FileStorageIntegrationTest {

    @Autowired
    private FileStorageService fileStorageService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp(@Autowired FileStorageConfig config) {
        config.setUploadDir(tempDir.toString());
        // Re-init is not needed because @TempDir shares the same path
    }

    @Test
    void storeAndLoad_validJpeg_roundTrip() throws IOException {
        byte[] imageBytes = loadTestResource("test-image.jpg");
        MockMultipartFile file = new MockMultipartFile(
                "image", "test-image.jpg", "image/jpeg", imageBytes);

        String storedPath = fileStorageService.storeFile(file, "posts");

        assertNotNull(storedPath);
        assertTrue(storedPath.startsWith("posts/"));
        assertTrue(storedPath.endsWith(".jpg"));

        Resource resource = fileStorageService.loadFileAsResource(storedPath);
        assertTrue(resource.exists());
        assertArrayEquals(imageBytes, resource.getContentAsByteArray());
    }

    @Test
    void deleteFile_removesFromDisk() {
        MockMultipartFile file = new MockMultipartFile(
                "image", "photo.png", "image/png", new byte[512]);

        String path = fileStorageService.storeFile(file, "avatars");
        fileStorageService.deleteFile(path);

        assertThrows(ResourceNotFoundException.class,
                () -> fileStorageService.loadFileAsResource(path));
    }
}
```

---

### Manual Test with curl

```bash
# Upload a file (requires AUTHOR or ADMIN token — future FileController)
curl -X POST http://localhost:8082/api/v1/files/upload \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/path/to/image.jpg" \
  -F "directory=posts"

# Expected response:
# { "filePath": "posts/550e8400-e29b-41d4-a716-446655440000.jpg" }

# Serve the file (public, future endpoint)
curl http://localhost:8082/api/v1/files/posts/550e8400-e29b-41d4-a716-446655440000.jpg \
  --output downloaded.jpg
```

---

## Future Migration to S3

### What Changes

| Component | Local | S3 |
|-----------|-------|----|
| Storage | Host disk | AWS S3 bucket |
| storeFile() | `Files.copy()` | `s3Client.putObject()` |
| loadFileAsResource() | `UrlResource(path)` | `UrlResource(presignedUrl)` |
| deleteFile() | `Files.deleteIfExists()` | `s3Client.deleteObject()` |
| URL served to client | `/api/v1/files/{path}` | `https://bucket.s3.amazonaws.com/{key}` |

### What Does NOT Change

- `FileStorageService` interface — identical
- `FileStorageConfig` — add `bucketName` field, nothing removed
- `PostService` / any caller — zero changes
- Validation logic — can be shared in a base class or utility

### Spring Profile Swap

```java
@Service
@Profile("dev")          // local storage in development
public class LocalFileStorageServiceImpl implements FileStorageService { ... }

@Service
@Profile("prod")         // S3 in production
public class S3FileStorageServiceImpl implements FileStorageService { ... }
```

Deploy with `-Dspring.profiles.active=prod` and the S3 implementation is active. No code duplication, no breaking changes.

---

## Commit Stats

| Metric | Value |
|--------|-------|
| New files | 4 |
| Modified files | 1 (`application.properties`) |
| Lines of code | ~250 |
| Lines of documentation | this file |

---

## What Works Now

- Upload any image (JPEG, PNG, GIF, WebP) up to 5 MB
- Files stored under `uploads/{directory}/UUID.ext`
- Directories created on demand
- Path traversal attacks blocked
- File type validated by MIME + extension cross-check
- Load stored files as streamable `Resource`
- Delete stored files (idempotent)
- Configuration externalized to `application.properties`

## What's Missing (Next Steps)

- `FileController` — REST endpoint to accept `MultipartFile` from HTTP clients
- Static resource serving — expose `uploads/` as a URL path (Spring MVC `ResourceHandler`)
- Magic-byte validation (Apache Tika) — defend against polyglot files
- Image resizing — generate thumbnails for post cards (future: Thumbnailator)
- `spring.servlet.multipart.max-file-size=5MB` — add Spring-level size guard as defence in depth

---

## Resources

- [Spring MultipartFile docs](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/multipart/MultipartFile.html)
- [Spring @ConfigurationProperties](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.external-config.typesafe-configuration-properties)
- [OWASP — Unrestricted File Upload](https://owasp.org/www-community/vulnerabilities/Unrestricted_File_Upload)
- [Path Traversal — OWASP](https://owasp.org/www-community/attacks/Path_Traversal)
- [Apache Tika (magic-byte inspection)](https://tika.apache.org/)
- [AWS SDK for Java v2 — S3](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3.html)
