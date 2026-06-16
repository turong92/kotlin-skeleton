# Async Notification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `modules:async-notification` so one dependency wires async execution, notification publishing, and uncaught `@Async` exception notification.

**Architecture:** Keep `modules:async` notification-agnostic by allowing its default `AsyncConfigurer` to consume an optional `AsyncUncaughtExceptionHandler`. Add `modules:async-notification` as the bundle/adapter module with `api(project(":modules:async"))` and `api(project(":modules:notification"))`, publishing `NotificationEvent` on uncaught `@Async` failures. Use `apps:api` as the composition proof by depending on the bundle and exercising a failing async method in tests.

**Tech Stack:** Kotlin, Spring Boot auto-configuration, Spring `@Async`, `AsyncUncaughtExceptionHandler`, Gradle `java-library`, existing notification contracts.

---

## File Structure

- Modify `build.gradle.kts`: apply `java-library` to Kotlin subprojects so bundle modules can use `api(...)`.
- Modify `settings.gradle.kts`: include `:modules:async-notification`.
- Modify `modules/async/src/main/kotlin/dev/sumin/skeleton/async/SkeletonAsyncAutoConfiguration.kt`: inject optional `AsyncUncaughtExceptionHandler` into default `AsyncConfigurer`.
- Modify `modules/async/src/test/kotlin/dev/sumin/skeleton/async/SkeletonAsyncAutoConfigurationTest.kt`: prove handler pass-through and custom handler backoff.
- Create `modules/async-notification/build.gradle.kts`: bundle dependencies and test dependencies.
- Create `modules/async-notification/src/main/kotlin/dev/sumin/skeleton/async/notification/AsyncNotificationProperties.kt`: `skeleton.async-notification` properties.
- Create `modules/async-notification/src/main/kotlin/dev/sumin/skeleton/async/notification/AsyncNotificationExceptionHandler.kt`: maps uncaught async failures to `NotificationEvent`.
- Create `modules/async-notification/src/main/kotlin/dev/sumin/skeleton/async/notification/AsyncNotificationAutoConfiguration.kt`: contributes handler when enabled and publisher exists.
- Create `modules/async-notification/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`: registers auto-config.
- Create `modules/async-notification/src/test/kotlin/dev/sumin/skeleton/async/notification/AsyncNotificationExceptionHandlerTest.kt`: event contract and failure policy tests.
- Create `modules/async-notification/src/test/kotlin/dev/sumin/skeleton/async/notification/AsyncNotificationAutoConfigurationTest.kt`: auto-config tests.
- Modify `apps/api/build.gradle.kts`: replace direct `async` + `notification` dependencies with `async-notification` where possible.
- Modify `apps/api/src/main/kotlin/dev/sumin/skeleton/api/SkeletonModuleController.kt`: add workbench endpoint that triggers failing `@Async` service.
- Modify `apps/api/src/test/kotlin/dev/sumin/skeleton/api/SkeletonModuleCompositionIntegrationTest.kt`: assert the bundle publishes `async-exception` notification.
- Modify `README.md`, `docs/async.md`, `docs/logging.md`, and roadmap docs.

## Task 1: Enable Bundle API Dependencies

**Files:**
- Modify: `build.gradle.kts`

- [ ] **Step 1: Write the failing check**

Run:

```bash
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :help --dry-run
```

Expected before implementation: no direct failure, but `api(...)` will be unavailable once `modules:async-notification/build.gradle.kts` is added without `java-library`.

- [ ] **Step 2: Apply `java-library` to subprojects**

In `build.gradle.kts`, add:

```kotlin
pluginManager.apply("java-library")
```

inside the existing `configure(subprojects.filter { it.buildFile.isFile })` block, before Kotlin plugin configuration is used.

- [ ] **Step 3: Verify Gradle still configures**

Run:

```bash
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew help
```

Expected: `BUILD SUCCESSFUL`.

## Task 2: Let `modules:async` Use an Optional Async Exception Handler

**Files:**
- Modify: `modules/async/src/main/kotlin/dev/sumin/skeleton/async/SkeletonAsyncAutoConfiguration.kt`
- Modify: `modules/async/src/test/kotlin/dev/sumin/skeleton/async/SkeletonAsyncAutoConfigurationTest.kt`

- [ ] **Step 1: Write the failing test**

Add a test that registers an `AsyncUncaughtExceptionHandler` bean, starts `SkeletonAsyncAutoConfiguration`, and asserts the default `AsyncConfigurer` returns that handler from `getAsyncUncaughtExceptionHandler()`.

- [ ] **Step 2: Run the test to verify RED**

Run:

```bash
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :modules:async:test --tests '*SkeletonAsyncAutoConfigurationTest*'
```

Expected: FAIL because the default configurer currently does not expose the supplied handler.

- [ ] **Step 3: Implement optional handler injection**

Change `skeletonAsyncConfigurer` to accept:

```kotlin
handlerProvider: ObjectProvider<AsyncUncaughtExceptionHandler>
```

and override:

```kotlin
override fun getAsyncUncaughtExceptionHandler(): AsyncUncaughtExceptionHandler? =
    handlerProvider.getIfAvailable()
```

- [ ] **Step 4: Run GREEN**

Run:

```bash
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :modules:async:test --tests '*SkeletonAsyncAutoConfigurationTest*'
```

Expected: `BUILD SUCCESSFUL`.

## Task 3: Add `modules:async-notification` Handler and Auto-Configuration

**Files:**
- Modify: `settings.gradle.kts`
- Create: `modules/async-notification/build.gradle.kts`
- Create: `modules/async-notification/src/main/kotlin/dev/sumin/skeleton/async/notification/AsyncNotificationProperties.kt`
- Create: `modules/async-notification/src/main/kotlin/dev/sumin/skeleton/async/notification/AsyncNotificationExceptionHandler.kt`
- Create: `modules/async-notification/src/main/kotlin/dev/sumin/skeleton/async/notification/AsyncNotificationAutoConfiguration.kt`
- Create: `modules/async-notification/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `modules/async-notification/src/test/kotlin/dev/sumin/skeleton/async/notification/AsyncNotificationExceptionHandlerTest.kt`
- Create: `modules/async-notification/src/test/kotlin/dev/sumin/skeleton/async/notification/AsyncNotificationAutoConfigurationTest.kt`

- [ ] **Step 1: Write failing handler tests**

Tests must assert:

- exception publishes `NotificationEvent(topic="async.exception", type="async-exception", severity=ERROR)`
- payload includes `exceptionClass`, `method`, `argumentCount`, `argumentTypes`, `traceId`, `spanId`, `runId`, `accountId`
- null/blank values are omitted
- publisher failure is swallowed and logged

- [ ] **Step 2: Run handler tests to verify RED**

Run:

```bash
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :modules:async-notification:test --tests '*AsyncNotificationExceptionHandlerTest*'
```

Expected: FAIL because module/classes do not exist.

- [ ] **Step 3: Implement module and handler**

Use properties:

```kotlin
@ConfigurationProperties("skeleton.async-notification")
data class AsyncNotificationProperties(
    val enabled: Boolean = true,
    val topic: String = "async.exception",
    val type: String = "async-exception",
    val title: String = "Async task failed",
)
```

Handler maps `Method` and `params` conservatively, never logging raw argument values.

- [ ] **Step 4: Write auto-config tests**

Tests must assert:

- enabled default contributes one `AsyncUncaughtExceptionHandler`
- disabled property contributes none
- custom handler backs off
- module exposes `AsyncTaskGroup` and `NotificationPublisher` to a consumer through `api(...)`

- [ ] **Step 5: Run module GREEN**

Run:

```bash
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :modules:async-notification:test --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`.

## Task 4: Prove App Composition Uses Only the Bundle

**Files:**
- Modify: `apps/api/build.gradle.kts`
- Modify: `apps/api/src/main/kotlin/dev/sumin/skeleton/api/SkeletonModuleController.kt`
- Modify: `apps/api/src/test/kotlin/dev/sumin/skeleton/api/SkeletonModuleCompositionIntegrationTest.kt`

- [ ] **Step 1: Write failing app integration assertion**

Update the composition test to subscribe to `async.exception`, call a workbench endpoint that triggers a failing `@Async` method, and assert a captured event has `type=async-exception` and the fixed trace id.

- [ ] **Step 2: Run app test to verify RED**

Run:

```bash
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :apps:api:test --tests '*SkeletonModuleCompositionIntegrationTest*'
```

Expected: FAIL until the endpoint/service and bundle dependency are wired.

- [ ] **Step 3: Wire app dependency and endpoint**

Change app dependency to include:

```kotlin
implementation(project(":modules:async-notification"))
```

Add a workbench endpoint such as `POST /api/v1/skeleton/async/fail` that invokes a bean method annotated with `@Async("skeletonAsyncTaskExecutor")` and throws.

- [ ] **Step 4: Run app GREEN**

Run:

```bash
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :apps:api:test --tests '*SkeletonModuleCompositionIntegrationTest*' --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`.

## Task 5: Docs, Verification, and Commit

**Files:**
- Modify: `README.md`
- Modify: `docs/async.md`
- Modify: `docs/logging.md`
- Modify: `docs/superpowers/specs/2026-06-11-modular-skeleton-roadmap.md`

- [ ] **Step 1: Update docs**

Document:

- `implementation(project(":modules:async-notification"))` is the one-line bundle dependency.
- `async` alone is executor/context only.
- `notification` alone is broker/event only.
- `async-notification` publishes uncaught `@Async` failures as notification events.

- [ ] **Step 2: Run full focused verification**

Run:

```bash
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :modules:async:test :modules:async-notification:test :apps:api:test --tests '*SkeletonModuleCompositionIntegrationTest*' --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Check diff**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors; only intended files changed.

- [ ] **Step 4: Commit**

Run:

```bash
git add build.gradle.kts settings.gradle.kts modules/async modules/async-notification apps/api README.md docs
git commit -m "feat: add async notification bridge"
```
