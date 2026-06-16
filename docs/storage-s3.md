# S3 Storage

`modules:storage` owns vendor-neutral contracts. `modules:storage-s3` adapts
those contracts to AWS S3.

## Module Shape

- `ObjectStorage`: upload bytes, copy, move, list, metadata, delete, public URL.
- `PresignedStorage`: `ObjectStorage` plus PUT/GET presign and multipart upload.
- `StoragePublicUrlResolver`: optional public URL or CloudFront URL strategy.
- `StorageFileValidator`: shared file size, extension, and content-type guard.

`move` is implemented as copy plus delete in the core contract. Provider modules
can override it later if a backend supports an atomic rename-like operation.

## Configuration

```yaml
skeleton:
  storage-s3:
    enabled: true
    bucket: app-uploads
    region: ap-northeast-2
    credentials:
      profile: skeleton-dev
    public-url:
      base-url: https://cdn.example.com/uploads
      strategy: RAW
      token-path-prefix: /c
      token-purpose: storage-public-url
    presign:
      upload: 10m
      download: 10m
      multipart-part: 15m
```

If `bucket` is missing, the S3 client and presigner can exist but the
`PresignedStorage` bean is not created.

## Direct Object Operations

```kotlin
val uploaded = storage.upload(
    UploadObjectRequest(
        key = ObjectKey("avatars/user-1.png"),
        content = bytes,
        contentType = "image/png",
        metadata = mapOf("owner" to "user-1"),
    ),
)

val copied = storage.copy(
    CopyObjectRequest(
        sourceKey = ObjectKey("avatars/user-1.png"),
        destinationKey = ObjectKey("archive/user-1.png"),
    ),
)

val listed = storage.list(ListObjectsRequest(prefix = "avatars/", maxKeys = 100))
```

Result DTOs include the object key, ETag/version when the provider returns them,
and `publicUrl` when a resolver is configured.

## Public URL Strategies

`public-url.strategy=RAW` keeps the existing behavior:

```text
https://cdn.example.com/uploads/images/cat.png
```

`public-url.strategy=OPAQUE` emits a URL-safe encrypted token instead of the raw
object key:

```text
https://cdn.example.com/c/<opaque-token>
```

OPAQUE requires `modules:crypto` to create an `OpaqueUrlTokenCodec` bean, so a
valid `skeleton.crypto.keys.*` configuration must exist. The token payload stores
the object key with purpose `storage-public-url` by default.

Opaque URLs are for URL shape privacy and routing indirection. They are not a
security boundary by themselves. For private content, keep S3 private, connect
CloudFront to S3 through Origin Access Control, and use CloudFront signed URLs or
signed cookies when clients should access content through the CDN.

CloudFront Function rewrite can still be used by a project-specific edge layer:
the function reads `/c/<opaque-token>` and rewrites to the resolved origin key.
Keep that as an opt-in deployment artifact because edge secret management and
rotation policy are environment-specific.

## Presigned Operations

Use presigned uploads when a browser or mobile client should send bytes directly
to S3:

```kotlin
val url = storage.presignUpload(
    PresignedUploadRequest(
        key = ObjectKey("avatars/user-1.png"),
        contentType = "image/png",
        contentLength = 1024,
    ),
)
```

Use multipart when large files need resumable part uploads. The skeleton exposes
start, part presign, complete, and abort as stable DTOs.

## Testing Posture

The current tests prove presign, multipart, metadata, delete, upload, copy, move,
list, and public URL strategy selection without contacting AWS. A real AWS or
LocalStack smoke run is still the right final check for a service that enables
this module in an environment.
