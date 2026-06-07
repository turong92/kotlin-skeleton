# OpenAPI Module Composition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Swagger/OpenAPI documentation assemble itself from capability modules while preserving the standard REST response envelopes.

**Architecture:** `modules/platform` owns OpenAPI defaults, standard schemas, trace header documentation, and common error response decoration. `modules/auth` contributes bearer JWT security when the auth capability is present. `apps/api` keeps the Springdoc UI dependency and verifies the generated `/api/v1/docs` contract.

**Tech Stack:** Kotlin 2.2, Spring Boot 4, Springdoc OpenAPI 3, Swagger Core annotations/models, MockMvc JSON integration tests.

---

### Task 1: OpenAPI Contract Test

**Files:**
- Create: `apps/api/src/test/kotlin/dev/sumin/skeleton/api/OpenApiDocumentationIntegrationTest.kt`

- [ ] **Step 1: Write the failing test**

Create a MockMvc integration test that calls `/api/v1/docs` and asserts:
- `info.title` and `info.version` are set by platform auto-configuration.
- every documented operation has trace headers.
- standard success schemas expose `value` and `meta`.
- `ApiError` is registered as a component schema.
- bearer JWT security is registered when `modules/auth` is present.

- [ ] **Step 2: Run the test to verify RED**

Run:

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :apps:api:test --tests dev.sumin.skeleton.api.OpenApiDocumentationIntegrationTest
```

Expected: FAIL because standard info/security/error/headers are not yet added.

### Task 2: Platform OpenAPI Auto-Configuration

**Files:**
- Modify: `modules/platform/build.gradle.kts`
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/openapi/OpenApiProperties.kt`
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/openapi/PlatformOpenApiAutoConfiguration.kt`
- Create: `modules/platform/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

- [ ] **Step 1: Add Springdoc/OpenAPI compile dependencies to platform**

Add `springdoc-openapi-starter-webmvc-api` and Swagger annotations/models dependencies needed by the auto-configuration.

- [ ] **Step 2: Implement platform auto-configuration**

Register an OpenAPI bean/customizer that:
- sets title/version defaults,
- registers `ApiError`, `ResponseMeta`, and `PaginationMeta` schemas,
- adds common trace headers to API operations,
- adds common `400`, `404`, and `500` error responses using `ApiError`.

- [ ] **Step 3: Run the OpenAPI test**

Expected: remaining RED should only concern auth bearer security if platform is correct.

### Task 3: Auth OpenAPI Auto-Configuration

**Files:**
- Modify: `modules/auth/build.gradle.kts`
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/openapi/AuthOpenApiAutoConfiguration.kt`
- Create or update: `modules/auth/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

- [ ] **Step 1: Add Springdoc/OpenAPI compile dependencies to auth**

Use the same OpenAPI API dependencies as platform.

- [ ] **Step 2: Register JWT bearer security**

When auth is present, register:
- component security scheme `bearerAuth`,
- global security requirement for `/api/v1/auth/me` and other authenticated API operations,
- public exceptions for `/api/v1/auth/login`, `/api/v1/auth/social/*/login`, `/api/v1/hello`, docs, health.

- [ ] **Step 3: Run the OpenAPI test**

Expected: GREEN.

### Task 4: Documentation

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Document module-composed Swagger**

Record that modules contribute OpenAPI metadata through auto-configuration, and application developers mostly write DTOs plus optional endpoint summaries.

### Task 5: Verification and Commit

- [ ] **Step 1: Run focused OpenAPI test**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :apps:api:test --tests dev.sumin.skeleton.api.OpenApiDocumentationIntegrationTest
```

- [ ] **Step 2: Run full verification**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew clean test
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :apps:api:bootJar
git diff --check
```

- [ ] **Step 3: Commit**

```bash
git add .
git commit -m "Compose OpenAPI docs from modules"
```
