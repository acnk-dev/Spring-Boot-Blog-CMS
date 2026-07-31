# Commit 28 — Post Featured Image Upload (Service Layer)

## Overview

This commit adds `uploadFeaturedImage(Long postId, MultipartFile file, Long userId)` to `PostService` and its implementation in `PostServiceImpl`. The method allows a post's author (or an ADMIN) to upload or replace the featured image for a post. Old files are deleted from storage before the new file is written, keeping the filesystem clean.

---

## Business Logic

### Why verify ownership?

A post's featured image is part of the post's content. Allowing any authenticated user to replace it would let malicious actors deface or spam posts they do not own. Ownership verification ensures only the person responsible for the content can change its visual representation.

### Why allow ADMIN override?

Administrators are responsible for platform moderation. They may need to remove inappropriate images, fix broken uploads, or act on behalf of a user who is locked out. The ADMIN bypass gives them the tools to perform these duties without needing to impersonate the author.

### Why delete the old image?

Every `storeFile` call writes a new UUID-named file to disk. Without explicit deletion, each image replacement leaves an orphaned file that will never be referenced again. Over time, these accumulate, waste storage, and increase hosting costs. Deleting the old file immediately before storing the new one keeps the filesystem bounded to exactly one image per post.

---

## Service Layer Responsibilities

| Responsibility | Detail |
|---|---|
| Business rules enforcement | Ownership/ADMIN check before any mutation |
| Transaction management | `@Transactional` ensures the DB save and the in-memory post state are consistent; file I/O sits outside the DB transaction by necessity |
| File cleanup | Calls `fileStorageService.deleteFile()` before writing the new file |

---

## Authorization Logic

```
isOwner = post.getUser().getId().equals(userId)
isAdmin = user.getRole() == Role.ADMIN

if (!isOwner && !isAdmin) → throw UnauthorizedException
```

- **Owner check** — compares the post's author ID with the requesting user's ID (Long equality via `.equals()`).
- **Admin bypass** — checks the user's `Role` enum value against `Role.ADMIN`. This lets platform moderators act on any post.
- **Exception** — `UnauthorizedException` propagates as an HTTP 403 Forbidden response via the global exception handler.

---

## Code Flow

```
uploadFeaturedImage(postId, file, userId)
├─→ Find post by ID          (ResourceNotFoundException if missing / soft-deleted)
├─→ Find user by ID          (ResourceNotFoundException if missing / soft-deleted)
├─→ Verify ownership/admin   (UnauthorizedException if neither)
├─→ Delete old image          (if featuredImageUrl is non-null and non-blank)
├─→ Store new file            (fileStorageService.storeFile(file, "posts"))
├─→ Update post entity        (post.setFeaturedImageUrl(newImagePath))
├─→ Save to database          (postRepository.save(post))
└─→ Return PostResponse       (postMapper.toResponse(savedPost))
```

---

## Key Concepts

### Service layer vs Controller layer

The controller's job is to accept HTTP input and return HTTP output. Business decisions — who is allowed to do what, when to delete files, what constitutes a valid state change — belong in the service layer. The controller calls `uploadFeaturedImage` and trusts the service to enforce all rules.

### Business logic location

All authorization, cleanup, and persistence logic lives in `PostServiceImpl`. The controller only extracts `userId` from the security context and delegates. This keeps the controller thin and the service independently testable.

### Transaction boundaries

`@Transactional` on `uploadFeaturedImage` covers the database operations (find user, find post, save post). File I/O (`deleteFile`, `storeFile`) is not transactional — the filesystem has no rollback mechanism. The order (delete old → store new → save DB) is chosen to minimize the window of inconsistency: if the DB save fails after `storeFile`, the new file will be orphaned, but the post's `featuredImageUrl` will remain unchanged in the database, so no broken reference is persisted.

---

## Error Scenarios

| Scenario | Exception | HTTP Status |
|---|---|---|
| Post ID does not exist or is soft-deleted | `ResourceNotFoundException` | 404 |
| User ID does not exist or is soft-deleted | `ResourceNotFoundException` | 404 |
| User is neither the owner nor an ADMIN | `UnauthorizedException` | 403 |
| File fails MIME/extension validation | `BadRequestException` (thrown by `FileStorageService`) | 400 |
| File cannot be written to disk | `FileStorageException` (thrown by `FileStorageService`) | 500 |
| Database save fails | Spring `DataAccessException` → 500 | 500 |

---

## Cleanup Strategy

### Why delete old files?

1. **Storage cost** — Each uncleaned file consumes disk space indefinitely.
2. **Orphan prevention** — A file with no DB reference can never be served or deleted later; it becomes dead weight.
3. **Predictability** — One post = one image file. This invariant makes auditing storage easy.

### When deletion is skipped

If `post.getFeaturedImageUrl()` is `null` or blank, there is no old file to delete, so the delete step is skipped. This covers the first-time upload case where no previous image exists.

---

## Testing Strategy

| Test case | Setup | Expected outcome |
|---|---|---|
| Valid owner uploads image | Post owned by userId, no existing image | Returns `PostResponse` with new `featuredImageUrl` |
| Valid owner replaces image | Post owned by userId, existing image present | Old file deleted, new path saved, `PostResponse` returned |
| ADMIN uploads image | Post owned by a different user, requester is ADMIN | Succeeds — ADMIN bypass applies |
| Unauthorized user | Post owned by another user, requester is AUTHOR/SUBSCRIBER | `UnauthorizedException` thrown |
| Post not found | Non-existent postId | `ResourceNotFoundException` thrown |
| User not found | Non-existent userId | `ResourceNotFoundException` thrown |
| Invalid file type | Requester is owner, file is a `.exe` | `BadRequestException` from `FileStorageService` |
| Storage failure | `FileStorageService.storeFile` throws | `FileStorageException` propagates, DB unchanged |
