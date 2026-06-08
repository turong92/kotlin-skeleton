# Auth Social Provider Clients Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split real OAuth provider clients into optional `auth-social-*` modules so applications can opt into Google, Kakao, and Naver independently.

**Architecture:** `modules/auth-social` remains the provider-neutral social login contract and route. Provider modules depend on `auth-social` and `platform`, contribute one `OAuthProvider` bean through Spring Boot auto-configuration, and activate only when the matching `skeleton.auth-social.providers.<provider>.enabled=true` property is set. The shared `ExternalHttpClient` gets form POST and per-call base URL support so OAuth token/profile calls do not require extra HTTP client YAML wiring.

**Tech Stack:** Kotlin 2.2.21, Java 21, Spring Boot 4.0.5 auto-configuration, Spring WebClient facade, JUnit 5, JDK `HttpServer` for provider tests.

---

## File Structure

- Modify: `settings.gradle.kts` to include `:modules:auth-social-google`, `:modules:auth-social-kakao`, and `:modules:auth-social-naver`.
- Modify: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/http/*` to add `postForm` and request-level `baseUrl`.
- Test: `modules/platform/src/test/kotlin/dev/sumin/skeleton/common/http/ExternalHttpClientTest.kt`.
- Modify: `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/config/AuthSocialProperties.kt` for token/profile URL properties.
- Create: provider module build files, auto-configuration imports, auto-configuration classes, provider clients, and JDK-server tests under each provider module.
- Modify: project docs to describe optional dependency composition.

---

### Task 1: Platform HTTP OAuth Primitives

- [ ] Write failing tests for `ExternalHttpClient.postForm` and request-level `baseUrl`.
- [ ] Run `./gradlew :modules:platform:test --tests dev.sumin.skeleton.common.http.ExternalHttpClientTest` and confirm RED.
- [ ] Implement `postForm` and `ExternalHttpRequestSpec.baseUrl`.
- [ ] Re-run the platform test and confirm GREEN.

### Task 2: Provider Module Gradle Skeleton

- [ ] Add the three provider modules to `settings.gradle.kts`.
- [ ] Create `build.gradle.kts` for `auth-social-google`, `auth-social-kakao`, and `auth-social-naver`.
- [ ] Create `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` for each module.

### Task 3: Google OAuth Provider

- [ ] Write a failing JDK-server test that verifies authorization-code token exchange, bearer profile fetch, and `OAuthUserProfile` mapping.
- [ ] Implement `GoogleOAuthProvider` and `GoogleOAuthProviderAutoConfiguration`.
- [ ] Re-run the Google provider test and confirm GREEN.

### Task 4: Kakao OAuth Provider

- [ ] Write a failing JDK-server test for Kakao token exchange, `/v2/user/me` profile fetch, and nested account/profile mapping.
- [ ] Implement `KakaoOAuthProvider` and `KakaoOAuthProviderAutoConfiguration`.
- [ ] Re-run the Kakao provider test and confirm GREEN.

### Task 5: Naver OAuth Provider

- [ ] Write a failing JDK-server test for Naver token exchange, `/v1/nid/me` profile fetch, and `response` profile mapping.
- [ ] Implement `NaverOAuthProvider` and `NaverOAuthProviderAutoConfiguration`.
- [ ] Re-run the Naver provider test and confirm GREEN.

### Task 6: Verification And Commit

- [ ] Run `git diff --check`.
- [ ] Run `./gradlew clean test`.
- [ ] Run `./gradlew :apps:api:bootJar`.
- [ ] Commit the complete provider-client slice.
