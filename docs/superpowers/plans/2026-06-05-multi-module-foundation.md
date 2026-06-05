# Multi-Module Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert `kotlin-skeleton` from a single Spring Boot app into a coarse-grained multi-module skeleton with `apps/api`, `modules/platform`, and `modules/auth`.

**Architecture:** `apps/api` is the only executable Spring Boot application. `modules/platform` owns the current shared web/error/observability code. `modules/auth` starts as a coarse capability module with minimal principal contracts and no runtime security behavior yet, so including it does not change startup or request handling.

**Tech Stack:** Kotlin 2.2.21, Java 21, Spring Boot 4.0.5, Gradle Kotlin DSL, Spring MVC, Testcontainers, Flyway, MySQL.

---

## File Structure

Create or modify these files:

- Modify: `settings.gradle.kts` to include `:apps:api`, `:modules:platform`, and `:modules:auth`.
- Modify: `build.gradle.kts` to become the root convention build file.
- Create: `apps/api/build.gradle.kts` for the executable Spring Boot app.
- Create: `modules/platform/build.gradle.kts` for shared web/error/observability code.
- Create: `modules/auth/build.gradle.kts` for auth contracts.
- Move: `src/main/kotlin/dev/sumin/skeleton/KotlinSkeletonApplication.kt` to `apps/api/src/main/kotlin/dev/sumin/skeleton/KotlinSkeletonApplication.kt`.
- Move: `src/main/kotlin/dev/sumin/skeleton/api/HelloController.kt` to `apps/api/src/main/kotlin/dev/sumin/skeleton/api/HelloController.kt`.
- Move: `src/main/resources/application.yml` to `apps/api/src/main/resources/application.yml`.
- Move: `src/main/resources/db/migration/V1__init.sql` to `apps/api/src/main/resources/db/migration/V1__init.sql`.
- Move: `src/test/kotlin/dev/sumin/skeleton/KotlinSkeletonApplicationTests.kt` to `apps/api/src/test/kotlin/dev/sumin/skeleton/KotlinSkeletonApplicationTests.kt`.
- Move: `src/test/kotlin/dev/sumin/skeleton/TestKotlinSkeletonApplication.kt` to `apps/api/src/test/kotlin/dev/sumin/skeleton/TestKotlinSkeletonApplication.kt`.
- Move: `src/test/kotlin/dev/sumin/skeleton/TestcontainersConfiguration.kt` to `apps/api/src/test/kotlin/dev/sumin/skeleton/TestcontainersConfiguration.kt`.
- Move: `src/test/kotlin/dev/sumin/skeleton/api/HelloControllerIntegrationTest.kt` to `apps/api/src/test/kotlin/dev/sumin/skeleton/api/HelloControllerIntegrationTest.kt`.
- Move: `src/main/kotlin/dev/sumin/skeleton/common/ApiError.kt` to `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/ApiError.kt`.
- Move: `src/main/kotlin/dev/sumin/skeleton/common/ApplicationException.kt` to `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/ApplicationException.kt`.
- Move: `src/main/kotlin/dev/sumin/skeleton/common/GlobalExceptionHandler.kt` to `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/GlobalExceptionHandler.kt`.
- Move: `src/main/kotlin/dev/sumin/skeleton/common/RequestLoggingFilter.kt` to `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/RequestLoggingFilter.kt`.
- Move: `src/main/kotlin/dev/sumin/skeleton/common/TraceIdFilter.kt` to `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/TraceIdFilter.kt`.
- Move: `src/test/kotlin/dev/sumin/skeleton/common/TraceIdFilterTest.kt` to `modules/platform/src/test/kotlin/dev/sumin/skeleton/common/TraceIdFilterTest.kt`.
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/principal/CurrentPrincipal.kt`.
- Create: `modules/auth/src/test/kotlin/dev/sumin/skeleton/auth/principal/CurrentPrincipalTest.kt`.
- Modify: `README.md` with the new module selection model.
- Modify: `CHANGELOG.md` with the v1.2.0 foundation note.
- Modify: `CLAUDE.md` only if it still describes the old single-app layout.

Do not touch `react-skeleton` or `my-monorepo` in this plan. Syncing the finished structure to `my-monorepo/app1` should be a separate follow-up plan after this repo is stable.

---

### Task 1: Add Multi-Project Gradle Skeleton

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`
- Create: `apps/api/build.gradle.kts`
- Create: `modules/platform/build.gradle.kts`
- Create: `modules/auth/build.gradle.kts`

- [ ] **Step 1: Verify the app module does not exist yet**

Run:

```bash
./gradlew :apps:api:test
```

Expected: FAIL with a message that project `apps` or task `:apps:api:test` cannot be found.

- [ ] **Step 2: Replace `settings.gradle.kts`**

Set `settings.gradle.kts` to:

```kotlin
rootProject.name = "kotlin-skeleton"

include(":apps:api")
include(":modules:platform")
include(":modules:auth")
```

- [ ] **Step 3: Replace root `build.gradle.kts`**

Set `build.gradle.kts` to:

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    kotlin("jvm") version "2.2.21" apply false
    kotlin("plugin.spring") version "2.2.21" apply false
    id("org.springframework.boot") version "4.0.5" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    group = "dev.sumin"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    pluginManager.apply("org.jetbrains.kotlin.jvm")
    pluginManager.apply("org.jetbrains.kotlin.plugin.spring")
    pluginManager.apply("io.spring.dependency-management")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    extensions.configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
        imports {
            mavenBom(SpringBootPlugin.BOM_COORDINATES)
        }
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        compilerOptions {
            freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
```

- [ ] **Step 4: Add `apps/api/build.gradle.kts`**

Create `apps/api/build.gradle.kts`:

```kotlin
plugins {
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":modules:platform"))
    implementation(project(":modules:auth"))

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.14")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.flywaydb:flyway-mysql")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    developmentOnly("org.springframework.boot:spring-boot-devtools")
    runtimeOnly("com.mysql:mysql-connector-j")

    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jdbc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-mysql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

- [ ] **Step 5: Add `modules/platform/build.gradle.kts`**

Create `modules/platform/build.gradle.kts`:

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

- [ ] **Step 6: Add `modules/auth/build.gradle.kts`**

Create `modules/auth/build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":modules:platform"))

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

- [ ] **Step 7: Verify Gradle discovers all modules**

Run:

```bash
./gradlew projects
```

Expected: PASS and list these projects:

```text
Project ':apps:api'
Project ':modules:auth'
Project ':modules:platform'
```

- [ ] **Step 8: Commit Gradle skeleton**

Run:

```bash
git add settings.gradle.kts build.gradle.kts apps/api/build.gradle.kts modules/platform/build.gradle.kts modules/auth/build.gradle.kts
git commit -m "Create multi-module Gradle foundation"
```

---

### Task 2: Move Platform Code Into `modules/platform`

**Files:**
- Move: `src/main/kotlin/dev/sumin/skeleton/common/*.kt` to `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/`
- Move: `src/test/kotlin/dev/sumin/skeleton/common/TraceIdFilterTest.kt` to `modules/platform/src/test/kotlin/dev/sumin/skeleton/common/TraceIdFilterTest.kt`

- [ ] **Step 1: Verify platform tests do not exist yet**

Run:

```bash
./gradlew :modules:platform:test --tests dev.sumin.skeleton.common.TraceIdFilterTest
```

Expected: FAIL because `TraceIdFilterTest` is not in the platform module yet.

- [ ] **Step 2: Create platform source directories**

Run:

```bash
mkdir -p modules/platform/src/main/kotlin/dev/sumin/skeleton/common
mkdir -p modules/platform/src/test/kotlin/dev/sumin/skeleton/common
```

- [ ] **Step 3: Move common source files**

Run:

```bash
mv src/main/kotlin/dev/sumin/skeleton/common/ApiError.kt modules/platform/src/main/kotlin/dev/sumin/skeleton/common/ApiError.kt
mv src/main/kotlin/dev/sumin/skeleton/common/ApplicationException.kt modules/platform/src/main/kotlin/dev/sumin/skeleton/common/ApplicationException.kt
mv src/main/kotlin/dev/sumin/skeleton/common/GlobalExceptionHandler.kt modules/platform/src/main/kotlin/dev/sumin/skeleton/common/GlobalExceptionHandler.kt
mv src/main/kotlin/dev/sumin/skeleton/common/RequestLoggingFilter.kt modules/platform/src/main/kotlin/dev/sumin/skeleton/common/RequestLoggingFilter.kt
mv src/main/kotlin/dev/sumin/skeleton/common/TraceIdFilter.kt modules/platform/src/main/kotlin/dev/sumin/skeleton/common/TraceIdFilter.kt
mv src/test/kotlin/dev/sumin/skeleton/common/TraceIdFilterTest.kt modules/platform/src/test/kotlin/dev/sumin/skeleton/common/TraceIdFilterTest.kt
```

Keep package declarations unchanged:

```kotlin
package dev.sumin.skeleton.common
```

- [ ] **Step 4: Remove now-empty common directories**

Run:

```bash
rmdir src/main/kotlin/dev/sumin/skeleton/common
rmdir src/test/kotlin/dev/sumin/skeleton/common
```

Expected: PASS if directories are empty.

- [ ] **Step 5: Verify platform module tests pass**

Run:

```bash
./gradlew :modules:platform:test --tests dev.sumin.skeleton.common.TraceIdFilterTest
```

Expected: PASS.

- [ ] **Step 6: Commit platform extraction**

Run:

```bash
git add src modules/platform
git commit -m "Extract platform observability and errors"
```

---

### Task 3: Move Executable App Into `apps/api`

**Files:**
- Move: `src/main/kotlin/dev/sumin/skeleton/KotlinSkeletonApplication.kt`
- Move: `src/main/kotlin/dev/sumin/skeleton/api/HelloController.kt`
- Move: `src/main/resources/application.yml`
- Move: `src/main/resources/db/migration/V1__init.sql`
- Move: app tests and testcontainers configuration under `apps/api/src/test/`

- [ ] **Step 1: Verify app tests do not pass before app files move**

Run:

```bash
./gradlew :apps:api:test
```

Expected: FAIL because the app module has no application source or tests yet.

- [ ] **Step 2: Create app directories**

Run:

```bash
mkdir -p apps/api/src/main/kotlin/dev/sumin/skeleton/api
mkdir -p apps/api/src/main/resources/db/migration
mkdir -p apps/api/src/test/kotlin/dev/sumin/skeleton/api
```

- [ ] **Step 3: Move app source and resources**

Run:

```bash
mv src/main/kotlin/dev/sumin/skeleton/KotlinSkeletonApplication.kt apps/api/src/main/kotlin/dev/sumin/skeleton/KotlinSkeletonApplication.kt
mv src/main/kotlin/dev/sumin/skeleton/api/HelloController.kt apps/api/src/main/kotlin/dev/sumin/skeleton/api/HelloController.kt
mv src/main/resources/application.yml apps/api/src/main/resources/application.yml
mv src/main/resources/db/migration/V1__init.sql apps/api/src/main/resources/db/migration/V1__init.sql
```

Keep application package unchanged:

```kotlin
package dev.sumin.skeleton
```

Keep controller package unchanged:

```kotlin
package dev.sumin.skeleton.api
```

- [ ] **Step 4: Move app tests**

Run:

```bash
mv src/test/kotlin/dev/sumin/skeleton/KotlinSkeletonApplicationTests.kt apps/api/src/test/kotlin/dev/sumin/skeleton/KotlinSkeletonApplicationTests.kt
mv src/test/kotlin/dev/sumin/skeleton/TestKotlinSkeletonApplication.kt apps/api/src/test/kotlin/dev/sumin/skeleton/TestKotlinSkeletonApplication.kt
mv src/test/kotlin/dev/sumin/skeleton/TestcontainersConfiguration.kt apps/api/src/test/kotlin/dev/sumin/skeleton/TestcontainersConfiguration.kt
mv src/test/kotlin/dev/sumin/skeleton/api/HelloControllerIntegrationTest.kt apps/api/src/test/kotlin/dev/sumin/skeleton/api/HelloControllerIntegrationTest.kt
```

- [ ] **Step 5: Remove now-empty legacy directories**

Run:

```bash
rmdir src/main/kotlin/dev/sumin/skeleton/api
rmdir src/main/kotlin/dev/sumin/skeleton
rmdir src/main/kotlin/dev/sumin
rmdir src/main/kotlin/dev
rmdir src/main/kotlin
rmdir src/main/resources/db/migration
rmdir src/main/resources/db
rmdir src/main/resources
rmdir src/main
rmdir src/test/kotlin/dev/sumin/skeleton/api
rmdir src/test/kotlin/dev/sumin/skeleton
rmdir src/test/kotlin/dev/sumin
rmdir src/test/kotlin/dev
rmdir src/test/kotlin
rmdir src/test
rmdir src
```

If any `rmdir` fails because the directory is not empty, inspect with `find src -maxdepth 4 -type f` before continuing.

- [ ] **Step 6: Verify app tests pass**

Run:

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :apps:api:test
```

Expected: PASS. The `HelloControllerIntegrationTest` should still verify:

```text
GET /api/v1/hello returns 200
traceparent response header exists
unmapped path returns ApiError with traceId/spanId
```

- [ ] **Step 7: Verify root test runs all modules**

Run:

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew test
```

Expected: PASS for `:modules:platform:test`, `:modules:auth:test`, and `:apps:api:test`.

- [ ] **Step 8: Commit app relocation**

Run:

```bash
git add -A src apps modules settings.gradle.kts build.gradle.kts
git commit -m "Move executable API app into apps module"
```

---

### Task 4: Add Minimal Auth Capability Contracts

**Files:**
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/principal/CurrentPrincipal.kt`
- Create: `modules/auth/src/test/kotlin/dev/sumin/skeleton/auth/principal/CurrentPrincipalTest.kt`

- [ ] **Step 1: Add failing auth contract test**

Create `modules/auth/src/test/kotlin/dev/sumin/skeleton/auth/principal/CurrentPrincipalTest.kt`:

```kotlin
package dev.sumin.skeleton.auth.principal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CurrentPrincipalTest {
    @Test
    fun `principal exposes account identity and roles`() {
        val principal = CurrentPrincipal(
            accountId = "acc_123",
            username = "sumin",
            email = "sumin@example.com",
            roles = setOf("USER", "ADMIN"),
        )

        assertEquals("acc_123", principal.accountId)
        assertEquals("sumin", principal.username)
        assertEquals("sumin@example.com", principal.email)
        assertTrue(principal.hasRole("USER"))
        assertTrue(principal.hasRole("ADMIN"))
        assertFalse(principal.hasRole("OWNER"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :modules:auth:test --tests dev.sumin.skeleton.auth.principal.CurrentPrincipalTest
```

Expected: FAIL with `Unresolved reference 'CurrentPrincipal'`.

- [ ] **Step 3: Add `CurrentPrincipal`**

Create `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/principal/CurrentPrincipal.kt`:

```kotlin
package dev.sumin.skeleton.auth.principal

data class CurrentPrincipal(
    val accountId: String,
    val username: String? = null,
    val email: String? = null,
    val roles: Set<String> = emptySet(),
) {
    fun hasRole(role: String): Boolean = roles.contains(role)
}
```

- [ ] **Step 4: Run auth test to verify it passes**

Run:

```bash
./gradlew :modules:auth:test --tests dev.sumin.skeleton.auth.principal.CurrentPrincipalTest
```

Expected: PASS.

- [ ] **Step 5: Commit auth contract**

Run:

```bash
git add modules/auth
git commit -m "Add auth principal contract"
```

---

### Task 5: Update Skeleton Documentation

**Files:**
- Modify: `README.md`
- Modify: `CHANGELOG.md`
- Modify: `CLAUDE.md` if it still references only the old single-app structure.

- [ ] **Step 1: Read current docs**

Run:

```bash
sed -n '1,240p' README.md
sed -n '1,260p' CHANGELOG.md
sed -n '1,260p' CLAUDE.md
```

Expected: Docs describe the current single-app skeleton or need module layout updates.

- [ ] **Step 2: Update `README.md` module section**

Add this section near the top of `README.md` after the project summary:

````markdown
## Module Layout

This skeleton uses coarse-grained Gradle modules.

```text
apps/
  api                 # executable Spring Boot app

modules/
  platform            # web, errors, trace/logging, shared infrastructure
  auth                # authentication capability contracts and future login support
```

Use modules as capability choices:

- `apps/api` composes the runnable application.
- `modules/platform` is the shared foundation for most apps.
- `modules/auth` is included when the app needs authentication.

Fine-grained details such as JWT, password login, OAuth, or dev login live as packages inside `modules/auth` unless they grow into provider-level integrations.
````

- [ ] **Step 3: Update `CHANGELOG.md`**

Add a new entry above the previous latest entry:

```markdown
## v1.2.0 - Multi-module foundation

- Converted the backend skeleton to a coarse-grained Gradle multi-module layout.
- Added `apps/api` as the executable Spring Boot application.
- Added `modules/platform` for shared web/error/observability infrastructure.
- Added `modules/auth` as the authentication capability module foundation.
- Kept the existing trace-aware `/api/v1/hello` behavior and integration tests.
```

- [ ] **Step 4: Update `CLAUDE.md` only if needed**

If `CLAUDE.md` references the old single-app `src/` layout, add this concise note:

```markdown
## Backend Module Layout

- `apps/api` is the only executable Spring Boot application.
- `modules/platform` owns shared web/error/observability code.
- `modules/auth` owns authentication contracts and future login flows.
- Keep provider/vendor integrations out of `platform`.
```

- [ ] **Step 5: Verify docs mention the new layout**

Run:

```bash
rg -n "apps/api|modules/platform|modules/auth|multi-module|Module Layout" README.md CHANGELOG.md CLAUDE.md
```

Expected: Matches in README and CHANGELOG, and in CLAUDE only if it needed updating.

- [ ] **Step 6: Commit docs**

Run:

```bash
git add README.md CHANGELOG.md CLAUDE.md
git commit -m "Document backend multi-module layout"
```

---

### Task 6: Final Verification

**Files:**
- No new files.
- Verify the full repository state.

- [ ] **Step 1: Run full test suite**

Run:

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew clean test
```

Expected: PASS.

- [ ] **Step 2: Run app bootJar**

Run:

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :apps:api:bootJar
```

Expected: PASS and create `apps/api/build/libs/api-0.0.1-SNAPSHOT.jar`.

- [ ] **Step 3: Check dependency direction**

Run:

```bash
./gradlew :apps:api:dependencies --configuration runtimeClasspath
```

Expected: Output includes:

```text
project :modules:platform
project :modules:auth
```

Run:

```bash
./gradlew :modules:platform:dependencies --configuration runtimeClasspath
```

Expected: Output does not include `project :apps:api` or `project :modules:auth`.

Run:

```bash
./gradlew :modules:auth:dependencies --configuration runtimeClasspath
```

Expected: Output includes `project :modules:platform` and does not include `project :apps:api`.

- [ ] **Step 4: Check formatting and git status**

Run:

```bash
git diff --check
git status --short
```

Expected: `git diff --check` exits 0. `git status --short` is empty.

- [ ] **Step 5: Record final commits**

Run:

```bash
git log --oneline -6
```

Expected: Shows the task commits from this plan, ending with documentation and verification-ready state.

---

## Self-Review Notes

Spec coverage:

- `apps/api` executable app: Tasks 1 and 3.
- `modules/platform`: Tasks 1 and 2.
- `modules/auth`: Tasks 1 and 4.
- Existing trace/log/error behavior preserved: Tasks 2, 3, and 6.
- README/CHANGELOG module selection rules: Task 5.
- Payment/provider routing design: intentionally excluded from this implementation plan because the spec says payment starts after auth proves the module pattern.
- Stateless JWT login and dev/break-glass auth: intentionally excluded from this implementation plan and should be covered by the next auth implementation plan.

Placeholder scan:

- No placeholder markers are required for execution.
- Each file creation step includes concrete content.
- Each verification step includes exact commands and expected results.
