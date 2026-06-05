# Stateless Auth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a reusable stateless authentication capability to `modules/auth`, including password login, JWT bearer authentication, local/dev header login, production break-glass access, and a current-user endpoint.

**Architecture:** `modules/auth` owns the auth contract, account lookup abstraction, token service, security filters, and auth endpoints. `apps/api` composes the module and adds integration tests at the app boundary. Defaults stay code-owned; YAML only supplies environment values such as JWT secret and break-glass secret.

**Tech Stack:** Kotlin 2.2.21, Java 21, Spring Boot 4.0.5, Spring Security 7, Spring MVC, Spring Security OAuth2 JOSE/Nimbus JWT, MockMvc, Testcontainers.

---

## File Structure

Create or modify these files:

- Modify: `modules/auth/build.gradle.kts` to add Spring Security, OAuth2 JOSE, validation, and security test dependencies.
- Modify: `apps/api/build.gradle.kts` to add `spring-security-test` for integration tests.
- Modify: `apps/api/src/main/resources/application.yml` to add thin `skeleton.auth.jwt.secret` default configuration.
- Modify: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/principal/CurrentPrincipal.kt` if role helpers need small additions.
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/account/AccountIdentifier.kt`.
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/account/AuthAccount.kt`.
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/account/AuthAccountRepository.kt`.
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/account/InMemoryAuthAccountRepository.kt`.
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/config/AuthProperties.kt`.
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/config/AuthStartupValidator.kt`.
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/config/AuthAutoConfiguration.kt`.
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/jwt/JwtTokenService.kt`.
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/security/AuthErrorWriter.kt`.
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/security/AuthPrincipalAuthentication.kt`.
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/security/JwtAuthenticationFilter.kt`.
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/security/DevLoginAuthenticationFilter.kt`.
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/security/BreakGlassAuthenticationFilter.kt`.
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/api/AuthController.kt`.
- Create tests under `modules/auth/src/test/kotlin/dev/sumin/skeleton/auth/**`.
- Create integration tests under `apps/api/src/test/kotlin/dev/sumin/skeleton/auth/**`.
- Modify: `README.md`, `CHANGELOG.md`, and `CLAUDE.md` after behavior is implemented.

Do not implement OAuth/social login or payment in this plan.

---

### Task 1: Add Auth Dependencies, Properties, and Account Lookup

**Files:**
- Modify: `modules/auth/build.gradle.kts`
- Modify: `apps/api/build.gradle.kts`
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/account/AccountIdentifier.kt`
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/account/AuthAccount.kt`
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/account/AuthAccountRepository.kt`
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/account/InMemoryAuthAccountRepository.kt`
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/config/AuthProperties.kt`
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/config/AuthStartupValidator.kt`
- Test: `modules/auth/src/test/kotlin/dev/sumin/skeleton/auth/account/AccountIdentifierTest.kt`
- Test: `modules/auth/src/test/kotlin/dev/sumin/skeleton/auth/config/AuthStartupValidatorTest.kt`

- [ ] **Step 1: Add failing account identifier tests**

Create `AccountIdentifierTest.kt` with tests for:

```kotlin
assertEquals(AccountIdentifier(accountId = "acc_123"), AccountIdentifier.from(accountId = "acc_123", username = null, email = null))
assertEquals(AccountIdentifier(email = "sumin@example.com"), AccountIdentifier.from(accountId = null, username = null, email = "sumin@example.com"))
assertEquals(AccountIdentifier(username = "sumin"), AccountIdentifier.from(accountId = null, username = "sumin", email = null))
assertFailsWith<IllegalArgumentException> { AccountIdentifier.from(null, null, null) }
```

- [ ] **Step 2: Run account test and verify RED**

Run:

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :modules:auth:test --tests dev.sumin.skeleton.auth.account.AccountIdentifierTest
```

Expected: FAIL because `AccountIdentifier` does not exist.

- [ ] **Step 3: Add account model and repository**

Implement:

```kotlin
data class AccountIdentifier(val accountId: String? = null, val username: String? = null, val email: String? = null) {
    companion object {
        fun from(accountId: String?, username: String?, email: String?): AccountIdentifier {
            val normalizedAccountId = accountId?.trim()?.takeIf { it.isNotEmpty() }
            val normalizedUsername = username?.trim()?.takeIf { it.isNotEmpty() }
            val normalizedEmail = email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            require(normalizedAccountId != null || normalizedUsername != null || normalizedEmail != null) {
                "accountId, username, or email is required"
            }
            return AccountIdentifier(normalizedAccountId, normalizedUsername, normalizedEmail)
        }
    }
}
```

Add:

```kotlin
data class AuthAccount(
    val accountId: String,
    val username: String,
    val email: String,
    val passwordHash: String,
    val roles: Set<String>,
)

interface AuthAccountRepository {
    fun findBy(identifier: AccountIdentifier): AuthAccount?
}
```

Add `InMemoryAuthAccountRepository` that resolves by accountId first, then email, then username.

- [ ] **Step 4: Add Spring Security dependencies**

Update `modules/auth/build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":modules:platform"))

    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.security:spring-security-oauth2-jose")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

Update `apps/api/build.gradle.kts` test dependencies:

```kotlin
testImplementation("org.springframework.security:spring-security-test")
```

- [ ] **Step 5: Add auth properties and startup validator tests**

Create tests that assert:

- dev login enabled under `prod` throws.
- break-glass enabled with blank secret throws.
- break-glass enabled under `prod` with empty allowed account list throws.
- break-glass enabled under `prod` with secret and allowlist does not throw.

- [ ] **Step 6: Implement `AuthProperties` and `AuthStartupValidator`**

Use `@ConfigurationProperties("skeleton.auth")` with nested data classes:

```kotlin
data class Jwt(val issuer: String = "kotlin-skeleton", val secret: String = "dev-local-jwt-secret-change-me-32-bytes", val accessTokenTtl: Duration = Duration.ofMinutes(15))
data class DevLogin(val enabled: Boolean = false)
data class BreakGlass(val enabled: Boolean = false, val secret: String = "", val allowedAccountIds: List<String> = emptyList())
```

Implement `AuthStartupValidator.validate(properties: AuthProperties, activeProfiles: Set<String>)`.

- [ ] **Step 7: Verify GREEN and commit**

Run:

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :modules:auth:test --tests 'dev.sumin.skeleton.auth.account.*' --tests 'dev.sumin.skeleton.auth.config.*'
```

Commit:

```bash
git add modules/auth apps/api/build.gradle.kts
git commit -m "Add auth configuration and account lookup"
```

---

### Task 2: Add JWT Token Service

**Files:**
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/jwt/JwtTokenService.kt`
- Test: `modules/auth/src/test/kotlin/dev/sumin/skeleton/auth/jwt/JwtTokenServiceTest.kt`

- [ ] **Step 1: Add failing JWT tests**

Create tests that:

- issue a token for `CurrentPrincipal(accountId = "acc_user", username = "user", email = "user@example.com", roles = setOf("USER"))`.
- decode the token and recover the same principal fields.
- preserve roles.
- reject a malformed token.

- [ ] **Step 2: Verify RED**

Run:

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :modules:auth:test --tests dev.sumin.skeleton.auth.jwt.JwtTokenServiceTest
```

Expected: FAIL because `JwtTokenService` does not exist.

- [ ] **Step 3: Implement `JwtTokenService`**

Use Spring Security `NimbusJwtEncoder` and `JwtDecoders`/`NimbusJwtDecoder` with HS256 symmetric key. Store claims:

```text
sub = accountId
username
email
roles
iss
iat
exp
```

Expose:

```kotlin
data class IssuedToken(val accessToken: String, val expiresAt: Instant)

class JwtTokenService {
    fun issue(principal: CurrentPrincipal): IssuedToken
    fun authenticate(token: String): CurrentPrincipal?
}
```

- [ ] **Step 4: Verify GREEN and commit**

Run the JWT test and then:

```bash
git add modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/jwt modules/auth/src/test/kotlin/dev/sumin/skeleton/auth/jwt
git commit -m "Add JWT token service"
```

---

### Task 3: Add Stateless Security Chain and Current User Endpoint

**Files:**
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/config/AuthAutoConfiguration.kt`
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/security/AuthErrorWriter.kt`
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/security/AuthPrincipalAuthentication.kt`
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/security/JwtAuthenticationFilter.kt`
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/api/AuthController.kt`
- Test: `apps/api/src/test/kotlin/dev/sumin/skeleton/auth/AuthControllerIntegrationTest.kt`

- [ ] **Step 1: Add failing integration tests**

Test:

- `GET /api/v1/auth/me` without auth returns `401 ApiError` with `traceId` and `spanId`.
- `POST /api/v1/auth/login` with seed user email/password returns `accessToken`, `tokenType=Bearer`, `expiresAt`, and `principal.accountId`.
- `GET /api/v1/auth/me` with `Authorization: Bearer <accessToken>` returns the same principal.
- Existing `GET /api/v1/hello` remains public and returns 200.

Use default seed accounts:

```text
acc_user / user / user@example.com / password / USER
acc_admin / admin / admin@example.com / password / USER,ADMIN
```

- [ ] **Step 2: Verify RED**

Run:

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :apps:api:test --tests dev.sumin.skeleton.auth.AuthControllerIntegrationTest
```

Expected: FAIL because auth endpoints/security do not exist.

- [ ] **Step 3: Implement auth auto-configuration and filters**

Configure:

- `@Configuration(proxyBeanMethods = false)`
- `@EnableConfigurationProperties(AuthProperties::class)`
- beans for `PasswordEncoder`, `AuthAccountRepository`, `JwtTokenService`, `AuthStartupValidator`, `SecurityFilterChain`, `JwtAuthenticationFilter`.
- Stateless session management.
- CSRF disabled.
- Permit `/health`, `/info`, `/api/v1/hello`, `/api/v1/auth/login`, docs endpoints.
- Require authentication for all other requests.
- Use `AuthErrorWriter` to return platform `ApiError` for `401` and `403`.

- [ ] **Step 4: Implement `AuthController`**

Endpoints:

```text
POST /api/v1/auth/login
GET /api/v1/auth/me
```

Login request:

```kotlin
data class PasswordLoginRequest(val accountId: String? = null, val username: String? = null, val email: String? = null, val password: String)
```

Token response:

```kotlin
data class AuthTokenResponse(val accessToken: String, val tokenType: String = "Bearer", val expiresAt: Instant, val principal: CurrentPrincipal)
```

- [ ] **Step 5: Verify GREEN and commit**

Run:

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :apps:api:test --tests dev.sumin.skeleton.auth.AuthControllerIntegrationTest
```

Commit:

```bash
git add modules/auth apps/api/src/test/kotlin/dev/sumin/skeleton/auth
git commit -m "Add stateless JWT auth endpoints"
```

---

### Task 4: Add Local/Dev Header Login

**Files:**
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/security/DevLoginAuthenticationFilter.kt`
- Modify: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/config/AuthAutoConfiguration.kt`
- Test: `apps/api/src/test/kotlin/dev/sumin/skeleton/auth/DevLoginIntegrationTest.kt`

- [ ] **Step 1: Add failing integration tests**

Test with properties:

```kotlin
@SpringBootTest(
    properties = [
        "spring.profiles.active=local",
        "skeleton.auth.dev-login.enabled=true",
    ],
)
```

Assertions:

- `GET /api/v1/auth/me` with `X-Dev-Email: user@example.com` returns `acc_user`.
- `GET /api/v1/auth/me` with `X-Dev-Account-Id: acc_admin` returns roles including `ADMIN`.
- no arbitrary role header is honored.

- [ ] **Step 2: Verify RED**

Run the new test and expect failure because the filter does not exist.

- [ ] **Step 3: Implement `DevLoginAuthenticationFilter`**

Rules:

- Active only if `skeleton.auth.dev-login.enabled=true`.
- Only authenticates when active profile includes `local` or `dev`.
- Accepts `X-Dev-Account-Id`, `X-Dev-Username`, or `X-Dev-Email`.
- Resolves through `AuthAccountRepository`.
- Builds the same `CurrentPrincipal` and authentication as JWT.
- Logs every use with trace context naturally present in MDC.
- Does not read roles from headers.

- [ ] **Step 4: Verify GREEN and commit**

Run:

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :apps:api:test --tests dev.sumin.skeleton.auth.DevLoginIntegrationTest
```

Commit:

```bash
git add modules/auth apps/api/src/test/kotlin/dev/sumin/skeleton/auth/DevLoginIntegrationTest.kt
git commit -m "Add local dev header login"
```

---

### Task 5: Add Production Break-Glass Header Login

**Files:**
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/security/BreakGlassAuthenticationFilter.kt`
- Modify: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/config/AuthAutoConfiguration.kt`
- Test: `apps/api/src/test/kotlin/dev/sumin/skeleton/auth/BreakGlassIntegrationTest.kt`
- Test: `modules/auth/src/test/kotlin/dev/sumin/skeleton/auth/config/AuthStartupValidatorTest.kt`

- [ ] **Step 1: Add failing break-glass tests**

Integration test properties:

```kotlin
@SpringBootTest(
    properties = [
        "spring.profiles.active=prod",
        "skeleton.auth.break-glass.enabled=true",
        "skeleton.auth.break-glass.secret=test-break-glass-secret",
        "skeleton.auth.break-glass.allowed-account-ids[0]=acc_admin",
    ],
)
```

Test:

- Headers `X-Break-Glass-Secret`, `X-Break-Glass-Reason`, `X-Break-Glass-Account-Id` authenticate `acc_admin`.
- Missing reason returns `401 ApiError`.
- Wrong secret returns `401 ApiError`.
- Account not in allowlist returns `403 ApiError`.

- [ ] **Step 2: Verify RED**

Run the break-glass tests and expect failure because the filter does not exist.

- [ ] **Step 3: Implement `BreakGlassAuthenticationFilter`**

Rules:

- Disabled by default.
- Requires configured secret.
- Requires reason.
- Requires account identifier.
- Enforces `allowedAccountIds` when non-empty.
- Logs every attempt/success/failure with target account and reason.
- Uses the same principal shape as JWT.
- Never reads roles from headers.

- [ ] **Step 4: Verify GREEN and commit**

Run:

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :apps:api:test --tests dev.sumin.skeleton.auth.BreakGlassIntegrationTest
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :modules:auth:test --tests dev.sumin.skeleton.auth.config.AuthStartupValidatorTest
```

Commit:

```bash
git add modules/auth apps/api/src/test/kotlin/dev/sumin/skeleton/auth/BreakGlassIntegrationTest.kt
git commit -m "Add break-glass auth access"
```

---

### Task 6: Document Auth Usage and Verify All

**Files:**
- Modify: `README.md`
- Modify: `CHANGELOG.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update docs**

Document:

- `POST /api/v1/auth/login`.
- `GET /api/v1/auth/me`.
- Seed accounts and password are development defaults.
- Dev login headers for `local`/`dev`.
- Break-glass headers and production guardrails.
- YAML stays thin; secrets come from environment variables.

- [ ] **Step 2: Run full verification**

Run:

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew clean test
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :apps:api:bootJar
docker build -t kotlin-skeleton:auth .
git diff --check
git status --short
```

- [ ] **Step 3: Commit docs**

```bash
git add README.md CHANGELOG.md CLAUDE.md
git commit -m "Document stateless auth capability"
```

---

## Self-Review Notes

Spec coverage:

- Stateless security setup: Tasks 3, 4, and 5.
- Current principal model: existing foundation plus Tasks 1 and 3.
- Password login and JWT: Tasks 2 and 3.
- Local/dev header login: Task 4.
- Production break-glass: Task 5.
- Current user endpoint: Task 3.
- FE protected-route follow-up: intentionally excluded from this backend plan.
- OAuth/social provider: intentionally excluded until auth contracts prove stable.

Placeholder scan:

- No placeholder markers are required for execution.
- Each behavior has a failing-test step before production code.
- Each task has exact commands and commit message.
