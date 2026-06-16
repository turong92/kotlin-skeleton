# Error Code Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add stable namespaced string error codes to all standard API error responses.

**Architecture:** `modules:platform` defines the shared error contract and common codes. Capability modules define module-local codes and throw `ApplicationException` with those codes. Direct error writers, such as auth and rate-limit filters, use the same `ApiError` DTO. Public responses use stable `code` values and do not expose RFC problem `type` URIs.

**Tech Stack:** Kotlin, Spring Boot MVC, MockMvc, Jackson, JUnit.

---

### Task 1: Platform Error Contract

**Files:**
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/ErrorCode.kt`
- Modify: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/ApiError.kt`
- Modify: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/ApplicationException.kt`
- Modify: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/GlobalExceptionHandler.kt`
- Test: `apps/api/src/test/kotlin/dev/sumin/skeleton/api/HelloControllerIntegrationTest.kt`

- [x] Write failing tests asserting `code` and safe `detail` for missing routes and unknown exceptions.
- [x] Run `./gradlew :apps:api:test --tests '*HelloControllerIntegrationTest*'` and verify RED.
- [x] Implement `ErrorCode`, `PlatformErrorCode`, `ApiError.code`, `ApiError.data`, and handler mappings.
- [x] Run the targeted test and verify GREEN.

### Task 2: Module Error Codes

**Files:**
- Modify: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/api/AuthController.kt`
- Modify: `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/OAuthLoginExceptions.kt`
- Modify: `modules/payment/src/main/kotlin/dev/sumin/skeleton/payment/PaymentExceptions.kt`
- Test: existing auth/social/payment integration tests.

- [x] Write failing assertions for auth invalid credentials and payment routing/provider error codes.
- [x] Run targeted tests and verify RED.
- [x] Add module-local `ErrorCode` enums and update exceptions.
- [x] Run targeted tests and verify GREEN.

### Task 3: Direct Error Writers and OpenAPI

**Files:**
- Modify: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/security/AuthErrorWriter.kt`
- Modify: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/web/RateLimit.kt`
- Modify: `modules/idempotency/src/main/kotlin/dev/sumin/skeleton/idempotency/IdempotencyHandlerInterceptor.kt`
- Modify: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/openapi/PlatformOpenApiAutoConfiguration.kt`
- Test: `apps/api/src/test/kotlin/dev/sumin/skeleton/api/OpenApiDocumentationIntegrationTest.kt`
- Test: `apps/api/src/test/kotlin/dev/sumin/skeleton/api/WebPolicyRateLimitIntegrationTest.kt`

- [x] Write failing assertions for direct writer `code` fields and OpenAPI schema.
- [x] Run targeted tests and verify RED.
- [x] Update writers and OpenAPI schema.
- [x] Run targeted tests and verify GREEN.

### Task 4: Verification and Commit

- [x] Run `./gradlew :modules:platform:test :modules:auth:test :modules:auth-social:test :modules:payment:test`.
- [x] Run `./gradlew :apps:api:test`.
- [x] Run `git diff --check`.
- [x] Commit with `feat: standardize error code responses`.
