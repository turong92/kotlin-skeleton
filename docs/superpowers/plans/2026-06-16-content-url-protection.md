# Content URL Protection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a standard way to expose opaque content URLs for frontend routes and S3/CloudFront-backed content without leaking raw object keys.

**Architecture:** Keep access control separate from URL opacity. `modules:crypto` owns URL-safe opaque token encoding/decoding. `modules:storage-s3` owns public URL generation strategies and can emit raw base URLs or opaque CDN URLs. CloudFront Function support is documented as an optional edge rewrite pattern, while the default service-side posture remains private S3 + CloudFront OAC/signed access.

**Tech Stack:** Kotlin, Spring Boot auto-configuration, AES-GCM crypto module, storage/storage-s3 contracts, JUnit/Kotlin tests.

---

### Task 1: Crypto URL Token Codec

**Files:**
- Create: `modules/crypto/src/main/kotlin/dev/sumin/skeleton/crypto/OpaqueUrlTokenCodec.kt`
- Test: `modules/crypto/src/test/kotlin/dev/sumin/skeleton/crypto/OpaqueUrlTokenCodecTest.kt`

- [ ] Write failing tests for URL-safe token creation, purpose validation, expiry validation, and tamper rejection.
- [ ] Run `./gradlew :modules:crypto:test --tests '*OpaqueUrlTokenCodecTest*'` and verify RED.
- [ ] Implement a compact JSON payload encrypted through `TextEncryptor`, then base64url-wrap the ciphertext for path usage.
- [ ] Run the targeted crypto test and verify GREEN.

### Task 2: Storage Public URL Strategy

**Files:**
- Modify: `modules/storage-s3/src/main/kotlin/dev/sumin/skeleton/storage/s3/S3StorageProperties.kt`
- Create: `modules/storage-s3/src/main/kotlin/dev/sumin/skeleton/storage/s3/OpaqueStoragePublicUrlResolver.kt`
- Modify: `modules/storage-s3/src/main/kotlin/dev/sumin/skeleton/storage/s3/S3StorageAutoConfiguration.kt`
- Test: `modules/storage-s3/src/test/kotlin/dev/sumin/skeleton/storage/s3/S3StorageAutoConfigurationTest.kt`

- [ ] Write failing auto-configuration tests for `public-url.strategy=opaque`, `public-url.token-path-prefix=/c`, and fallback raw base URL behavior.
- [ ] Run `./gradlew :modules:storage-s3:test --tests '*S3StorageAutoConfigurationTest*'` and verify RED.
- [ ] Add `RAW` and `OPAQUE` public URL strategies. Raw keeps current behavior. Opaque requires an `OpaqueUrlTokenCodec` bean and emits `baseUrl + tokenPathPrefix + token`.
- [ ] Run storage-s3 tests and verify GREEN.

### Task 3: App Smoke Endpoint and Docs

**Files:**
- Modify: `apps/api/src/main/kotlin/dev/sumin/skeleton/api/SkeletonModuleController.kt`
- Modify: `apps/api/src/main/resources/application.yml`
- Modify: `docs/storage-s3.md`
- Modify: `docs/crypto.md`
- Modify: `README.md`

- [ ] Add or extend a sample endpoint that returns a generated public URL for a supplied object key.
- [ ] Document raw, opaque, and signed-access posture. Make explicit that opaque URLs do not replace authorization.
- [ ] Run `./gradlew :modules:crypto:test :modules:storage-s3:test :apps:api:test`.
- [ ] Commit after tests pass.

### Follow-up: Global Exception and Error Code

After this commit, inspect current `modules:platform` exception response and old `be-api` error code patterns. Improve the skeleton around stable error code enum/registry, global exception mapping, OpenAPI examples, and Slack/trace context integration.
