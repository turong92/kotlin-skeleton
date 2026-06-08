# Web Platform Capability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add platform defaults for inbound web policy and outbound HTTP clients while keeping the server on Spring MVC.

**Architecture:** `modules/platform` owns generic web policy and external HTTP abstractions. `modules/auth` consumes platform public endpoint policy in its default `SecurityFilterChain`. `apps/api` only contributes sample public endpoints and tests the composed behavior.

**Tech Stack:** Kotlin 2.2, Java 21, Spring Boot 4.0.5, Spring MVC, Spring Security 7, Spring WebClient/Reactor Netty, MockMvc, JDK `HttpServer` for local outbound tests.

---

### Task 1: Inbound Web Policy RED

**Files:**
- Create: `apps/api/src/test/kotlin/dev/sumin/skeleton/api/WebPolicyIntegrationTest.kt`
- Create: `apps/api/src/test/kotlin/dev/sumin/skeleton/api/WebPolicyCorsIntegrationTest.kt`
- Create: `apps/api/src/test/kotlin/dev/sumin/skeleton/api/WebPolicyRateLimitIntegrationTest.kt`

- [x] **Step 1: Write failing integration tests**

Assert:
- `GET /api/v1/hello` remains public through the public endpoint registry.
- `GET /api/v1/examples/items` remains authenticated.
- forwarded headers affect `Location` for `201 Created`.
- standard security headers are present.
- CORS preflight works when enabled.
- rate limit rejects the second request when enabled with capacity `1`.

- [x] **Step 2: Verify RED**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :apps:api:test --tests dev.sumin.skeleton.api.WebPolicyIntegrationTest --tests dev.sumin.skeleton.api.WebPolicyCorsIntegrationTest --tests dev.sumin.skeleton.api.WebPolicyRateLimitIntegrationTest
```

Expected: FAIL because web policy types and auth integration do not exist yet.

### Task 2: Inbound Web Policy GREEN

**Files:**
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/web/WebProperties.kt`
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/web/PublicEndpointPolicy.kt`
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/web/SecurityHeadersFilter.kt`
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/web/RateLimit.kt`
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/web/WebPolicyAutoConfiguration.kt`
- Modify: `modules/platform/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Modify: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/config/AuthAutoConfiguration.kt`
- Create: `apps/api/src/main/kotlin/dev/sumin/skeleton/api/SamplePublicEndpointConfiguration.kt`

- [x] **Step 1: Add platform web policy auto-configuration**

Implement:
- `WebProperties`
- `PublicEndpoint`, `PublicEndpointRegistry`, `PublicEndpointContributor`
- `ForwardedHeaderFilter` bean
- `SecurityHeadersFilter`
- CORS `CorsFilter`
- `RateLimitFilter`, `RateLimitStore`, `RateLimitKeyResolver`

- [x] **Step 2: Integrate auth permitAll**

Change default auth security chain to read `PublicEndpointRegistry` and apply method-specific public rules.

- [x] **Step 3: Verify inbound GREEN**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :apps:api:test --tests dev.sumin.skeleton.api.WebPolicyIntegrationTest --tests dev.sumin.skeleton.api.WebPolicyCorsIntegrationTest --tests dev.sumin.skeleton.api.WebPolicyRateLimitIntegrationTest
```

Expected: PASS.

### Task 3: Outbound HTTP Client RED

**Files:**
- Create: `modules/platform/src/test/kotlin/dev/sumin/skeleton/common/http/ExternalHttpClientTest.kt`

- [x] **Step 1: Write failing outbound tests**

Assert:
- `GET`, `POST`, `PUT`, `PATCH`, and `DELETE` call the expected method/path.
- per-call query parameters, headers, URI variables, body, and timeout apply.
- trace headers propagate from MDC.
- upstream error maps to `ExternalHttpStatusException`.
- timeout maps to `ExternalHttpTimeoutException`.

- [x] **Step 2: Verify RED**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :modules:platform:test --tests dev.sumin.skeleton.common.http.ExternalHttpClientTest
```

Expected: FAIL because outbound client types do not exist yet.

### Task 4: Outbound HTTP Client GREEN

**Files:**
- Modify: `modules/platform/build.gradle.kts`
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/http/OutboundHttpProperties.kt`
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/http/ExternalHttpExceptions.kt`
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/http/ExternalHttpErrorMapper.kt`
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/http/ExternalHttpRequestSpec.kt`
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/http/ExternalHttpClient.kt`
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/http/DefaultExternalHttpClient.kt`
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/http/OutboundHttpAutoConfiguration.kt`
- Modify: `modules/platform/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

- [x] **Step 1: Add WebClient runtime**

Add:

```kotlin
implementation("org.springframework.boot:spring-boot-starter-webflux")
```

- [x] **Step 2: Implement method helpers and request manipulation**

Implement async `get`, `post`, `put`, `patch`, `delete` plus blocking convenience methods.

- [x] **Step 3: Implement timeout, trace propagation, logging, and default error mapping**

Map:
- timeout → `ExternalHttpTimeoutException`
- transport failure → `ExternalHttpNetworkException`
- upstream error status → `ExternalHttpStatusException`

- [x] **Step 4: Verify outbound GREEN**

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :modules:platform:test --tests dev.sumin.skeleton.common.http.ExternalHttpClientTest
```

Expected: PASS.

### Task 5: Docs and Full Verification

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`
- Modify: `CHANGELOG.md`

- [x] **Step 1: Document web platform conventions**

Document:
- public endpoint contributors
- CORS/rate-limit scaffold
- forwarded/security headers
- outbound WebClient facade and override hooks

- [x] **Step 2: Full verification**

```bash
git diff --check
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew clean test
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :apps:api:bootJar
```

Expected: all commands exit 0.

- [x] **Step 3: Commit**

```bash
git add .
git commit -m "Add web platform capability"
```
