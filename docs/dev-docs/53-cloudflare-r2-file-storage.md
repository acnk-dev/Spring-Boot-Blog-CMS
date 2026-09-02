# Commit 53 — Cloudflare R2 File Storage (Production Profile)

## Overview

This commit adds a second `FileStorageService` implementation —
`R2FileStorageServiceImpl` — backed by Cloudflare R2 instead of local disk, active only
under the `prod` Spring profile. `LocalFileStorageServiceImpl` continues to handle every
other case (including the default, no-profile case used for day-to-day development).

This is the migration doc 28 explicitly planned for when it introduced
`FileStorageService` as an interface: "when you switch to S3, you add
`S3FileStorageServiceImpl` ... and delete nothing." R2 is the S3-compatible target chosen
instead of AWS S3 (see Decision section below for why), but the shape of the change is
exactly what doc 28 predicted — a new implementation, zero changes to any caller.

---

## Problem / Motivation

`LocalFileStorageServiceImpl` writes uploaded post images and avatars to a directory on the
app server's own disk (doc 28). That's fine for a single instance in development, but breaks
down for anything resembling a real deployment:

- **No horizontal scaling** — a second app instance wouldn't see files written by the first.
- **No durability guarantee** — disk loss means file loss; there's no redundancy.
- **Coupling storage lifetime to app server lifetime** — redeploying to a new host loses
  every previously uploaded file unless the disk is a persistent volume, which most simple
  deployment targets don't provide by default.

The `FileStorageService` interface (doc 28) was built specifically so this migration would
be additive, not a rewrite.

---

## Decision

### Cloudflare R2 over AWS S3

R2 implements the S3 API, so the existing AWS SDK v2 (`software.amazon.awssdk:s3`) works
against it unchanged — only the endpoint, region, and credentials differ. R2 was chosen over
actual AWS S3 for one concrete reason: **R2 has zero egress fees**. A blog's images are read
far more often than written (every page view re-serves the featured image), so egress cost
dominates the bill for this workload shape. S3 charges per-GB egress; R2 doesn't. For a
teaching project this also avoids needing a full AWS account/IAM setup — an R2 API token from
the Cloudflare dashboard is enough.

### Public bucket + custom domain over presigned URLs

Two ways to make an R2 object readable over HTTP were considered:

| Approach | How it works | Trade-off |
|---|---|---|
| **Public bucket + custom domain (chosen)** | Bucket is flagged public in R2 settings; a domain/subdomain is mapped to it. URLs are permanent plain strings. | Anyone with the URL can read the object, forever, with no auth check — acceptable because post images/avatars are meant to be public anyway (same exposure as any `<img src>` on a public blog). |
| Presigned URLs | Bucket stays private; the backend signs a time-limited URL per request using its R2 credentials. | Can't be stored as a permanent `featuredImageUrl` in the DB (it expires) — would require storing the object key instead and re-signing on every read. Right tool for access-controlled content, not needed here. |

Public bucket + custom domain was chosen because it keeps `featuredImageUrl` behaving
exactly like it does today with local storage — a stable string, saved once, served forever
— with no signing logic added anywhere on the read path.

### Profile selection: `@Profile("!prod")`, not `@Profile("dev")`

The original plan (doc 28's "Spring Profile Swap" sketch) suggested `@Profile("dev")` for
local storage and `@Profile("prod")` for the cloud implementation. That would have been a
regression: **no `@Profile` annotation existed anywhere in this codebase before this
commit**, and the app normally runs with no active profile set at all (CLAUDE.md's mention of
`-Dspring-boot.run.profiles=dev` for seeding is unrelated — `DataSeeder` is gated by the
`app.seed.enabled` property, not a Spring profile).

If `LocalFileStorageServiceImpl` had been annotated `@Profile("dev")` literally, a default
run (no profile) would have had **zero** `FileStorageService` beans on the context, and
anything injecting it (`PostServiceImpl`) would fail at startup with
`NoSuchBeanDefinitionException`.

`@Profile("!prod")` avoids this: local storage stays active for the default case and every
profile except `prod`, and `R2FileStorageServiceImpl` only takes over when `prod` is
explicitly activated. This preserves today's default behavior exactly while still giving
`prod` its own storage backend.

### `storeFile()` returns a full URL, not a relative path

`LocalFileStorageServiceImpl.storeFile()` returns a relative path (`"posts/uuid.jpg"`),
served back out through this app's own `GET /api/v1/files/{path}` endpoint (doc 28).
R2 objects are served directly by Cloudflare via the public bucket URL — not by this app —
so `R2FileStorageServiceImpl.storeFile()` returns the **full public URL**
(`https://<public-url>/posts/uuid.jpg`) instead. `deleteFile()` and `loadFileAsResource()`
both accept either form (bare key or full URL) via a small `keyFromFilePath()` helper, since
callers persist whatever `storeFile()` returned and that value needs to resolve back to the
same R2 key regardless of which implementation originally produced it.

---

## Changes Made

### `pom.xml`

Added the AWS SDK v2 BOM (for consistent module versioning) and the S3 module:

```xml
<properties>
    <aws-sdk.version>2.29.52</aws-sdk.version>
</properties>

<dependencyManagement>
    <dependency>
        <groupId>software.amazon.awssdk</groupId>
        <artifactId>bom</artifactId>
        <version>${aws-sdk.version}</version>
        <type>pom</type>
        <scope>import</scope>
    </dependency>
</dependencyManagement>

<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3</artifactId>
</dependency>
```

### `config/R2Config.java` (new)

`@ConfigurationProperties(prefix = "r2")` — the same type-safe-properties pattern as
`FileStorageConfig` (doc 28). Five fields: `accountId`, `accessKeyId`, `secretAccessKey`,
`bucketName`, `publicUrl`.

### `config/S3ClientConfig.java` (new)

```java
@Configuration
@Profile("prod")
@RequiredArgsConstructor
public class S3ClientConfig {

    private final R2Config r2Config;

    @Bean
    public S3Client r2Client() {
        String endpoint = "https://" + r2Config.getAccountId() + ".r2.cloudflarestorage.com";

        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of("auto"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                r2Config.getAccessKeyId(),
                                r2Config.getSecretAccessKey())))
                .build();
    }
}
```

Three settings distinguish this from a real AWS S3 client: the endpoint override (points at
R2, not `s3.amazonaws.com`), region `"auto"` (R2 has no regions but the SDK requires a value),
and R2-issued credentials instead of AWS IAM keys. `@Profile("prod")` means this bean — and
the `S3Client` it produces — is never constructed outside the `prod` profile, so no R2
credentials need to be valid (or even present) for local development.

### `service/impl/R2FileStorageServiceImpl.java` (new)

Implements all five `FileStorageService` methods, structurally identical to
`LocalFileStorageServiceImpl` (same validation order: empty check → MIME/extension check →
size check → sanitize directory → unique filename), swapping disk I/O for `S3Client` calls:

```java
@Override
public String storeFile(MultipartFile file, String directory) {
    // ... same validation as LocalFileStorageServiceImpl ...

    String key = safeDirectory + "/" + uniqueFileName;

    r2Client.putObject(
            PutObjectRequest.builder()
                    .bucket(r2Config.getBucketName())
                    .key(key)
                    .contentType(file.getContentType())
                    .build(),
            RequestBody.fromInputStream(file.getInputStream(), file.getSize()));

    return r2Config.getPublicUrl() + "/" + key;
}
```

`deleteFile()` uses `DeleteObjectRequest` (idempotent — R2, like S3, returns success even if
the key doesn't exist, matching `Files.deleteIfExists()`'s semantics in the local
implementation). `loadFileAsResource()` builds a `UrlResource` from the public URL directly,
since the bucket being public means no signed request is needed to read it.

### `service/impl/LocalFileStorageServiceImpl.java` (modified)

Added `@Profile("!prod")` — see Decision section above for why this exact expression was
chosen over the originally-planned `@Profile("dev")`.

### `application.properties`

```properties
# Cloudflare R2 Object Storage (S3-compatible)
# Only used when running with the "prod" profile — R2FileStorageServiceImpl
# replaces LocalFileStorageServiceImpl in that profile.
r2.account-id=${R2_ACCOUNT_ID:}
r2.access-key-id=${R2_ACCESS_KEY_ID:}
r2.secret-access-key=${R2_SECRET_ACCESS_KEY:}
r2.bucket-name=${R2_BUCKET_NAME:}
r2.public-url=${R2_PUBLIC_URL:}
```

Empty defaults are intentional — these properties are only read when `S3ClientConfig`'s bean
is constructed, which only happens under `prod`, so an empty default never causes a startup
failure in the default/dev case.

### `.env.example` / `.env`

Added `R2_ACCOUNT_ID`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`, `R2_BUCKET_NAME`,
`R2_PUBLIC_URL`. Sourced the same way as `GEMINI_API_KEY`/`MAILTRAP_*` — via IntelliJ Run/
Debug Configuration environment variables (or shell `export` when running through
`./mvnw spring-boot:run`), since Spring Boot does not read `.env` files natively; these
files exist purely as reference/documentation of which variables exist.

---

## Concepts Introduced

### Getting R2 credentials from the Cloudflare dashboard

- **Account ID**: R2 → Overview, shown account-wide.
- **Bucket name**: R2 → Overview → Create bucket.
- **Access key ID / secret access key**: R2 → Manage API tokens → Create API token, scoped to
  **Object Read & Write** on the specific bucket. The secret is shown exactly once at
  creation time.
- **Public URL**: bucket → Settings → Public access — either the auto-generated `r2.dev`
  subdomain (fine for testing; Cloudflare flags it as rate-limited, not meant for production
  traffic) or a custom domain connected through Cloudflare's DNS.

### `@Profile` expressions beyond a bare name

Spring Profile expressions support boolean logic, not just single names:
`@Profile("!prod")` means "active whenever `prod` is *not* among the active profiles" —
including the common case of no active profile at all. This is different from
`@Profile("dev")`, which requires `dev` to be explicitly named as active. Choosing between
them is really a question of which one should be the *default*: `!prod` makes local storage
the default-safe choice; `dev` would make it opt-in only.

### Why the AWS SDK works against a non-AWS service

R2 deliberately implements the S3 REST API surface (`PutObject`, `GetObject`,
`DeleteObject`, etc.) so that any S3-compatible client — official AWS SDKs included — can
talk to it by pointing `endpointOverride` at R2 instead of `s3.amazonaws.com`. This is the
same "adopt a de facto standard API to avoid forcing a new client" strategy seen with
Gemini's OpenAI-compatible endpoint (doc 47) — different vendor, same idea: implement a
widely-adopted API shape so existing tooling works unmodified.

---

## Testing Checklist

- [ ] App starts with no active profile (or any profile except `prod`) — logs show
  `File storage root initialized at: ...` from `LocalFileStorageServiceImpl`; no `S3Client`
  bean is constructed
- [ ] App starts with `-Dspring-boot.run.profiles=prod` and valid `R2_*` env vars — no
  `File storage root initialized` log line; startup succeeds
- [ ] App fails fast under `prod` with a blank `R2_ACCESS_KEY_ID` — confirms the R2 bean
  wiring is actually exercised, not silently skipped
- [ ] `POST /api/v1/posts/{id}/image` under `prod` returns a `featuredImageUrl` shaped like
  `https://<public-url>/posts/<uuid>.jpg` (a full URL, not a relative path)
- [ ] The returned URL is publicly fetchable (`curl -I <url>` → `200 OK`) and the object is
  visible in the Cloudflare R2 dashboard under `posts/`
- [ ] Uploading a second image to the same post removes the first object from R2 (dashboard
  check, or `curl -I` on the old URL → `404`)
- [ ] Invalid file type / oversized file still return `400` under `prod`, identically to the
  local-storage behavior
- [ ] Maven build passes: `./mvnw compile`
