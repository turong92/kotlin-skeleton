# Auth Social Module Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the first `modules/auth-social` slice with provider-neutral social login contracts, fake-provider test support, account-link resolution, and a social login endpoint that returns the same JWT response shape as password login.

**Architecture:** `modules/auth` remains the provider-neutral auth core. `modules/auth-social` depends on `auth` and `platform`, owns OAuth/social contracts and the social login endpoint, and reuses `AuthAccountRepository`, `JwtTokenService`, and the shared auth token response shape. The first implementation uses a fake provider in tests only; Google/Kakao/Naver production clients are intentionally deferred.

**Tech Stack:** Kotlin 2.2.21, Java 21, Spring Boot 4.0.5 auto-configuration, Spring MVC, Spring Security 7, MockMvc, Testcontainers.

---

## File Structure

Create or modify these files:

- Modify: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/api/AuthController.kt`
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/api/AuthTokenResponseFactory.kt`
- Modify: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/config/AuthAutoConfiguration.kt`
- Test: `modules/auth/src/test/kotlin/dev/sumin/skeleton/auth/api/AuthTokenResponseFactoryTest.kt`
- Modify: `settings.gradle.kts`
- Modify: `apps/api/build.gradle.kts`
- Create: `modules/auth-social/build.gradle.kts`
- Create: `modules/auth-social/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/config/AuthSocialAutoConfiguration.kt`
- Create: `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/config/AuthSocialProperties.kt`
- Create: `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/OAuthUserProfile.kt`
- Create: `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/OAuthProvider.kt`
- Create: `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/OAuthProviderRegistry.kt`
- Create: `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/OAuthAccountLink.kt`
- Create: `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/OAuthAccountLinkRepository.kt`
- Create: `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/InMemoryOAuthAccountLinkRepository.kt`
- Create: `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/OAuthAccountProvisioningPolicy.kt`
- Create: `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/LinkedAccountOnlyOAuthAccountProvisioningPolicy.kt`
- Create: `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/OAuthLoginExceptions.kt`
- Create: `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/OAuthSocialLoginService.kt`
- Create: `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/api/OAuthSocialAuthController.kt`
- Create tests under `modules/auth-social/src/test/kotlin/dev/sumin/skeleton/auth/social/**`
- Create integration tests under `apps/api/src/test/kotlin/dev/sumin/skeleton/auth/social/**`
- Modify: `README.md`
- Modify: `CHANGELOG.md`
- Modify: `CLAUDE.md`

Do not implement production Google, Kakao, or Naver HTTP clients in this plan.

---

### Task 1: Extract Shared Auth Token Response Factory

**Files:**
- Modify: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/api/AuthController.kt`
- Create: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/api/AuthTokenResponseFactory.kt`
- Modify: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/config/AuthAutoConfiguration.kt`
- Test: `modules/auth/src/test/kotlin/dev/sumin/skeleton/auth/api/AuthTokenResponseFactoryTest.kt`

- [ ] **Step 1: Add failing factory test**

Create `modules/auth/src/test/kotlin/dev/sumin/skeleton/auth/api/AuthTokenResponseFactoryTest.kt`:

```kotlin
package dev.sumin.skeleton.auth.api

import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.config.AuthProperties
import dev.sumin.skeleton.auth.jwt.JwtTokenService
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AuthTokenResponseFactoryTest {
    private val clock = Clock.fixed(Instant.parse("2026-06-05T12:00:00Z"), ZoneOffset.UTC)
    private val jwt = JwtTokenService(
        AuthProperties.Jwt(
            issuer = "test-issuer",
            secret = "test-jwt-secret-change-me-32-bytes",
            accessTokenTtl = Duration.ofMinutes(15),
        ),
        clock,
    )

    @Test
    fun `issue creates bearer token response from auth account`() {
        val factory = AuthTokenResponseFactory(jwt)
        val account = AuthAccount(
            accountId = "acc_user",
            username = "user",
            email = "user@example.com",
            passwordHash = "hash",
            roles = setOf("USER"),
        )

        val response = factory.issue(account)

        assertNotNull(response.accessToken)
        assertEquals("Bearer", response.tokenType)
        assertEquals(Instant.parse("2026-06-05T12:15:00Z"), response.expiresAt)
        assertEquals("acc_user", response.principal.accountId)
        assertEquals("user", response.principal.username)
        assertEquals("user@example.com", response.principal.email)
        assertEquals(setOf("USER"), response.principal.roles)
    }
}
```

- [ ] **Step 2: Run factory test and verify RED**

Run:

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :modules:auth:test --tests dev.sumin.skeleton.auth.api.AuthTokenResponseFactoryTest
```

Expected: FAIL because `AuthTokenResponseFactory` does not exist.

- [ ] **Step 3: Implement `AuthTokenResponseFactory`**

Create `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/api/AuthTokenResponseFactory.kt`:

```kotlin
package dev.sumin.skeleton.auth.api

import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.jwt.JwtTokenService
import dev.sumin.skeleton.auth.principal.CurrentPrincipal

class AuthTokenResponseFactory(
    private val jwtTokenService: JwtTokenService,
) {
    fun issue(account: AuthAccount): AuthTokenResponse {
        val principal = account.toCurrentPrincipal()
        val token = jwtTokenService.issue(principal)
        return AuthTokenResponse(
            accessToken = token.accessToken,
            expiresAt = token.expiresAt,
            principal = principal,
        )
    }

    private fun AuthAccount.toCurrentPrincipal(): CurrentPrincipal =
        CurrentPrincipal(
            accountId = accountId,
            username = username,
            email = email,
            roles = roles,
        )
}
```

- [ ] **Step 4: Wire factory into auth auto-configuration**

Modify `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/config/AuthAutoConfiguration.kt`.

Add import:

```kotlin
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
```

Add bean after `jwtTokenService`:

```kotlin
    @Bean
    @ConditionalOnMissingBean
    fun authTokenResponseFactory(jwtTokenService: JwtTokenService): AuthTokenResponseFactory =
        AuthTokenResponseFactory(jwtTokenService)
```

- [ ] **Step 5: Refactor `AuthController` to use factory**

Modify `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/api/AuthController.kt`:

```kotlin
package dev.sumin.skeleton.auth.api

import dev.sumin.skeleton.auth.account.AccountIdentifier
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.principal.CurrentPrincipal
import dev.sumin.skeleton.common.ApiError
import dev.sumin.skeleton.common.TraceIdFilter
import java.time.Instant
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class PasswordLoginRequest(
    val accountId: String? = null,
    val username: String? = null,
    val email: String? = null,
    val password: String? = null,
)

data class AuthTokenResponse(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresAt: Instant,
    val principal: CurrentPrincipal,
)

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val accountRepository: AuthAccountRepository,
    private val passwordEncoder: PasswordEncoder,
    private val authTokenResponseFactory: AuthTokenResponseFactory,
) {
    @PostMapping("/login")
    fun login(@RequestBody request: PasswordLoginRequest): ResponseEntity<Any> {
        val identifier = try {
            AccountIdentifier.from(request.accountId, request.username, request.email)
        } catch (_: IllegalArgumentException) {
            return unauthorized()
        }

        val account = accountRepository.findBy(identifier)
            ?: return unauthorized()

        val password = request.password?.takeIf { it.isNotEmpty() }
            ?: return unauthorized()

        if (!passwordEncoder.matches(password, account.passwordHash)) {
            return unauthorized()
        }

        return ResponseEntity.ok(authTokenResponseFactory.issue(account))
    }

    @GetMapping("/me")
    fun me(authentication: Authentication): CurrentPrincipal =
        authentication.principal as CurrentPrincipal

    private fun unauthorized(): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
            ApiError(
                title = "Unauthorized",
                status = HttpStatus.UNAUTHORIZED.value(),
                detail = "Invalid credentials",
                traceId = MDC.get(TraceIdFilter.MDC_KEY),
                spanId = MDC.get(TraceIdFilter.MDC_SPAN_ID_KEY),
            ),
        )
}
```

- [ ] **Step 6: Verify GREEN and commit**

Run:

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :modules:auth:test --tests dev.sumin.skeleton.auth.api.AuthTokenResponseFactoryTest
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :apps:api:test --tests dev.sumin.skeleton.auth.AuthControllerIntegrationTest
```

Expected: both commands PASS.

Commit:

```bash
git add modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/api/AuthController.kt \
  modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/api/AuthTokenResponseFactory.kt \
  modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/config/AuthAutoConfiguration.kt \
  modules/auth/src/test/kotlin/dev/sumin/skeleton/auth/api/AuthTokenResponseFactoryTest.kt
git commit -m "Add shared auth token response factory"
```

---

### Task 2: Add Auth Social Module Foundation and OAuth Contracts

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `apps/api/build.gradle.kts`
- Create: `modules/auth-social/build.gradle.kts`
- Create: `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/config/AuthSocialProperties.kt`
- Create: `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/OAuthUserProfile.kt`
- Create: `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/OAuthProvider.kt`
- Create: `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/OAuthProviderRegistry.kt`
- Test: `modules/auth-social/src/test/kotlin/dev/sumin/skeleton/auth/social/oauth/OAuthProviderRegistryTest.kt`

- [ ] **Step 1: Add module build files**

Modify `settings.gradle.kts`:

```kotlin
rootProject.name = "kotlin-skeleton"

include(":apps:api")
include(":modules:platform")
include(":modules:auth")
include(":modules:auth-social")
```

Modify `apps/api/build.gradle.kts` dependencies:

```kotlin
dependencies {
    implementation(project(":modules:platform"))
    implementation(project(":modules:auth"))
    implementation(project(":modules:auth-social"))

    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")
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
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-mysql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

Create `modules/auth-social/build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":modules:platform"))
    implementation(project(":modules:auth"))

    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

- [ ] **Step 2: Add failing registry tests**

Create `modules/auth-social/src/test/kotlin/dev/sumin/skeleton/auth/social/oauth/OAuthProviderRegistryTest.kt`:

```kotlin
package dev.sumin.skeleton.auth.social.oauth

import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OAuthProviderRegistryTest {
    @Test
    fun `findEnabled returns provider only when provider exists and is enabled`() {
        val provider = FakeOAuthProvider("fake")
        val registry = OAuthProviderRegistry(
            providers = listOf(provider),
            properties = AuthSocialProperties(
                providers = mapOf("fake" to AuthSocialProperties.Provider(enabled = true)),
            ),
        )

        assertEquals(provider, registry.findEnabled("fake"))
    }

    @Test
    fun `findEnabled normalizes provider id`() {
        val provider = FakeOAuthProvider("fake")
        val registry = OAuthProviderRegistry(
            providers = listOf(provider),
            properties = AuthSocialProperties(
                providers = mapOf("fake" to AuthSocialProperties.Provider(enabled = true)),
            ),
        )

        assertEquals(provider, registry.findEnabled(" Fake "))
    }

    @Test
    fun `findEnabled returns null for disabled provider`() {
        val provider = FakeOAuthProvider("fake")
        val registry = OAuthProviderRegistry(
            providers = listOf(provider),
            properties = AuthSocialProperties(
                providers = mapOf("fake" to AuthSocialProperties.Provider(enabled = false)),
            ),
        )

        assertNull(registry.findEnabled("fake"))
    }

    @Test
    fun `findEnabled returns null for unknown provider`() {
        val registry = OAuthProviderRegistry(
            providers = emptyList(),
            properties = AuthSocialProperties(),
        )

        assertNull(registry.findEnabled("missing"))
    }

    private class FakeOAuthProvider(
        override val providerId: String,
    ) : OAuthProvider {
        override fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile =
            OAuthUserProfile(
                provider = providerId,
                providerUserId = "provider-user",
                email = "provider@example.com",
                username = "provider-user",
                displayName = "Provider User",
            )
    }
}
```

- [ ] **Step 3: Run registry test and verify RED**

Run:

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :modules:auth-social:test --tests dev.sumin.skeleton.auth.social.oauth.OAuthProviderRegistryTest
```

Expected: FAIL because `AuthSocialProperties`, `OAuthProvider`, `OAuthUserProfile`, and `OAuthProviderRegistry` do not exist.

- [ ] **Step 4: Implement OAuth contracts and registry**

Create `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/config/AuthSocialProperties.kt`:

```kotlin
package dev.sumin.skeleton.auth.social.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.auth-social")
data class AuthSocialProperties(
    val providers: Map<String, Provider> = emptyMap(),
) {
    data class Provider(
        val enabled: Boolean = false,
        val clientId: String = "",
        val clientSecret: String = "",
        val redirectUri: String? = null,
        val apiBaseUrl: String? = null,
    )

    fun isProviderEnabled(providerId: String): Boolean =
        providers[providerId.trim().lowercase()]?.enabled == true
}
```

Create `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/OAuthUserProfile.kt`:

```kotlin
package dev.sumin.skeleton.auth.social.oauth

data class OAuthUserProfile(
    val provider: String,
    val providerUserId: String,
    val email: String? = null,
    val username: String? = null,
    val displayName: String? = null,
)
```

Create `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/OAuthProvider.kt`:

```kotlin
package dev.sumin.skeleton.auth.social.oauth

interface OAuthProvider {
    val providerId: String

    fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile
}
```

Create `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/OAuthProviderRegistry.kt`:

```kotlin
package dev.sumin.skeleton.auth.social.oauth

import dev.sumin.skeleton.auth.social.config.AuthSocialProperties

class OAuthProviderRegistry(
    providers: Collection<OAuthProvider>,
    private val properties: AuthSocialProperties,
) {
    private val providersById = providers.associateBy { normalize(it.providerId) }

    fun findEnabled(providerId: String): OAuthProvider? {
        val normalized = normalize(providerId)
        return providersById[normalized]
            ?.takeIf { properties.isProviderEnabled(normalized) }
    }

    private fun normalize(value: String): String = value.trim().lowercase()
}
```

- [ ] **Step 5: Verify GREEN and commit**

Run:

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :modules:auth-social:test --tests dev.sumin.skeleton.auth.social.oauth.OAuthProviderRegistryTest
```

Expected: PASS.

Commit:

```bash
git add settings.gradle.kts apps/api/build.gradle.kts modules/auth-social
git commit -m "Add auth social module contracts"
```

---

### Task 3: Add Social Account Linking and Login Service

**Files:**
- Create: `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/OAuthAccountLink.kt`
- Create: `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/OAuthAccountLinkRepository.kt`
- Create: `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/InMemoryOAuthAccountLinkRepository.kt`
- Create: `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/OAuthAccountProvisioningPolicy.kt`
- Create: `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/LinkedAccountOnlyOAuthAccountProvisioningPolicy.kt`
- Create: `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/OAuthLoginExceptions.kt`
- Create: `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/OAuthSocialLoginService.kt`
- Test: `modules/auth-social/src/test/kotlin/dev/sumin/skeleton/auth/social/oauth/InMemoryOAuthAccountLinkRepositoryTest.kt`
- Test: `modules/auth-social/src/test/kotlin/dev/sumin/skeleton/auth/social/oauth/OAuthSocialLoginServiceTest.kt`

- [ ] **Step 1: Add failing account link repository tests**

Create `modules/auth-social/src/test/kotlin/dev/sumin/skeleton/auth/social/oauth/InMemoryOAuthAccountLinkRepositoryTest.kt`:

```kotlin
package dev.sumin.skeleton.auth.social.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InMemoryOAuthAccountLinkRepositoryTest {
    @Test
    fun `findAccountId resolves provider and provider user id`() {
        val repository = InMemoryOAuthAccountLinkRepository(
            links = listOf(OAuthAccountLink(provider = "fake", providerUserId = "fake_user", accountId = "acc_user")),
        )

        assertEquals("acc_user", repository.findAccountId("fake", "fake_user"))
    }

    @Test
    fun `findAccountId normalizes provider but not provider user id`() {
        val repository = InMemoryOAuthAccountLinkRepository(
            links = listOf(OAuthAccountLink(provider = "fake", providerUserId = "fake_user", accountId = "acc_user")),
        )

        assertEquals("acc_user", repository.findAccountId(" Fake ", "fake_user"))
        assertNull(repository.findAccountId("fake", "FAKE_USER"))
    }
}
```

- [ ] **Step 2: Add failing social login service tests**

Create `modules/auth-social/src/test/kotlin/dev/sumin/skeleton/auth/social/oauth/OAuthSocialLoginServiceTest.kt`:

```kotlin
package dev.sumin.skeleton.auth.social.oauth

import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.account.InMemoryAuthAccountRepository
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
import dev.sumin.skeleton.auth.config.AuthProperties
import dev.sumin.skeleton.auth.jwt.JwtTokenService
import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OAuthSocialLoginServiceTest {
    private val accountRepository = InMemoryAuthAccountRepository(
        listOf(
            AuthAccount(
                accountId = "acc_user",
                username = "user",
                email = "user@example.com",
                passwordHash = "hash",
                roles = setOf("USER"),
            ),
        ),
    )
    private val tokenFactory = AuthTokenResponseFactory(
        JwtTokenService(
            AuthProperties.Jwt(
                issuer = "test-issuer",
                secret = "test-jwt-secret-change-me-32-bytes",
                accessTokenTtl = Duration.ofMinutes(15),
            ),
            Clock.fixed(Instant.parse("2026-06-05T12:00:00Z"), ZoneOffset.UTC),
        ),
    )

    @Test
    fun `login returns auth token response for linked account`() {
        val service = service(
            provider = FakeOAuthProvider("fake", "valid-code", providerUserId = "fake_user"),
            links = listOf(OAuthAccountLink(provider = "fake", providerUserId = "fake_user", accountId = "acc_user")),
        )

        val response = service.login(
            providerId = "fake",
            authorizationCode = "valid-code",
            redirectUri = "http://localhost:3000/auth/callback/fake",
        )

        assertEquals("Bearer", response.tokenType)
        assertEquals("acc_user", response.principal.accountId)
        assertEquals(setOf("USER"), response.principal.roles)
    }

    @Test
    fun `login rejects unknown provider`() {
        val service = service(
            provider = FakeOAuthProvider("fake", "valid-code", providerUserId = "fake_user"),
            links = emptyList(),
        )

        assertFailsWith<OAuthProviderNotFoundException> {
            service.login("missing", "valid-code", null)
        }
    }

    @Test
    fun `login rejects unlinked provider account`() {
        val service = service(
            provider = FakeOAuthProvider("fake", "valid-code", providerUserId = "unlinked_user"),
            links = emptyList(),
        )

        assertFailsWith<OAuthAccountLinkNotFoundException> {
            service.login("fake", "valid-code", null)
        }
    }

    @Test
    fun `login rejects stale account link`() {
        val service = service(
            provider = FakeOAuthProvider("fake", "valid-code", providerUserId = "fake_user"),
            links = listOf(OAuthAccountLink(provider = "fake", providerUserId = "fake_user", accountId = "missing")),
        )

        assertFailsWith<OAuthLinkedAccountNotFoundException> {
            service.login("fake", "valid-code", null)
        }
    }

    @Test
    fun `login maps invalid authorization code to unauthorized exception`() {
        val service = service(
            provider = FakeOAuthProvider("fake", "valid-code", providerUserId = "fake_user"),
            links = listOf(OAuthAccountLink(provider = "fake", providerUserId = "fake_user", accountId = "acc_user")),
        )

        assertFailsWith<OAuthInvalidAuthorizationCodeException> {
            service.login("fake", "bad-code", null)
        }
    }

    private fun service(
        provider: OAuthProvider,
        links: List<OAuthAccountLink>,
    ): OAuthSocialLoginService {
        val registry = OAuthProviderRegistry(
            providers = listOf(provider),
            properties = AuthSocialProperties(
                providers = mapOf(provider.providerId to AuthSocialProperties.Provider(enabled = true)),
            ),
        )
        val linkRepository = InMemoryOAuthAccountLinkRepository(links)
        return OAuthSocialLoginService(
            providerRegistry = registry,
            provisioningPolicy = LinkedAccountOnlyOAuthAccountProvisioningPolicy(linkRepository),
            accountRepository = accountRepository,
            tokenResponseFactory = tokenFactory,
        )
    }

    private class FakeOAuthProvider(
        override val providerId: String,
        private val validCode: String,
        private val providerUserId: String,
    ) : OAuthProvider {
        override fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile {
            if (authorizationCode != validCode) {
                throw OAuthInvalidAuthorizationCodeException(providerId)
            }
            return OAuthUserProfile(
                provider = providerId,
                providerUserId = providerUserId,
                email = "provider@example.com",
                username = "provider-user",
                displayName = "Provider User",
            )
        }
    }
}
```

- [ ] **Step 3: Run service tests and verify RED**

Run:

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :modules:auth-social:test --tests 'dev.sumin.skeleton.auth.social.oauth.*'
```

Expected: FAIL because account-linking and login service classes do not exist.

- [ ] **Step 4: Implement account-link repository**

Create `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/OAuthAccountLink.kt`:

```kotlin
package dev.sumin.skeleton.auth.social.oauth

data class OAuthAccountLink(
    val provider: String,
    val providerUserId: String,
    val accountId: String,
)
```

Create `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/OAuthAccountLinkRepository.kt`:

```kotlin
package dev.sumin.skeleton.auth.social.oauth

interface OAuthAccountLinkRepository {
    fun findAccountId(provider: String, providerUserId: String): String?
}
```

Create `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/InMemoryOAuthAccountLinkRepository.kt`:

```kotlin
package dev.sumin.skeleton.auth.social.oauth

class InMemoryOAuthAccountLinkRepository(
    links: List<OAuthAccountLink>,
) : OAuthAccountLinkRepository {
    private val linksByProviderAndUserId = links.associateBy {
        Key(provider = it.provider.normalizeProvider(), providerUserId = it.providerUserId)
    }

    override fun findAccountId(provider: String, providerUserId: String): String? =
        linksByProviderAndUserId[Key(provider.normalizeProvider(), providerUserId)]?.accountId

    private fun String.normalizeProvider(): String = trim().lowercase()

    private data class Key(
        val provider: String,
        val providerUserId: String,
    )
}
```

- [ ] **Step 5: Implement provisioning policy, exceptions, and login service**

Create `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/OAuthAccountProvisioningPolicy.kt`:

```kotlin
package dev.sumin.skeleton.auth.social.oauth

interface OAuthAccountProvisioningPolicy {
    fun resolveOrCreateAccount(profile: OAuthUserProfile): String?
}
```

Create `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/LinkedAccountOnlyOAuthAccountProvisioningPolicy.kt`:

```kotlin
package dev.sumin.skeleton.auth.social.oauth

class LinkedAccountOnlyOAuthAccountProvisioningPolicy(
    private val linkRepository: OAuthAccountLinkRepository,
) : OAuthAccountProvisioningPolicy {
    override fun resolveOrCreateAccount(profile: OAuthUserProfile): String? =
        linkRepository.findAccountId(profile.provider, profile.providerUserId)
}
```

Create `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/OAuthLoginExceptions.kt`:

```kotlin
package dev.sumin.skeleton.auth.social.oauth

import dev.sumin.skeleton.common.ApplicationException
import org.springframework.http.HttpStatus

class OAuthProviderNotFoundException(providerId: String) : ApplicationException(
    status = HttpStatus.NOT_FOUND,
    title = "OAuth provider not found",
    message = "OAuth provider '$providerId' is not enabled or does not exist",
)

class OAuthInvalidAuthorizationCodeException(providerId: String) : ApplicationException(
    status = HttpStatus.UNAUTHORIZED,
    title = "Invalid OAuth authorization code",
    message = "Authorization code for provider '$providerId' is invalid",
)

class OAuthProviderGatewayException(providerId: String, cause: Throwable) : ApplicationException(
    status = HttpStatus.BAD_GATEWAY,
    title = "OAuth provider request failed",
    message = "OAuth provider '$providerId' request failed: ${cause.message}",
)

class OAuthAccountLinkNotFoundException(provider: String, providerUserId: String) : ApplicationException(
    status = HttpStatus.CONFLICT,
    title = "OAuth account is not linked",
    message = "OAuth account '$provider:$providerUserId' is not linked to an internal account",
)

class OAuthLinkedAccountNotFoundException(accountId: String) : ApplicationException(
    status = HttpStatus.CONFLICT,
    title = "Linked account not found",
    message = "Linked internal account '$accountId' was not found",
)
```

Create `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth/OAuthSocialLoginService.kt`:

```kotlin
package dev.sumin.skeleton.auth.social.oauth

import dev.sumin.skeleton.auth.account.AccountIdentifier
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.api.AuthTokenResponse
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
import dev.sumin.skeleton.common.ApplicationException

class OAuthSocialLoginService(
    private val providerRegistry: OAuthProviderRegistry,
    private val provisioningPolicy: OAuthAccountProvisioningPolicy,
    private val accountRepository: AuthAccountRepository,
    private val tokenResponseFactory: AuthTokenResponseFactory,
) {
    fun login(
        providerId: String,
        authorizationCode: String,
        redirectUri: String?,
    ): AuthTokenResponse {
        val provider = providerRegistry.findEnabled(providerId)
            ?: throw OAuthProviderNotFoundException(providerId)

        val profile = try {
            provider.fetchProfile(authorizationCode, redirectUri)
        } catch (ex: OAuthInvalidAuthorizationCodeException) {
            throw ex
        } catch (ex: ApplicationException) {
            throw ex
        } catch (ex: RuntimeException) {
            throw OAuthProviderGatewayException(provider.providerId, ex)
        }

        val accountId = provisioningPolicy.resolveOrCreateAccount(profile)
            ?: throw OAuthAccountLinkNotFoundException(profile.provider, profile.providerUserId)

        val account = accountRepository.findBy(AccountIdentifier(accountId = accountId))
            ?: throw OAuthLinkedAccountNotFoundException(accountId)

        return tokenResponseFactory.issue(account)
    }
}
```

- [ ] **Step 6: Verify GREEN and commit**

Run:

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :modules:auth-social:test --tests 'dev.sumin.skeleton.auth.social.oauth.*'
```

Expected: PASS.

Commit:

```bash
git add modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth \
  modules/auth-social/src/test/kotlin/dev/sumin/skeleton/auth/social/oauth
git commit -m "Add OAuth social login service"
```

---

### Task 4: Add Auth Social Auto-Configuration and HTTP Endpoint

**Files:**
- Create: `modules/auth-social/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/config/AuthSocialAutoConfiguration.kt`
- Create: `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/api/OAuthSocialAuthController.kt`
- Modify: `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/config/AuthAutoConfiguration.kt`
- Test: `apps/api/src/test/kotlin/dev/sumin/skeleton/auth/social/OAuthSocialAuthControllerIntegrationTest.kt`

- [ ] **Step 1: Add failing social login integration test**

Create `apps/api/src/test/kotlin/dev/sumin/skeleton/auth/social/OAuthSocialAuthControllerIntegrationTest.kt`:

```kotlin
package dev.sumin.skeleton.auth.social

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.TestcontainersConfiguration
import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.auth.social.oauth.OAuthUserProfile
import org.hamcrest.Matchers.hasItem
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest(
    properties = [
        "skeleton.auth-social.providers.fake.enabled=true",
    ],
)
@AutoConfigureMockMvc
@Import(
    TestcontainersConfiguration::class,
    OAuthSocialAuthControllerIntegrationTest.FakeProviderConfiguration::class,
)
class OAuthSocialAuthControllerIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `POST social login with fake provider returns bearer token and principal`() {
        mockMvc.post("/api/v1/auth/social/fake/login") {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"authorizationCode":"valid-user-code","redirectUri":"http://localhost:3000/auth/callback/fake"}"""
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.accessToken") { isNotEmpty() }
            jsonPath("$.tokenType") { value("Bearer") }
            jsonPath("$.expiresAt") { isNotEmpty() }
            jsonPath("$.principal.accountId") { value("acc_user") }
            jsonPath("$.principal.username") { value("user") }
            jsonPath("$.principal.email") { value("user@example.com") }
            jsonPath("$.principal.roles") { value(hasItem("USER")) }
        }
    }

    @Test
    fun `GET me accepts bearer token issued by social login`() {
        val loginResponse = mockMvc.post("/api/v1/auth/social/fake/login") {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"authorizationCode":"valid-user-code"}"""
        }.andExpect {
            status { isOk() }
        }.andReturn().response.contentAsString

        val accessToken = JsonPath.read<String>(loginResponse, "$.accessToken")

        mockMvc.get("/api/v1/auth/me") {
            header("Authorization", "Bearer $accessToken")
            accept = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.accountId") { value("acc_user") }
            jsonPath("$.roles") { value(hasItem("USER")) }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FakeProviderConfiguration {
        @Bean
        fun fakeOAuthProvider(): OAuthProvider =
            object : OAuthProvider {
                override val providerId: String = "fake"

                override fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile {
                    if (authorizationCode != "valid-user-code") {
                        throw IllegalArgumentException("invalid fake code")
                    }
                    return OAuthUserProfile(
                        provider = "fake",
                        providerUserId = "fake_user",
                        email = "provider@example.com",
                        username = "provider-user",
                        displayName = "Provider User",
                    )
                }
            }
    }
}
```

- [ ] **Step 2: Run integration test and verify RED**

Run:

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :apps:api:test --tests dev.sumin.skeleton.auth.social.OAuthSocialAuthControllerIntegrationTest
```

Expected: FAIL because social auto-configuration and endpoint do not exist.

- [ ] **Step 3: Register auth-social auto-configuration**

Create `modules/auth-social/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```text
dev.sumin.skeleton.auth.social.config.AuthSocialAutoConfiguration
```

Create `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/config/AuthSocialAutoConfiguration.kt`:

```kotlin
package dev.sumin.skeleton.auth.social.config

import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.api.AuthTokenResponseFactory
import dev.sumin.skeleton.auth.social.api.OAuthSocialAuthController
import dev.sumin.skeleton.auth.social.oauth.InMemoryOAuthAccountLinkRepository
import dev.sumin.skeleton.auth.social.oauth.LinkedAccountOnlyOAuthAccountProvisioningPolicy
import dev.sumin.skeleton.auth.social.oauth.OAuthAccountLink
import dev.sumin.skeleton.auth.social.oauth.OAuthAccountLinkRepository
import dev.sumin.skeleton.auth.social.oauth.OAuthAccountProvisioningPolicy
import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.auth.social.oauth.OAuthProviderRegistry
import dev.sumin.skeleton.auth.social.oauth.OAuthSocialLoginService
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration
@EnableConfigurationProperties(AuthSocialProperties::class)
class AuthSocialAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun oauthProviderRegistry(
        providers: List<OAuthProvider>,
        properties: AuthSocialProperties,
    ): OAuthProviderRegistry =
        OAuthProviderRegistry(providers, properties)

    @Bean
    @ConditionalOnMissingBean
    fun oauthAccountLinkRepository(): OAuthAccountLinkRepository =
        InMemoryOAuthAccountLinkRepository(
            links = listOf(
                OAuthAccountLink(provider = "fake", providerUserId = "fake_user", accountId = "acc_user"),
                OAuthAccountLink(provider = "fake", providerUserId = "fake_admin", accountId = "acc_admin"),
            ),
        )

    @Bean
    @ConditionalOnMissingBean
    fun oauthAccountProvisioningPolicy(
        linkRepository: OAuthAccountLinkRepository,
    ): OAuthAccountProvisioningPolicy =
        LinkedAccountOnlyOAuthAccountProvisioningPolicy(linkRepository)

    @Bean
    @ConditionalOnMissingBean
    fun oauthSocialLoginService(
        providerRegistry: OAuthProviderRegistry,
        provisioningPolicy: OAuthAccountProvisioningPolicy,
        accountRepository: AuthAccountRepository,
        tokenResponseFactory: AuthTokenResponseFactory,
    ): OAuthSocialLoginService =
        OAuthSocialLoginService(
            providerRegistry = providerRegistry,
            provisioningPolicy = provisioningPolicy,
            accountRepository = accountRepository,
            tokenResponseFactory = tokenResponseFactory,
        )

    @Bean
    @ConditionalOnMissingBean
    fun oauthSocialAuthController(
        loginService: OAuthSocialLoginService,
    ): OAuthSocialAuthController =
        OAuthSocialAuthController(loginService)
}
```

- [ ] **Step 4: Add social auth controller**

Create `modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/api/OAuthSocialAuthController.kt`:

```kotlin
package dev.sumin.skeleton.auth.social.api

import dev.sumin.skeleton.auth.api.AuthTokenResponse
import dev.sumin.skeleton.auth.social.oauth.OAuthSocialLoginService
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class OAuthSocialLoginRequest(
    val authorizationCode: String,
    val redirectUri: String? = null,
)

@RestController
@RequestMapping("/api/v1/auth/social")
class OAuthSocialAuthController(
    private val loginService: OAuthSocialLoginService,
) {
    @PostMapping("/{provider}/login")
    fun login(
        @PathVariable provider: String,
        @RequestBody request: OAuthSocialLoginRequest,
    ): AuthTokenResponse =
        loginService.login(
            providerId = provider,
            authorizationCode = request.authorizationCode,
            redirectUri = request.redirectUri,
        )
}
```

- [ ] **Step 5: Permit social login endpoint in auth security chain**

Modify `modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/config/AuthAutoConfiguration.kt`.

Add the social login path to the existing permit list:

```kotlin
                        "/api/v1/auth/social/*/login",
```

Place it near `"/api/v1/auth/login"`.

- [ ] **Step 6: Verify GREEN and commit**

Run:

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :apps:api:test --tests dev.sumin.skeleton.auth.social.OAuthSocialAuthControllerIntegrationTest
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :modules:auth-social:test
```

Expected: both commands PASS.

Commit:

```bash
git add modules/auth-social/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports \
  modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/config/AuthSocialAutoConfiguration.kt \
  modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/api/OAuthSocialAuthController.kt \
  modules/auth/src/main/kotlin/dev/sumin/skeleton/auth/config/AuthAutoConfiguration.kt \
  apps/api/src/test/kotlin/dev/sumin/skeleton/auth/social/OAuthSocialAuthControllerIntegrationTest.kt
git commit -m "Add auth social login endpoint"
```

---

### Task 5: Add Social Login Error and Disabled Provider Coverage

**Files:**
- Modify: `apps/api/src/test/kotlin/dev/sumin/skeleton/auth/social/OAuthSocialAuthControllerIntegrationTest.kt`
- Test: `apps/api/src/test/kotlin/dev/sumin/skeleton/auth/social/OAuthSocialDisabledProviderIntegrationTest.kt`

- [ ] **Step 1: Add failing error-path tests to enabled-provider integration test**

Append these tests to `OAuthSocialAuthControllerIntegrationTest`:

```kotlin
    @Test
    fun `POST social login with invalid code returns bad gateway ApiError for provider failure`() {
        mockMvc.post("/api/v1/auth/social/fake/login") {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"authorizationCode":"bad-code"}"""
        }.andExpect {
            status { isBadGateway() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.status") { value(502) }
            jsonPath("$.title") { value("OAuth provider request failed") }
            jsonPath("$.traceId") { isNotEmpty() }
            jsonPath("$.spanId") { isNotEmpty() }
        }
    }

    @Test
    fun `POST social login with unlinked provider account returns conflict ApiError`() {
        mockMvc.post("/api/v1/auth/social/fake/login") {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"authorizationCode":"valid-unlinked-code"}"""
        }.andExpect {
            status { isConflict() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.status") { value(409) }
            jsonPath("$.title") { value("OAuth account is not linked") }
            jsonPath("$.traceId") { isNotEmpty() }
            jsonPath("$.spanId") { isNotEmpty() }
        }
    }

    @Test
    fun `POST social login uses repository roles instead of provider profile roles`() {
        mockMvc.post("/api/v1/auth/social/fake/login") {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"authorizationCode":"valid-user-code"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.principal.roles") { value(org.hamcrest.Matchers.contains("USER")) }
            jsonPath("$.principal.roles") { value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("ADMIN"))) }
        }
    }
```

Update the fake provider in the same test to support the unlinked code:

```kotlin
                override fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile =
                    when (authorizationCode) {
                        "valid-user-code" -> OAuthUserProfile(
                            provider = "fake",
                            providerUserId = "fake_user",
                            email = "provider@example.com",
                            username = "provider-user",
                            displayName = "Provider User",
                        )
                        "valid-unlinked-code" -> OAuthUserProfile(
                            provider = "fake",
                            providerUserId = "unlinked_user",
                            email = "unlinked@example.com",
                            username = "unlinked-user",
                            displayName = "Unlinked User",
                        )
                        else -> throw IllegalArgumentException("invalid fake code")
                    }
```

- [ ] **Step 2: Add failing disabled-provider integration test**

Create `apps/api/src/test/kotlin/dev/sumin/skeleton/auth/social/OAuthSocialDisabledProviderIntegrationTest.kt`:

```kotlin
package dev.sumin.skeleton.auth.social

import dev.sumin.skeleton.TestcontainersConfiguration
import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.auth.social.oauth.OAuthUserProfile
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@SpringBootTest(
    properties = [
        "skeleton.auth-social.providers.fake.enabled=false",
    ],
)
@AutoConfigureMockMvc
@Import(
    TestcontainersConfiguration::class,
    OAuthSocialDisabledProviderIntegrationTest.FakeProviderConfiguration::class,
)
class OAuthSocialDisabledProviderIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `POST social login with disabled provider returns not found ApiError`() {
        mockMvc.post("/api/v1/auth/social/fake/login") {
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.APPLICATION_JSON
            content = """{"authorizationCode":"valid-user-code"}"""
        }.andExpect {
            status { isNotFound() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.status") { value(404) }
            jsonPath("$.title") { value("OAuth provider not found") }
            jsonPath("$.traceId") { isNotEmpty() }
            jsonPath("$.spanId") { isNotEmpty() }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class FakeProviderConfiguration {
        @Bean
        fun fakeOAuthProvider(): OAuthProvider =
            object : OAuthProvider {
                override val providerId: String = "fake"

                override fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile =
                    OAuthUserProfile(
                        provider = "fake",
                        providerUserId = "fake_user",
                        email = "provider@example.com",
                        username = "provider-user",
                        displayName = "Provider User",
                    )
            }
    }
}
```

- [ ] **Step 3: Run error tests and verify RED**

Run:

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :apps:api:test --tests 'dev.sumin.skeleton.auth.social.*'
```

Expected: FAIL because `valid-unlinked-code` is not handled by the fake provider yet.

- [ ] **Step 4: Make enabled-provider error tests pass**

Replace the fake provider `fetchProfile` body in `OAuthSocialAuthControllerIntegrationTest` with:

```kotlin
                override fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile =
                    when (authorizationCode) {
                        "valid-user-code" -> OAuthUserProfile(
                            provider = "fake",
                            providerUserId = "fake_user",
                            email = "provider@example.com",
                            username = "provider-user",
                            displayName = "Provider User",
                        )
                        "valid-unlinked-code" -> OAuthUserProfile(
                            provider = "fake",
                            providerUserId = "unlinked_user",
                            email = "unlinked@example.com",
                            username = "unlinked-user",
                            displayName = "Unlinked User",
                        )
                        else -> throw IllegalArgumentException("invalid fake code")
                    }
```

The service implementation from Task 3 already maps fake provider `IllegalArgumentException` to:

```kotlin
throw OAuthProviderGatewayException(provider.providerId, ex)
```

The registry implementation from Task 2 already returns `null` when `properties.isProviderEnabled(normalized)` is false, which produces `OAuthProviderNotFoundException` and `404 ApiError`.

- [ ] **Step 5: Verify GREEN and commit**

Run:

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :apps:api:test --tests 'dev.sumin.skeleton.auth.social.*'
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :modules:auth-social:test
```

Expected: both commands PASS.

Commit:

```bash
git add apps/api/src/test/kotlin/dev/sumin/skeleton/auth/social \
  modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/oauth
git commit -m "Cover auth social error paths"
```

---

### Task 6: Document Auth Social Usage and Verify All

**Files:**
- Modify: `README.md`
- Modify: `CHANGELOG.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update README**

Add this section after the existing auth capability section:

```markdown
## Auth Social Capability

`modules/auth-social` is an optional social-login extension for `modules/auth`.

- `POST /api/v1/auth/social/{provider}/login` exchanges a frontend-provided authorization code through an enabled provider.
- The response shape is the same as password login: bearer token, expiration, and `CurrentPrincipal`.
- Provider identity maps to an internal account through `OAuthAccountLinkRepository`.
- Roles always come from `AuthAccountRepository`, not provider profile data.
- Google, Kakao, and Naver live under `auth-social/providers/*` when real provider clients are added. They are not separate Gradle modules yet.

First-slice tests use a fake provider. Real provider credentials should be supplied through environment variables:

```yaml
skeleton:
  auth-social:
    providers:
      google:
        enabled: true
        client-id: ${GOOGLE_OAUTH_CLIENT_ID}
        client-secret: ${GOOGLE_OAUTH_CLIENT_SECRET}
```
```

- [ ] **Step 2: Update CHANGELOG**

Add under `[Unreleased]` → `Added`:

```markdown
- `modules/auth-social` optional social-login capability with provider-neutral OAuth contracts, account-link resolution, fake-provider integration tests, and `/api/v1/auth/social/{provider}/login`
```

- [ ] **Step 3: Update CLAUDE.md**

Add this to the module layout section:

```text
modules/auth-social/src/main/kotlin/dev/sumin/skeleton/auth/social/
├── oauth/                         # provider-neutral social-login contracts and service
├── providers/                     # google/kakao/naver provider packages when real clients are added
├── api/                           # social login endpoint
└── config/                        # Spring Boot auth-social auto-configuration
```

Add this to the auth flow section:

```markdown
- social login:
  - Optional module: `modules/auth-social`
  - Endpoint: `POST /api/v1/auth/social/{provider}/login`
  - Frontend obtains provider authorization code; backend exchanges code through enabled provider
  - `provider + providerUserId` maps to internal `accountId`
  - JWT response shape is the same as password login
  - roles are always loaded from `AuthAccountRepository`
```

- [ ] **Step 4: Run final verification**

Run:

```bash
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew clean test
JAVA_HOME=$HOME/.sdkman/candidates/java/current PATH=$HOME/.sdkman/candidates/java/current/bin:$PATH ./gradlew :apps:api:bootJar
docker build -t kotlin-skeleton:auth-social .
git diff --check
git status --short
```

Expected:

- Gradle clean test passes.
- Boot jar builds.
- Docker image builds.
- `git diff --check` prints no output.
- `git status --short` shows only documentation files before the docs commit.

- [ ] **Step 5: Commit docs**

```bash
git add README.md CHANGELOG.md CLAUDE.md
git commit -m "Document auth social capability"
```

---

## Self-Review Notes

Spec coverage:

- Coarse module structure: Task 2 adds `modules/auth-social`, not provider-specific Gradle modules.
- Auth core reuse: Task 1 creates `AuthTokenResponseFactory`; Tasks 3 and 4 reuse auth account lookup and JWT response.
- Provider-neutral contracts: Task 2.
- Account linking: Task 3.
- Fake provider test support: Tasks 4 and 5 integration tests.
- Same JWT response shape as password login: Tasks 1, 3, and 4.
- Disabled provider and no-link errors: Task 5.
- Documentation: Task 6.
- Real Google/Kakao/Naver clients: intentionally excluded.

Placeholder scan:

- No placeholder markers are required for execution.
- Every behavior has a failing-test step before production code.
- Every task has exact commands and commit messages.
