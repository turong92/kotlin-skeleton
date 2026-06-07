# Validation Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make invalid API requests fail predictably through Bean Validation and the standard `ApiError.errors[]` response shape.

**Architecture:** `modules/platform` owns validation error serialization through `GlobalExceptionHandler`. `modules/auth` declares request DTO constraints and request-specific validators. `apps/api` verifies runtime error JSON and generated OpenAPI schema.

**Tech Stack:** Kotlin 2.2, Spring Boot 4, Jakarta Bean Validation, Spring MVC, Springdoc OpenAPI, MockMvc JSON integration tests.

---

### Task 1: Runtime Validation Contract

**Files:**
- Modify: `apps/api/src/test/kotlin/dev/sumin/skeleton/auth/AuthControllerIntegrationTest.kt`

- [ ] **Step 1: Add failing tests**

Assert that `POST /api/v1/auth/login` returns `400 Validation failed` with `errors[]` when:
- `email` is malformed and `password` is blank.
- no `accountId`, `username`, or `email` identifier is supplied.

- [ ] **Step 2: Verify RED**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :apps:api:test --tests dev.sumin.skeleton.auth.AuthControllerIntegrationTest
```

Expected: FAIL because the current controller returns `401 Unauthorized` instead of validation errors.

### Task 2: Request DTO Constraints

**Files:**
- Modify: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/api/AuthController.kt`
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/api/ValidPasswordLoginRequest.kt`

- [ ] **Step 1: Add Bean Validation constraints**

Add `@Valid` to the login request body. Add field constraints for `accountId`, `username`, `email`, and `password`.

- [ ] **Step 2: Add identifier validator**

Add a class-level validator that emits a field error at `identifier` when all login identifiers are absent or blank.

- [ ] **Step 3: Verify GREEN**

Run the AuthController integration test and confirm the new validation tests pass.

### Task 3: OpenAPI Constraint Contract

**Files:**
- Modify: `apps/api/src/test/kotlin/dev/sumin/skeleton/api/OpenApiDocumentationIntegrationTest.kt`

- [ ] **Step 1: Assert generated request schema constraints**

Verify `PasswordLoginRequest` exposes `email` format, max lengths, and required `password` through `/api/v1/docs`.

- [ ] **Step 2: Verify OpenAPI test**

Run the OpenAPI integration test and confirm it passes.

### Task 4: Docs and Verification

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Document validation convention**

Record that request DTOs should use Jakarta Bean Validation and invalid requests should return `ApiError.errors[]`.

- [ ] **Step 2: Full verification and commit**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew clean test
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :apps:api:bootJar
git diff --check
git add .
git commit -m "Standardize request validation errors"
```
