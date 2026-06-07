# REST Operation Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Standardize create, command, async, and paginated REST operation responses for the composable backend skeleton.

**Architecture:** `modules/platform` owns reusable HTTP response helpers and page query DTOs. `apps/api` exposes small example endpoints so the contract is visible in runtime tests and Swagger. OpenAPI remains code-first and verifies HTTP status/schema/query parameter shape.

**Tech Stack:** Kotlin 2.2, Spring Boot 4, Spring MVC, Jakarta Bean Validation, Springdoc OpenAPI, MockMvc JSON integration tests.

---

### Task 1: Runtime Operation Contract

**Files:**
- Create: `apps/api/src/test/kotlin/dev/sumin/skeleton/api/OperationContractIntegrationTest.kt`

- [x] **Step 1: Write failing tests**

Assert:
- `POST /api/v1/examples/items` returns `201`, `Location`, `{value, meta}`.
- `DELETE /api/v1/examples/items/{id}` returns `204` with an empty body.
- `POST /api/v1/examples/jobs` returns `202`, `{value.jobId, value.status, meta}`.
- `GET /api/v1/examples/items?page=1&size=2` returns `{values, pagination, meta}`.
- invalid page query returns `400 Validation failed` with `errors[]`.

- [x] **Step 2: Verify RED**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :apps:api:test --tests dev.sumin.skeleton.api.OperationContractIntegrationTest
```

Expected: FAIL because the example endpoints and platform helpers do not exist.

### Task 2: Platform Helpers

**Files:**
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/ApiResponseEntity.kt`
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/PageQuery.kt`

- [x] **Step 1: Implement HTTP response helpers**

Add:
- `ApiResponseEntity.created(location: URI, value: T)`
- `ApiResponseEntity.accepted(value: T)`
- `ApiResponseEntity.noContent()`

- [x] **Step 2: Implement page query DTO**

Add `PageQuery(page: Int = 0, size: Int = 20)` with validation:
- `page >= 0`
- `1 <= size <= 100`

### Task 3: Example Endpoints

**Files:**
- Create: `apps/api/src/main/kotlin/dev/sumin/skeleton/api/OperationExampleController.kt`

- [x] **Step 1: Implement example operation endpoints**

Use platform helpers and `PageQuery` to demonstrate the standard contracts.

- [x] **Step 2: Verify runtime tests GREEN**

Run the operation contract test.

### Task 4: OpenAPI Contract

**Files:**
- Modify: `apps/api/src/test/kotlin/dev/sumin/skeleton/api/OpenApiDocumentationIntegrationTest.kt`

- [x] **Step 1: Assert operation contracts in `/api/v1/docs`**

Verify `201`, `202`, `204`, `Location`, and `page/size` query parameters.

- [x] **Step 2: Verify OpenAPI test GREEN**

Run the OpenAPI integration test.

### Task 5: Docs and Commit

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`
- Modify: `CHANGELOG.md`

- [x] **Step 1: Document operation conventions**

Record create/command/async/page conventions.

- [x] **Step 2: Full verification and commit**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew clean test
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :apps:api:bootJar
git diff --check
git add .
git commit -m "Standardize REST operation contracts"
```
