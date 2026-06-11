# Redis Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `modules:redis-core` as the shared Redis connection, serialization, key-prefix, and configuration foundation for later `redis-lock`, `redis-cache`, and `redis-rate-limit` modules.

**Architecture:** `redis-core` provides Redis infrastructure but does not decide whether Redis is required. It binds `skeleton.redis.*`, creates overrideable Lettuce connection/template beans, and never pings Redis during startup. Feature modules will own fail-fast/fail-open behavior, so `redis-core` can be present without Redis running.

**Tech Stack:** Kotlin, Spring Boot auto-configuration, Spring Data Redis, Lettuce, Jackson Redis serializer, JUnit 5, AssertJ, `ApplicationContextRunner`.

---

## Environment And Configuration Rules

The Redis work must follow the profile/secret rules already established for SSM:

- `SPRING_PROFILES_ACTIVE` is still the required environment selector.
- Do not require a global Redis enabled environment variable for normal usage.
- Do not commit real Redis passwords or endpoints.
- `.env.example` may list local override names, but `.env.local` remains ignored and must be loaded by shell, IDE, Docker Compose, or deployment tooling.
- `local` defaults to `localhost:6379` with no password.
- `dev`, `staging`, and `prod` read host, port, username, password, SSL, timeout, and key prefix from environment variables or SSM.
- `redis-core` must not fail startup when Redis is unavailable. `redis-lock` will perform the fail-fast startup check in a later plan.

Public Redis configuration uses `skeleton.redis.*`, not `spring.data.redis.*`.

## File Structure

- Modify: `settings.gradle.kts`
  - Include `:modules:redis-core`.
- Create: `modules/redis-core/build.gradle.kts`
  - Add Spring Data Redis, Lettuce, Boot test, AssertJ, Kotlin test dependencies.
- Create: `modules/redis-core/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  - Register `RedisCoreAutoConfiguration`.
- Create: `modules/redis-core/src/main/kotlin/dev/sumin/skeleton/redis/core/RedisCoreProperties.kt`
  - Bind `skeleton.redis.*`.
- Create: `modules/redis-core/src/main/kotlin/dev/sumin/skeleton/redis/core/RedisKeyPrefixer.kt`
  - Build stable namespaced Redis keys.
- Create: `modules/redis-core/src/main/kotlin/dev/sumin/skeleton/redis/core/RedisCoreAutoConfiguration.kt`
  - Provide conditional beans for key prefixer, serializers, Lettuce connection factory, `StringRedisTemplate`, and JSON `RedisTemplate`.
- Create: `modules/redis-core/src/test/kotlin/dev/sumin/skeleton/redis/core/RedisCorePropertiesTest.kt`
  - Verify property binding defaults and env-style overrides.
- Create: `modules/redis-core/src/test/kotlin/dev/sumin/skeleton/redis/core/RedisKeyPrefixerTest.kt`
  - Verify stable key construction.
- Create: `modules/redis-core/src/test/kotlin/dev/sumin/skeleton/redis/core/RedisCoreAutoConfigurationTest.kt`
  - Verify beans load without Redis running and user beans override defaults.
- Modify: `.env.example`
  - Add non-secret Redis local override names.
- Modify: `apps/api/src/main/resources/application-local.yml`
  - Add `skeleton.redis.*` defaults for local.
- Modify: `apps/api/src/main/resources/application-dev.yml`
  - Add `skeleton.redis.*` values backed by env/SSM.
- Modify: `apps/api/src/main/resources/application-staging.yml`
  - Add `skeleton.redis.*` values backed by env/SSM.
- Modify: `apps/api/src/main/resources/application-prod.yml`
  - Add `skeleton.redis.*` values backed by env/SSM.
- Create: `docs/config/redis.md`
  - Document local/dev/prod Redis configuration, local Redis startup expectation, and which later modules fail fast.
- Modify: `docs/configuration.md`
  - Link to Redis configuration docs.

## Task 1: Register The Redis Core Module

**Files:**
- Modify: `settings.gradle.kts`
- Create: `modules/redis-core/build.gradle.kts`
- Create: `modules/redis-core/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

- [ ] **Step 1: Write the module include**

Add this line near the other `modules:*` includes in `settings.gradle.kts`:

```kotlin
include(":modules:redis-core")
```

- [ ] **Step 2: Create the module build file**

Create `modules/redis-core/build.gradle.kts`:

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

- [ ] **Step 3: Create the auto-configuration imports file**

Create `modules/redis-core/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```text
dev.sumin.skeleton.redis.core.RedisCoreAutoConfiguration
```

- [ ] **Step 4: Run the empty module build**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/java/libexec/openjdk.jdk/Contents/Home ./gradlew :modules:redis-core:classes
```

Expected: PASS. Spring does not validate auto-configuration import class names
until an application context loads the auto-configuration, so Task 2 supplies
the first failing test.

- [ ] **Step 5: Commit the module skeleton**

```bash
git add settings.gradle.kts modules/redis-core/build.gradle.kts modules/redis-core/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
git commit -m "build: add redis core module"
```

## Task 2: Add Redis Properties

**Files:**
- Create: `modules/redis-core/src/main/kotlin/dev/sumin/skeleton/redis/core/RedisCoreProperties.kt`
- Create: `modules/redis-core/src/test/kotlin/dev/sumin/skeleton/redis/core/RedisCorePropertiesTest.kt`

- [ ] **Step 1: Write failing property binding tests**

Create `modules/redis-core/src/test/kotlin/dev/sumin/skeleton/redis/core/RedisCorePropertiesTest.kt`:

```kotlin
package dev.sumin.skeleton.redis.core

import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class RedisCorePropertiesTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RedisCoreAutoConfiguration::class.java))

    @Test
    fun `binds default redis properties`() {
        contextRunner.run { context ->
            val properties = context.getBean(RedisCoreProperties::class.java)

            assertThat(properties.mode).isEqualTo(RedisCoreProperties.Mode.STANDALONE)
            assertThat(properties.host).isEqualTo("localhost")
            assertThat(properties.port).isEqualTo(6379)
            assertThat(properties.database).isEqualTo(0)
            assertThat(properties.username).isEqualTo("")
            assertThat(properties.password).isEqualTo("")
            assertThat(properties.ssl.enabled).isFalse()
            assertThat(properties.ssl.disablePeerVerificationLocal).isTrue()
            assertThat(properties.timeout.connect).isEqualTo(Duration.ofSeconds(2))
            assertThat(properties.timeout.command).isEqualTo(Duration.ofSeconds(2))
            assertThat(properties.keyPrefix).isEqualTo("kotlin-skeleton")
        }
    }

    @Test
    fun `binds env style redis overrides`() {
        contextRunner
            .withPropertyValues(
                "skeleton.redis.mode=standalone",
                "skeleton.redis.host=redis.internal",
                "skeleton.redis.port=6380",
                "skeleton.redis.database=3",
                "skeleton.redis.username=app",
                "skeleton.redis.password=secret-from-env-or-ssm",
                "skeleton.redis.ssl.enabled=true",
                "skeleton.redis.ssl.disable-peer-verification-local=false",
                "skeleton.redis.timeout.connect=1500ms",
                "skeleton.redis.timeout.command=2500ms",
                "skeleton.redis.key-prefix=kotlin-skeleton:dev",
            )
            .run { context ->
                val properties = context.getBean(RedisCoreProperties::class.java)

                assertThat(properties.host).isEqualTo("redis.internal")
                assertThat(properties.port).isEqualTo(6380)
                assertThat(properties.database).isEqualTo(3)
                assertThat(properties.username).isEqualTo("app")
                assertThat(properties.password).isEqualTo("secret-from-env-or-ssm")
                assertThat(properties.ssl.enabled).isTrue()
                assertThat(properties.ssl.disablePeerVerificationLocal).isFalse()
                assertThat(properties.timeout.connect).isEqualTo(Duration.ofMillis(1500))
                assertThat(properties.timeout.command).isEqualTo(Duration.ofMillis(2500))
                assertThat(properties.keyPrefix).isEqualTo("kotlin-skeleton:dev")
            }
    }
}
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/java/libexec/openjdk.jdk/Contents/Home ./gradlew :modules:redis-core:test --tests '*RedisCorePropertiesTest'
```

Expected: FAIL because `RedisCoreProperties` and `RedisCoreAutoConfiguration` do not exist.

- [ ] **Step 3: Implement Redis properties**

Create `modules/redis-core/src/main/kotlin/dev/sumin/skeleton/redis/core/RedisCoreProperties.kt`:

```kotlin
package dev.sumin.skeleton.redis.core

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.redis")
data class RedisCoreProperties(
    val mode: Mode = Mode.STANDALONE,
    val host: String = "localhost",
    val port: Int = 6379,
    val database: Int = 0,
    val username: String = "",
    val password: String = "",
    val ssl: Ssl = Ssl(),
    val timeout: Timeout = Timeout(),
    val keyPrefix: String = "kotlin-skeleton",
) {
    enum class Mode {
        STANDALONE,
    }

    data class Ssl(
        val enabled: Boolean = false,
        val disablePeerVerificationLocal: Boolean = true,
    )

    data class Timeout(
        val connect: Duration = Duration.ofSeconds(2),
        val command: Duration = Duration.ofSeconds(2),
    )
}
```

- [ ] **Step 4: Implement minimal auto-configuration for property binding**

Create `modules/redis-core/src/main/kotlin/dev/sumin/skeleton/redis/core/RedisCoreAutoConfiguration.kt`:

```kotlin
package dev.sumin.skeleton.redis.core

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties

@AutoConfiguration
@EnableConfigurationProperties(RedisCoreProperties::class)
class RedisCoreAutoConfiguration
```

- [ ] **Step 5: Run tests and verify they pass**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/java/libexec/openjdk.jdk/Contents/Home ./gradlew :modules:redis-core:test --tests '*RedisCorePropertiesTest'
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts modules/redis-core
git commit -m "feat: add redis core properties"
```

## Task 3: Add Redis Key Prefixing

**Files:**
- Create: `modules/redis-core/src/main/kotlin/dev/sumin/skeleton/redis/core/RedisKeyPrefixer.kt`
- Modify: `modules/redis-core/src/main/kotlin/dev/sumin/skeleton/redis/core/RedisCoreAutoConfiguration.kt`
- Create: `modules/redis-core/src/test/kotlin/dev/sumin/skeleton/redis/core/RedisKeyPrefixerTest.kt`

- [ ] **Step 1: Write failing key prefix tests**

Create `modules/redis-core/src/test/kotlin/dev/sumin/skeleton/redis/core/RedisKeyPrefixerTest.kt`:

```kotlin
package dev.sumin.skeleton.redis.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RedisKeyPrefixerTest {
    @Test
    fun `builds namespaced redis key`() {
        val prefixer = RedisKeyPrefixer("kotlin-skeleton:local")

        assertThat(prefixer.key("lock", "order", "123")).isEqualTo("kotlin-skeleton:local:lock:order:123")
    }

    @Test
    fun `trims duplicate separators and blank parts`() {
        val prefixer = RedisKeyPrefixer("kotlin-skeleton:local:")

        assertThat(prefixer.key(":rate-limit:", "", " ip ", "127.0.0.1 "))
            .isEqualTo("kotlin-skeleton:local:rate-limit:ip:127.0.0.1")
    }

    @Test
    fun `uses raw key without prefix when prefix is blank`() {
        val prefixer = RedisKeyPrefixer("")

        assertThat(prefixer.key("cache", "sample")).isEqualTo("cache:sample")
    }
}
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/java/libexec/openjdk.jdk/Contents/Home ./gradlew :modules:redis-core:test --tests '*RedisKeyPrefixerTest'
```

Expected: FAIL because `RedisKeyPrefixer` does not exist.

- [ ] **Step 3: Implement key prefixer**

Create `modules/redis-core/src/main/kotlin/dev/sumin/skeleton/redis/core/RedisKeyPrefixer.kt`:

```kotlin
package dev.sumin.skeleton.redis.core

class RedisKeyPrefixer(
    private val keyPrefix: String,
) {
    fun key(vararg parts: String): String =
        normalize(listOf(keyPrefix) + parts.toList()).joinToString(":")

    private fun normalize(parts: List<String>): List<String> =
        parts.mapNotNull { part ->
            part.trim()
                .trim(':')
                .takeIf { it.isNotBlank() }
        }
}
```

- [ ] **Step 4: Register key prefixer bean**

Replace `RedisCoreAutoConfiguration.kt` with:

```kotlin
package dev.sumin.skeleton.redis.core

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration
@EnableConfigurationProperties(RedisCoreProperties::class)
class RedisCoreAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun redisKeyPrefixer(properties: RedisCoreProperties): RedisKeyPrefixer =
        RedisKeyPrefixer(properties.keyPrefix)
}
```

- [ ] **Step 5: Run tests and verify they pass**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/java/libexec/openjdk.jdk/Contents/Home ./gradlew :modules:redis-core:test --tests '*RedisKeyPrefixerTest' --tests '*RedisCorePropertiesTest'
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add modules/redis-core
git commit -m "feat: add redis key prefixer"
```

## Task 4: Add Redis Connection And Template Beans

**Files:**
- Modify: `modules/redis-core/src/main/kotlin/dev/sumin/skeleton/redis/core/RedisCoreAutoConfiguration.kt`
- Create: `modules/redis-core/src/test/kotlin/dev/sumin/skeleton/redis/core/RedisCoreAutoConfigurationTest.kt`

- [ ] **Step 1: Write failing auto-configuration tests**

Create `modules/redis-core/src/test/kotlin/dev/sumin/skeleton/redis/core/RedisCoreAutoConfigurationTest.kt`:

```kotlin
package dev.sumin.skeleton.redis.core

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.serializer.RedisSerializer

class RedisCoreAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RedisCoreAutoConfiguration::class.java))

    @Test
    fun `creates redis beans without connecting to redis`() {
        contextRunner
            .withPropertyValues(
                "skeleton.redis.host=127.0.0.1",
                "skeleton.redis.port=6399",
                "skeleton.redis.key-prefix=kotlin-skeleton:test",
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(RedisCoreProperties::class.java)
                assertThat(context).hasSingleBean(RedisKeyPrefixer::class.java)
                assertThat(context).hasSingleBean(RedisConnectionFactory::class.java)
                assertThat(context).hasSingleBean(StringRedisTemplate::class.java)
                assertThat(context).hasBean("jsonRedisTemplate")
            }
    }

    @Test
    fun `configures standalone lettuce connection factory`() {
        contextRunner
            .withPropertyValues(
                "skeleton.redis.host=redis.internal",
                "skeleton.redis.port=6380",
                "skeleton.redis.database=2",
            )
            .run { context ->
                val connectionFactory = context.getBean(RedisConnectionFactory::class.java)

                assertThat(connectionFactory).isInstanceOf(LettuceConnectionFactory::class.java)
                val lettuce = connectionFactory as LettuceConnectionFactory
                assertThat(lettuce.standaloneConfiguration.hostName).isEqualTo("redis.internal")
                assertThat(lettuce.standaloneConfiguration.port).isEqualTo(6380)
                assertThat(lettuce.database).isEqualTo(2)
            }
    }

    @Test
    fun `backs off when user provides connection factory and templates`() {
        val userConnectionFactory = LettuceConnectionFactory("localhost", 6379)
        val userStringTemplate = StringRedisTemplate()
        val userJsonTemplate = RedisTemplate<String, Any>()

        contextRunner
            .withBean(RedisConnectionFactory::class.java) { userConnectionFactory }
            .withBean(StringRedisTemplate::class.java) { userStringTemplate }
            .withBean("jsonRedisTemplate", RedisTemplate::class.java) { userJsonTemplate }
            .run { context ->
                assertThat(context.getBean(RedisConnectionFactory::class.java)).isSameAs(userConnectionFactory)
                assertThat(context.getBean(StringRedisTemplate::class.java)).isSameAs(userStringTemplate)
                assertThat(context.getBean("jsonRedisTemplate")).isSameAs(userJsonTemplate)
            }
    }

    @Test
    fun `creates redis serializers`() {
        contextRunner.run { context ->
            assertThat(context).hasBean("redisStringSerializer")
            assertThat(context).hasBean("redisJsonSerializer")
            assertThat(context.getBean("redisStringSerializer", RedisSerializer::class.java)).isNotNull()
            assertThat(context.getBean("redisJsonSerializer", RedisSerializer::class.java)).isNotNull()
        }
    }
}
```

- [ ] **Step 2: Run tests and verify they fail**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/java/libexec/openjdk.jdk/Contents/Home ./gradlew :modules:redis-core:test --tests '*RedisCoreAutoConfigurationTest'
```

Expected: FAIL because connection factory, templates, and serializers are not implemented.

- [ ] **Step 3: Implement Redis beans**

Replace `modules/redis-core/src/main/kotlin/dev/sumin/skeleton/redis/core/RedisCoreAutoConfiguration.kt` with:

```kotlin
package dev.sumin.skeleton.redis.core

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import io.lettuce.core.ClientOptions
import io.lettuce.core.SocketOptions
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.RedisPassword
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.RedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer

@AutoConfiguration
@EnableConfigurationProperties(RedisCoreProperties::class)
class RedisCoreAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun redisKeyPrefixer(properties: RedisCoreProperties): RedisKeyPrefixer =
        RedisKeyPrefixer(properties.keyPrefix)

    @Bean("redisStringSerializer")
    @ConditionalOnMissingBean(name = ["redisStringSerializer"])
    fun redisStringSerializer(): RedisSerializer<String> =
        StringRedisSerializer()

    @Bean("redisJsonSerializer")
    @ConditionalOnMissingBean(name = ["redisJsonSerializer"])
    fun redisJsonSerializer(): RedisSerializer<Any> =
        Jackson2JsonRedisSerializer(Any::class.java)

    @Bean
    @ConditionalOnMissingBean
    fun redisConnectionFactory(properties: RedisCoreProperties): RedisConnectionFactory {
        val standalone = RedisStandaloneConfiguration().apply {
            hostName = properties.host
            port = properties.port
            database = properties.database
            if (properties.username.isNotBlank()) {
                username = properties.username
            }
            if (properties.password.isNotBlank()) {
                password = RedisPassword.of(properties.password)
            }
        }

        val clientOptions = ClientOptions.builder()
            .socketOptions(
                SocketOptions.builder()
                    .connectTimeout(properties.timeout.connect)
                    .build(),
            )
            .build()

        val clientBuilder = LettuceClientConfiguration.builder()
            .commandTimeout(properties.timeout.command)

        clientBuilder.clientOptions(clientOptions)

        if (properties.ssl.enabled) {
            val sslBuilder = clientBuilder.useSsl()
            if (properties.ssl.disablePeerVerificationLocal) {
                sslBuilder.disablePeerVerification()
            }
        }

        val client = clientBuilder.build()

        return LettuceConnectionFactory(standalone, client)
    }

    @Bean
    @ConditionalOnMissingBean
    fun stringRedisTemplate(
        redisConnectionFactory: RedisConnectionFactory,
    ): StringRedisTemplate =
        StringRedisTemplate(redisConnectionFactory)

    @Bean("jsonRedisTemplate")
    @ConditionalOnMissingBean(name = ["jsonRedisTemplate"])
    fun jsonRedisTemplate(
        redisConnectionFactory: RedisConnectionFactory,
        @Qualifier("redisStringSerializer") stringSerializer: RedisSerializer<String>,
        @Qualifier("redisJsonSerializer") jsonSerializer: RedisSerializer<Any>,
    ): RedisTemplate<String, Any> =
        RedisTemplate<String, Any>().apply {
            connectionFactory = redisConnectionFactory
            keySerializer = stringSerializer
            hashKeySerializer = stringSerializer
            valueSerializer = jsonSerializer
            hashValueSerializer = jsonSerializer
            afterPropertiesSet()
        }
}
```

- [ ] **Step 4: Run tests**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/java/libexec/openjdk.jdk/Contents/Home ./gradlew :modules:redis-core:test
```

Expected: PASS. If Spring Data Redis 4 has changed the Jackson serializer constructor, update only `redisJsonSerializer()` and keep the tests unchanged.

- [ ] **Step 5: Commit**

```bash
git add modules/redis-core
git commit -m "feat: configure redis core beans"
```

## Task 5: Add Profile Configuration And Environment Examples

**Files:**
- Modify: `.env.example`
- Modify: `apps/api/src/main/resources/application-local.yml`
- Modify: `apps/api/src/main/resources/application-dev.yml`
- Modify: `apps/api/src/main/resources/application-staging.yml`
- Modify: `apps/api/src/main/resources/application-prod.yml`

- [ ] **Step 1: Add Redis env names to `.env.example`**

Append this block after the AWS SSM variables in `.env.example`:

```dotenv
SKELETON_REDIS_HOST=localhost
SKELETON_REDIS_PORT=6379
SKELETON_REDIS_USERNAME=
SKELETON_REDIS_PASSWORD=
SKELETON_REDIS_DATABASE=0
SKELETON_REDIS_SSL_ENABLED=false
SKELETON_REDIS_SSL_DISABLE_PEER_VERIFICATION_LOCAL=true
SKELETON_REDIS_CONNECT_TIMEOUT=2s
SKELETON_REDIS_COMMAND_TIMEOUT=2s
SKELETON_REDIS_KEY_PREFIX=kotlin-skeleton:local
```

Do not add `SKELETON_REDIS_ENABLED`.

- [ ] **Step 2: Add local profile Redis defaults**

Add this under `skeleton:` in `apps/api/src/main/resources/application-local.yml`, beside the existing `config` and `auth` blocks:

```yaml
  redis:
    mode: standalone
    host: ${SKELETON_REDIS_HOST:localhost}
    port: ${SKELETON_REDIS_PORT:6379}
    username: ${SKELETON_REDIS_USERNAME:}
    password: ${SKELETON_REDIS_PASSWORD:}
    database: ${SKELETON_REDIS_DATABASE:0}
    ssl:
      enabled: ${SKELETON_REDIS_SSL_ENABLED:false}
      disable-peer-verification-local: ${SKELETON_REDIS_SSL_DISABLE_PEER_VERIFICATION_LOCAL:true}
    timeout:
      connect: ${SKELETON_REDIS_CONNECT_TIMEOUT:2s}
      command: ${SKELETON_REDIS_COMMAND_TIMEOUT:2s}
    key-prefix: ${SKELETON_REDIS_KEY_PREFIX:kotlin-skeleton:local}
```

- [ ] **Step 3: Add dev profile Redis defaults**

Add this under `skeleton:` in `apps/api/src/main/resources/application-dev.yml`:

```yaml
  redis:
    mode: standalone
    host: ${SKELETON_REDIS_HOST:localhost}
    port: ${SKELETON_REDIS_PORT:6379}
    username: ${SKELETON_REDIS_USERNAME:}
    password: ${SKELETON_REDIS_PASSWORD:}
    database: ${SKELETON_REDIS_DATABASE:0}
    ssl:
      enabled: ${SKELETON_REDIS_SSL_ENABLED:false}
      disable-peer-verification-local: ${SKELETON_REDIS_SSL_DISABLE_PEER_VERIFICATION_LOCAL:false}
    timeout:
      connect: ${SKELETON_REDIS_CONNECT_TIMEOUT:2s}
      command: ${SKELETON_REDIS_COMMAND_TIMEOUT:2s}
    key-prefix: ${SKELETON_REDIS_KEY_PREFIX:kotlin-skeleton:dev}
```

- [ ] **Step 4: Add staging profile Redis defaults**

Add this under `skeleton:` in `apps/api/src/main/resources/application-staging.yml`:

```yaml
  redis:
    mode: standalone
    host: ${SKELETON_REDIS_HOST:localhost}
    port: ${SKELETON_REDIS_PORT:6379}
    username: ${SKELETON_REDIS_USERNAME:}
    password: ${SKELETON_REDIS_PASSWORD:}
    database: ${SKELETON_REDIS_DATABASE:0}
    ssl:
      enabled: ${SKELETON_REDIS_SSL_ENABLED:true}
      disable-peer-verification-local: ${SKELETON_REDIS_SSL_DISABLE_PEER_VERIFICATION_LOCAL:false}
    timeout:
      connect: ${SKELETON_REDIS_CONNECT_TIMEOUT:2s}
      command: ${SKELETON_REDIS_COMMAND_TIMEOUT:2s}
    key-prefix: ${SKELETON_REDIS_KEY_PREFIX:kotlin-skeleton:staging}
```

- [ ] **Step 5: Add prod profile Redis defaults**

Add this under `skeleton:` in `apps/api/src/main/resources/application-prod.yml`:

```yaml
  redis:
    mode: standalone
    host: ${SKELETON_REDIS_HOST:localhost}
    port: ${SKELETON_REDIS_PORT:6379}
    username: ${SKELETON_REDIS_USERNAME:}
    password: ${SKELETON_REDIS_PASSWORD:}
    database: ${SKELETON_REDIS_DATABASE:0}
    ssl:
      enabled: ${SKELETON_REDIS_SSL_ENABLED:true}
      disable-peer-verification-local: ${SKELETON_REDIS_SSL_DISABLE_PEER_VERIFICATION_LOCAL:false}
    timeout:
      connect: ${SKELETON_REDIS_CONNECT_TIMEOUT:2s}
      command: ${SKELETON_REDIS_COMMAND_TIMEOUT:2s}
    key-prefix: ${SKELETON_REDIS_KEY_PREFIX:kotlin-skeleton:prod}
```

- [ ] **Step 6: Verify no global Redis enabled env exists**

Run:

```bash
rg -n "SKELETON_REDIS_ENABLED=|skeleton.redis.enabled:" .env.example apps/api/src/main/resources
```

Expected: no matches.

- [ ] **Step 7: Run Redis core tests**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/java/libexec/openjdk.jdk/Contents/Home ./gradlew :modules:redis-core:test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add .env.example apps/api/src/main/resources/application-local.yml apps/api/src/main/resources/application-dev.yml apps/api/src/main/resources/application-staging.yml apps/api/src/main/resources/application-prod.yml
git commit -m "docs: add redis profile configuration skeleton"
```

## Task 6: Add Redis Configuration Documentation

**Files:**
- Create: `docs/config/redis.md`
- Modify: `docs/configuration.md`
- Modify: `docs/superpowers/specs/2026-06-11-redis-bundle-design.md`

- [ ] **Step 1: Create Redis config docs**

Create `docs/config/redis.md`:

```markdown
# Redis Configuration

Redis is a composable skeleton capability. The shared connection settings live
under `skeleton.redis.*`.

`redis-core` does not make Redis required. It only creates connection and
template beans. Feature modules decide whether Redis is required:

- `redis-lock`: Redis is required and startup fails if Redis is unavailable.
- `redis-cache`: Redis is optional by default and cache failures are fail-open.
- `redis-rate-limit`: Redis failure policy is configurable.

## Local

Local defaults target a Redis server on localhost:

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :apps:api:bootRun
```

`redis-core` alone does not connect during startup, so local can start even when
Redis is not running. Once `redis-lock` is added and enabled, local must have
Redis running too.

Use `.env.local` for local overrides:

```bash
cp .env.example .env.local
set -a
source .env.local
set +a
./gradlew :apps:api:bootRun
```

Spring Boot does not read `.env.local` by file name automatically.

## Dev, Staging, Prod

Dev, staging, and prod should receive Redis values from the runtime environment
or SSM. Do not commit Redis passwords or private endpoints.

Common variables:

```text
SKELETON_REDIS_HOST
SKELETON_REDIS_PORT
SKELETON_REDIS_USERNAME
SKELETON_REDIS_PASSWORD
SKELETON_REDIS_DATABASE
SKELETON_REDIS_SSL_ENABLED
SKELETON_REDIS_CONNECT_TIMEOUT
SKELETON_REDIS_COMMAND_TIMEOUT
SKELETON_REDIS_KEY_PREFIX
```

There is no `SKELETON_REDIS_ENABLED` switch. `skeleton.redis.*` describes a
connection. Feature modules own their own enable and failure-policy switches.
```

- [ ] **Step 2: Link Redis docs from `docs/configuration.md`**

Add `docs/config/redis.md` to the required/config documentation section:

```markdown
Related configuration docs:

- `docs/config/aws-ssm.md`
- `docs/config/redis.md`
```

If the section already exists in a different shape, add only the Redis link
without duplicating headings.

- [ ] **Step 3: Update the Redis bundle spec if it still mentions a global Redis enable flag**

Run:

```bash
rg -n "skeleton.redis.enabled:|SKELETON_REDIS_ENABLED=" docs/superpowers/specs/2026-06-11-redis-bundle-design.md docs/config/redis.md
```

Expected: no matches.

- [ ] **Step 4: Run doc checks**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 5: Commit**

```bash
git add docs/config/redis.md docs/configuration.md docs/superpowers/specs/2026-06-11-redis-bundle-design.md
git commit -m "docs: document redis configuration"
```

## Task 7: Verify Full Redis Core Integration

**Files:**
- No new files.

- [ ] **Step 1: Run focused Redis tests**

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/java/libexec/openjdk.jdk/Contents/Home ./gradlew :modules:redis-core:test
```

Expected: PASS.

- [ ] **Step 2: Run API tests to prove redis-core remains optional**

Do not add `implementation(project(":modules:redis-core"))` to `apps/api` in this task. The API app should still run without Redis modules.

Run:

```bash
JAVA_HOME=/opt/homebrew/opt/java/libexec/openjdk.jdk/Contents/Home ./gradlew :apps:api:test
```

Expected: PASS.

- [ ] **Step 3: Run global check for accidental enable switches**

Run:

```bash
rg -n "SKELETON_REDIS_ENABLED=|skeleton.redis.enabled:" .env.example apps/api/src/main/resources docs/config docs/superpowers/specs || true
```

Expected: no matches.

- [ ] **Step 4: Check git diff cleanliness**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors. `git status --short` should show only intended files if anything remains.

- [ ] **Step 5: Confirm there is no leftover cleanup commit needed**

Run:

```bash
git status --short
```

Expected: no output. If files remain, return to the task that introduced them
and either commit with that task's commit message or fix the leftover change.

## Future Plans

After this plan is complete:

1. Write and implement `redis-lock`.
2. Write and implement `redis-cache`.
3. Write and implement `redis-rate-limit`.

`redis-lock` is the first feature module that should enforce Redis startup
availability in every profile, including `local`.
