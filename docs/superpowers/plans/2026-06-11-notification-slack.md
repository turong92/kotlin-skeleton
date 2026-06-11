# Notification Slack Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an optional `modules:notification-slack` module that sends standardized Slack operational alerts and supports `@SlackExceptionNotify` without copying old product-domain code.

**Architecture:** `notification-slack` depends on `platform` and `notification`. It contains Slack-specific properties, payload building, webhook sending, notification-event forwarding, and exception AOP. Product-specific fields are supplied through contributor interfaces, not hard-coded service lookups.

**Tech Stack:** Kotlin 2.2, Spring Boot 4 auto-configuration, Spring AOP, WebClient through existing `ExternalHttpClient`, Jackson Kotlin, JUnit 5, AssertJ.

---

## File Structure

- Modify: `/Users/sumin/IdeaProjects/sumin/homeserver/projects/kotlin-skeleton/settings.gradle.kts`
  - Include `:modules:notification-slack`.
- Create: `/Users/sumin/IdeaProjects/sumin/homeserver/projects/kotlin-skeleton/modules/notification-slack/build.gradle.kts`
  - Module dependencies and tests.
- Create: `/Users/sumin/IdeaProjects/sumin/homeserver/projects/kotlin-skeleton/modules/notification-slack/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  - Register auto-configuration.
- Create: `/Users/sumin/IdeaProjects/sumin/homeserver/projects/kotlin-skeleton/modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack/SlackNotificationProperties.kt`
  - Property contract.
- Create: `/Users/sumin/IdeaProjects/sumin/homeserver/projects/kotlin-skeleton/modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack/SlackAlert.kt`
  - Internal alert model and trace fields.
- Create: `/Users/sumin/IdeaProjects/sumin/homeserver/projects/kotlin-skeleton/modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack/SlackAlertContextContributor.kt`
  - Product-specific context extension point.
- Create: `/Users/sumin/IdeaProjects/sumin/homeserver/projects/kotlin-skeleton/modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack/SlackAlertMessageFactory.kt`
  - Slack block payload builder.
- Create: `/Users/sumin/IdeaProjects/sumin/homeserver/projects/kotlin-skeleton/modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack/SlackAlertSender.kt`
  - Sender interface plus webhook implementation.
- Create: `/Users/sumin/IdeaProjects/sumin/homeserver/projects/kotlin-skeleton/modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack/SlackNotificationForwarder.kt`
  - Optional bridge from `NotificationEvent` to Slack.
- Create: `/Users/sumin/IdeaProjects/sumin/homeserver/projects/kotlin-skeleton/modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack/SlackExceptionNotify.kt`
  - Annotation contract.
- Create: `/Users/sumin/IdeaProjects/sumin/homeserver/projects/kotlin-skeleton/modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack/SlackExceptionAspect.kt`
  - Sync exception AOP.
- Create: `/Users/sumin/IdeaProjects/sumin/homeserver/projects/kotlin-skeleton/modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack/NotificationSlackAutoConfiguration.kt`
  - Beans and conditions.
- Create tests under `/Users/sumin/IdeaProjects/sumin/homeserver/projects/kotlin-skeleton/modules/notification-slack/src/test/kotlin/dev/sumin/skeleton/notification/slack/`.
- Modify: `/Users/sumin/IdeaProjects/sumin/homeserver/projects/kotlin-skeleton/apps/api/build.gradle.kts`
  - Add optional module dependency for integration proof.
- Create: `/Users/sumin/IdeaProjects/sumin/homeserver/projects/kotlin-skeleton/apps/api/src/test/kotlin/dev/sumin/skeleton/api/SlackExceptionNotifyIntegrationTest.kt`
  - App-level proof that HTTP exception annotation produces a Slack alert.
- Modify: `/Users/sumin/IdeaProjects/sumin/homeserver/projects/kotlin-skeleton/docs/superpowers/specs/2026-06-11-modular-skeleton-roadmap.md`
  - Mark completed checklist items and add completion log.

---

### Task 1: Add Module Skeleton

**Files:**
- Modify: `settings.gradle.kts`
- Create: `modules/notification-slack/build.gradle.kts`
- Create: `modules/notification-slack/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

- [ ] **Step 1: Add module include**

Add to `settings.gradle.kts` after `include(":modules:notification-sse")`:

```kotlin
include(":modules:notification-slack")
```

- [ ] **Step 2: Add module build file**

Create `modules/notification-slack/build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":modules:platform"))
    implementation(project(":modules:notification"))

    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

- [ ] **Step 3: Register auto-configuration import**

Create `modules/notification-slack/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```text
dev.sumin.skeleton.notification.slack.NotificationSlackAutoConfiguration
```

- [ ] **Step 4: Run module discovery check**

Run:

```bash
./gradlew :modules:notification-slack:tasks --quiet
```

Expected: Gradle lists tasks for `:modules:notification-slack`.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts modules/notification-slack
git commit -m "chore: add notification slack module"
```

---

### Task 2: Define Slack Alert Contracts

**Files:**
- Create: `modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack/SlackNotificationProperties.kt`
- Create: `modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack/SlackAlert.kt`
- Create: `modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack/SlackAlertContextContributor.kt`
- Test: `modules/notification-slack/src/test/kotlin/dev/sumin/skeleton/notification/slack/SlackNotificationPropertiesTest.kt`

- [ ] **Step 1: Write property test**

Create `SlackNotificationPropertiesTest.kt`:

```kotlin
package dev.sumin.skeleton.notification.slack

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SlackNotificationPropertiesTest {
    @Test
    fun `missing webhook disables sending without disabling module beans`() {
        val properties = SlackNotificationProperties()

        assertThat(properties.enabled).isTrue()
        assertThat(properties.resolveWebhookUrl("operations")).isNull()
    }

    @Test
    fun `topic route overrides default webhook`() {
        val properties = SlackNotificationProperties(
            webhookUrl = "https://hooks.slack.test/default",
            routes = mapOf(
                "payment" to SlackNotificationProperties.Route(
                    webhookUrl = "https://hooks.slack.test/payment",
                ),
            ),
        )

        assertThat(properties.resolveWebhookUrl("payment"))
            .isEqualTo("https://hooks.slack.test/payment")
        assertThat(properties.resolveWebhookUrl("operations"))
            .isEqualTo("https://hooks.slack.test/default")
    }
}
```

- [ ] **Step 2: Run test and verify it fails**

Run:

```bash
./gradlew :modules:notification-slack:test --tests '*SlackNotificationPropertiesTest'
```

Expected: FAIL because `SlackNotificationProperties` does not exist.

- [ ] **Step 3: Implement properties**

Create `SlackNotificationProperties.kt`:

```kotlin
package dev.sumin.skeleton.notification.slack

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("skeleton.notification.slack")
data class SlackNotificationProperties(
    val enabled: Boolean = true,
    val webhookUrl: String = "",
    val username: String = "skeleton",
    val iconEmoji: String = ":warning:",
    val defaultTopic: String = "operations",
    val subscribeToBroker: Boolean = false,
    val subscribedTopics: Set<String> = emptySet(),
    val minimumSeverity: SlackAlertSeverity = SlackAlertSeverity.WARNING,
    val timeout: Duration = Duration.ofSeconds(3),
    val maxFieldLength: Int = 700,
    val logUrlTemplate: String = "",
    val routes: Map<String, Route> = emptyMap(),
) {
    data class Route(
        val webhookUrl: String = "",
        val minimumSeverity: SlackAlertSeverity? = null,
    )

    fun resolveWebhookUrl(topic: String): String? =
        routes[topic]?.webhookUrl
            ?.takeIf { it.isNotBlank() }
            ?: webhookUrl.takeIf { it.isNotBlank() }
}
```

- [ ] **Step 4: Implement alert model**

Create `SlackAlert.kt`:

```kotlin
package dev.sumin.skeleton.notification.slack

import java.time.Instant
import java.util.UUID

data class SlackAlert(
    val topic: String,
    val type: String,
    val id: String = UUID.randomUUID().toString(),
    val severity: SlackAlertSeverity = SlackAlertSeverity.ERROR,
    val title: String,
    val message: String? = null,
    val fields: Map<String, String?> = emptyMap(),
    val trace: SlackTraceContext = SlackTraceContext.empty(),
    val createdAt: Instant = Instant.now(),
)

enum class SlackAlertSeverity {
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
}

data class SlackTraceContext(
    val traceId: String? = null,
    val spanId: String? = null,
    val parentSpanId: String? = null,
) {
    companion object {
        fun empty(): SlackTraceContext = SlackTraceContext()
    }
}
```

- [ ] **Step 5: Implement context contributor API**

Create `SlackAlertContextContributor.kt`:

```kotlin
package dev.sumin.skeleton.notification.slack

import jakarta.servlet.http.HttpServletRequest
import java.lang.reflect.Method

data class SlackExceptionAlertContext(
    val throwable: Throwable,
    val method: Method?,
    val arguments: List<Any?>,
    val request: HttpServletRequest?,
    val trace: SlackTraceContext,
)

fun interface SlackAlertContextContributor {
    fun contribute(context: SlackExceptionAlertContext): Map<String, String?>
}
```

- [ ] **Step 6: Run test and verify it passes**

Run:

```bash
./gradlew :modules:notification-slack:test --tests '*SlackNotificationPropertiesTest'
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add modules/notification-slack
git commit -m "feat: add slack alert contracts"
```

---

### Task 3: Build Slack Payloads Safely

**Files:**
- Create: `modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack/SlackAlertMessageFactory.kt`
- Test: `modules/notification-slack/src/test/kotlin/dev/sumin/skeleton/notification/slack/SlackAlertMessageFactoryTest.kt`

- [ ] **Step 1: Write payload tests**

Create `SlackAlertMessageFactoryTest.kt`:

```kotlin
package dev.sumin.skeleton.notification.slack

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SlackAlertMessageFactoryTest {
    @Test
    fun `payload contains title severity trace and log link`() {
        val factory = SlackAlertMessageFactory(
            SlackNotificationProperties(
                username = "api",
                iconEmoji = ":rotating_light:",
                logUrlTemplate = "https://logs.test/explore?query={traceId}",
            ),
        )

        val payload = factory.createPayload(
            SlackAlert(
                topic = "operations",
                type = "exception",
                severity = SlackAlertSeverity.ERROR,
                title = "Unhandled exception",
                message = "boom",
                trace = SlackTraceContext(traceId = "abc123", spanId = "def456"),
                fields = mapOf("request" to "GET /api/v1/test"),
            ),
        )

        assertThat(payload.text).contains("Unhandled exception")
        assertThat(payload.username).isEqualTo("api")
        assertThat(payload.iconEmoji).isEqualTo(":rotating_light:")
        assertThat(payload.blocks.toString()).contains("abc123")
        assertThat(payload.blocks.toString()).contains("https://logs.test/explore?query=abc123")
    }

    @Test
    fun `payload truncates long field values`() {
        val factory = SlackAlertMessageFactory(
            SlackNotificationProperties(maxFieldLength = 10),
        )

        val payload = factory.createPayload(
            SlackAlert(
                topic = "operations",
                type = "exception",
                title = "Long field",
                fields = mapOf("body" to "12345678901234567890"),
            ),
        )

        assertThat(payload.blocks.toString()).contains("1234567890...(truncated)")
    }
}
```

- [ ] **Step 2: Run test and verify it fails**

Run:

```bash
./gradlew :modules:notification-slack:test --tests '*SlackAlertMessageFactoryTest'
```

Expected: FAIL because `SlackAlertMessageFactory` does not exist.

- [ ] **Step 3: Implement payload factory**

Create `SlackAlertMessageFactory.kt`:

```kotlin
package dev.sumin.skeleton.notification.slack

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class SlackAlertMessageFactory(
    private val properties: SlackNotificationProperties,
) {
    fun createPayload(alert: SlackAlert): SlackWebhookPayload {
        val blocks = mutableListOf<Map<String, Any>>()
        blocks += headerBlock("${emojiFor(alert.severity)} ${alert.title}")
        blocks += contextBlock(alert)
        blocks += dividerBlock()
        alert.message?.takeIf { it.isNotBlank() }?.let {
            blocks += markdownSection("*Message*\n```$it```")
        }
        alert.fields
            .filterValues { !it.isNullOrBlank() }
            .forEach { (key, value) ->
                blocks += markdownSection("*$key*\n`${truncate(value.orEmpty())}`")
            }
        logUrl(alert.trace.traceId)?.let { url ->
            blocks += markdownSection("*Logs:* <$url|Open trace logs>")
        }

        return SlackWebhookPayload(
            text = "[${alert.severity}] ${alert.title}",
            username = properties.username,
            iconEmoji = properties.iconEmoji,
            blocks = blocks,
        )
    }

    private fun contextBlock(alert: SlackAlert): Map<String, Any> {
        val timestamp = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(alert.createdAt.atOffset(ZoneOffset.UTC))
        val trace = listOfNotNull(
            alert.trace.traceId?.let { "traceId=`$it`" },
            alert.trace.spanId?.let { "spanId=`$it`" },
            alert.trace.parentSpanId?.let { "parentSpanId=`$it`" },
        ).joinToString(" ")
        val text = listOf(
            "topic=`${alert.topic}`",
            "type=`${alert.type}`",
            timestamp,
            trace,
        ).filter { it.isNotBlank() }.joinToString(" | ")
        return mapOf(
            "type" to "context",
            "elements" to listOf(mapOf("type" to "mrkdwn", "text" to text)),
        )
    }

    private fun headerBlock(text: String): Map<String, Any> =
        mapOf(
            "type" to "header",
            "text" to mapOf("type" to "plain_text", "text" to text, "emoji" to true),
        )

    private fun dividerBlock(): Map<String, Any> =
        mapOf("type" to "divider")

    private fun markdownSection(text: String): Map<String, Any> =
        mapOf("type" to "section", "text" to mapOf("type" to "mrkdwn", "text" to text))

    private fun emojiFor(severity: SlackAlertSeverity): String =
        when (severity) {
            SlackAlertSeverity.INFO -> ":information_source:"
            SlackAlertSeverity.SUCCESS -> ":white_check_mark:"
            SlackAlertSeverity.WARNING -> ":warning:"
            SlackAlertSeverity.ERROR -> ":rotating_light:"
        }

    private fun truncate(value: String): String =
        if (value.length <= properties.maxFieldLength) {
            value
        } else {
            value.take(properties.maxFieldLength) + "...(truncated)"
        }

    private fun logUrl(traceId: String?): String? {
        if (traceId.isNullOrBlank() || properties.logUrlTemplate.isBlank()) return null
        val encoded = URLEncoder.encode(traceId, StandardCharsets.UTF_8)
        return properties.logUrlTemplate.replace("{traceId}", encoded)
    }
}

data class SlackWebhookPayload(
    val text: String,
    val username: String,
    val iconEmoji: String,
    val blocks: List<Map<String, Any>>,
)
```

- [ ] **Step 4: Run tests and verify they pass**

Run:

```bash
./gradlew :modules:notification-slack:test --tests '*SlackAlertMessageFactoryTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/notification-slack
git commit -m "feat: build slack alert payloads"
```

---

### Task 4: Send Slack Alerts

**Files:**
- Create: `modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack/SlackAlertSender.kt`
- Test: `modules/notification-slack/src/test/kotlin/dev/sumin/skeleton/notification/slack/SlackAlertSenderTest.kt`

- [ ] **Step 1: Write sender tests**

Create `SlackAlertSenderTest.kt` with a fake `ExternalHttpClient`:

```kotlin
package dev.sumin.skeleton.notification.slack

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.ExternalHttpRequestSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

class SlackAlertSenderTest {
    @Test
    fun `disabled sender skips delivery`() {
        val client = RecordingExternalHttpClient()
        val sender = SlackWebhookAlertSender(
            httpClient = client,
            properties = SlackNotificationProperties(enabled = false, webhookUrl = "https://hooks.slack.test/default"),
            messageFactory = SlackAlertMessageFactory(SlackNotificationProperties(enabled = false)),
        )

        sender.send(SlackAlert(topic = "operations", type = "test", title = "skip"))

        assertThat(client.calls).isEmpty()
    }

    @Test
    fun `missing webhook skips delivery`() {
        val client = RecordingExternalHttpClient()
        val properties = SlackNotificationProperties()
        val sender = SlackWebhookAlertSender(
            httpClient = client,
            properties = properties,
            messageFactory = SlackAlertMessageFactory(properties),
        )

        sender.send(SlackAlert(topic = "operations", type = "test", title = "skip"))

        assertThat(client.calls).isEmpty()
    }

    @Test
    fun `sender posts payload to resolved webhook`() {
        val client = RecordingExternalHttpClient()
        val properties = SlackNotificationProperties(webhookUrl = "https://hooks.slack.test/default")
        val sender = SlackWebhookAlertSender(
            httpClient = client,
            properties = properties,
            messageFactory = SlackAlertMessageFactory(properties),
        )

        sender.send(SlackAlert(topic = "operations", type = "test", title = "send"))

        assertThat(client.calls).hasSize(1)
        assertThat(client.calls.single().path).isEqualTo("https://hooks.slack.test/default")
    }

    private class RecordingExternalHttpClient : ExternalHttpClient {
        val calls = mutableListOf<Call>()

        override fun <T : Any> get(
            clientName: String,
            path: String,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = error("not used")

        override fun <T : Any> post(
            clientName: String,
            path: String,
            body: Any?,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> {
            calls += Call(clientName, path, body)
            @Suppress("UNCHECKED_CAST")
            return Mono.just("ok" as T)
        }

        override fun <T : Any> postForm(
            clientName: String,
            path: String,
            form: Map<String, String>,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = error("not used")

        override fun <T : Any> put(
            clientName: String,
            path: String,
            body: Any?,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = error("not used")

        override fun <T : Any> patch(
            clientName: String,
            path: String,
            body: Any?,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = error("not used")

        override fun <T : Any> delete(
            clientName: String,
            path: String,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = error("not used")
    }

    private data class Call(
        val clientName: String,
        val path: String,
        val body: Any?,
    )
}
```

- [ ] **Step 2: Run test and verify it fails**

Run:

```bash
./gradlew :modules:notification-slack:test --tests '*SlackAlertSenderTest'
```

Expected: FAIL because `SlackWebhookAlertSender` does not exist.

- [ ] **Step 3: Implement sender**

Create `SlackAlertSender.kt`:

```kotlin
package dev.sumin.skeleton.notification.slack

import dev.sumin.skeleton.common.http.ExternalHttpClient
import org.slf4j.LoggerFactory
import java.time.Duration

fun interface SlackAlertSender {
    fun send(alert: SlackAlert)
}

class SlackWebhookAlertSender(
    private val httpClient: ExternalHttpClient,
    private val properties: SlackNotificationProperties,
    private val messageFactory: SlackAlertMessageFactory,
) : SlackAlertSender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(alert: SlackAlert) {
        if (!properties.enabled) return
        val webhookUrl = properties.resolveWebhookUrl(alert.topic) ?: return
        val payload = messageFactory.createPayload(alert)
        runCatching {
            httpClient.post(
                clientName = CLIENT_NAME,
                path = webhookUrl,
                body = payload,
                responseType = String::class.java,
            ) {
                timeout(properties.timeout.coerceAtLeast(Duration.ofMillis(100)))
                loggingTag("slack-alert:${alert.topic}:${alert.type}")
            }.subscribe(
                { log.debug("Slack alert sent: topic={}, type={}, id={}", alert.topic, alert.type, alert.id) },
                { error -> log.warn("Slack alert send failed: {}", error.message) },
            )
        }.onFailure { error ->
            log.warn("Slack alert send setup failed: {}", error.message)
        }
    }

    companion object {
        const val CLIENT_NAME = "slack"
    }
}
```

- [ ] **Step 4: Run sender tests**

Run:

```bash
./gradlew :modules:notification-slack:test --tests '*SlackAlertSenderTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/notification-slack
git commit -m "feat: send slack alert webhooks"
```

---

### Task 5: Forward Notification Events To Slack

**Files:**
- Create: `modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack/SlackNotificationForwarder.kt`
- Test: `modules/notification-slack/src/test/kotlin/dev/sumin/skeleton/notification/slack/SlackNotificationForwarderTest.kt`

- [ ] **Step 1: Write forwarder test**

Create `SlackNotificationForwarderTest.kt`:

```kotlin
package dev.sumin.skeleton.notification.slack

import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationSeverity
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SlackNotificationForwarderTest {
    @Test
    fun `forwards event at or above minimum severity`() {
        val sender = RecordingSlackAlertSender()
        val forwarder = SlackNotificationForwarder(
            sender = sender,
            properties = SlackNotificationProperties(minimumSeverity = SlackAlertSeverity.WARNING),
        )

        forwarder.forward(
            NotificationEvent(
                topic = "operations",
                type = "batch_failed",
                severity = NotificationSeverity.ERROR,
                title = "Batch failed",
                message = "sync failed",
            ),
        )

        assertThat(sender.alerts).hasSize(1)
        assertThat(sender.alerts.single().topic).isEqualTo("operations")
        assertThat(sender.alerts.single().severity).isEqualTo(SlackAlertSeverity.ERROR)
    }

    @Test
    fun `skips event below minimum severity`() {
        val sender = RecordingSlackAlertSender()
        val forwarder = SlackNotificationForwarder(
            sender = sender,
            properties = SlackNotificationProperties(minimumSeverity = SlackAlertSeverity.ERROR),
        )

        forwarder.forward(
            NotificationEvent(
                topic = "operations",
                type = "info",
                severity = NotificationSeverity.INFO,
                title = "FYI",
            ),
        )

        assertThat(sender.alerts).isEmpty()
    }

    private class RecordingSlackAlertSender : SlackAlertSender {
        val alerts = mutableListOf<SlackAlert>()
        override fun send(alert: SlackAlert) {
            alerts += alert
        }
    }
}
```

- [ ] **Step 2: Run test and verify it fails**

Run:

```bash
./gradlew :modules:notification-slack:test --tests '*SlackNotificationForwarderTest'
```

Expected: FAIL because `SlackNotificationForwarder` does not exist.

- [ ] **Step 3: Implement forwarder**

Create `SlackNotificationForwarder.kt`:

```kotlin
package dev.sumin.skeleton.notification.slack

import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationSeverity
import dev.sumin.skeleton.notification.NotificationSubscription
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
import org.springframework.context.SmartLifecycle

class SlackNotificationForwarder(
    private val sender: SlackAlertSender,
    private val properties: SlackNotificationProperties,
    private val subscriptionRegistry: NotificationSubscriptionRegistry? = null,
) : SmartLifecycle {
    private var running = false
    private var subscription: NotificationSubscription? = null

    fun forward(event: NotificationEvent) {
        val severity = event.severity.toSlackSeverity()
        val routeMinimum = properties.routes[event.topic]?.minimumSeverity
        val minimum = routeMinimum ?: properties.minimumSeverity
        if (severity.ordinal < minimum.ordinal) return
        sender.send(
            SlackAlert(
                topic = event.topic,
                type = event.type,
                id = event.id,
                severity = severity,
                title = event.title ?: event.type,
                message = event.message,
                fields = event.payload.mapValues { it.value?.toString() },
                createdAt = event.createdAt,
            ),
        )
    }

    override fun start() {
        if (running || !properties.subscribeToBroker || subscriptionRegistry == null) return
        subscription = subscriptionRegistry.subscribe(properties.subscribedTopics) { event ->
            forward(event)
        }
        running = true
    }

    override fun stop() {
        subscription?.close()
        subscription = null
        running = false
    }

    override fun isRunning(): Boolean = running
}

private fun NotificationSeverity.toSlackSeverity(): SlackAlertSeverity =
    when (this) {
        NotificationSeverity.INFO -> SlackAlertSeverity.INFO
        NotificationSeverity.SUCCESS -> SlackAlertSeverity.SUCCESS
        NotificationSeverity.WARNING -> SlackAlertSeverity.WARNING
        NotificationSeverity.ERROR -> SlackAlertSeverity.ERROR
    }
```

- [ ] **Step 4: Run test and verify it passes**

Run:

```bash
./gradlew :modules:notification-slack:test --tests '*SlackNotificationForwarderTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/notification-slack
git commit -m "feat: forward notifications to slack"
```

---

### Task 6: Add Exception Annotation And Aspect

**Files:**
- Create: `modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack/SlackExceptionNotify.kt`
- Create: `modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack/SlackExceptionAspect.kt`
- Test: `modules/notification-slack/src/test/kotlin/dev/sumin/skeleton/notification/slack/SlackExceptionAspectTest.kt`

- [ ] **Step 1: Write aspect tests**

Create `SlackExceptionAspectTest.kt`:

```kotlin
package dev.sumin.skeleton.notification.slack

import org.assertj.core.api.Assertions.assertThat
import org.aspectj.lang.JoinPoint
import org.aspectj.lang.reflect.MethodSignature
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever
import java.lang.reflect.Method

class SlackExceptionAspectTest {
    private val sender = RecordingSlackAlertSender()
    private val aspect = SlackExceptionAspect(
        sender = sender,
        contributors = emptyList(),
        properties = SlackNotificationProperties(defaultTopic = "operations"),
    )

    @Test
    fun `sends alert for matching exception`() {
        val method = TestTarget::explode.javaMethod()
        val joinPoint = joinPoint(method)
        val annotation = method.getAnnotation(SlackExceptionNotify::class.java)

        aspect.afterThrowing(joinPoint, annotation, IllegalStateException("boom"))

        assertThat(sender.alerts).hasSize(1)
        assertThat(sender.alerts.single().topic).isEqualTo("operations")
        assertThat(sender.alerts.single().fields["exception"]).isEqualTo("IllegalStateException")
    }

    @Test
    fun `exclude prevents alert`() {
        val method = TestTarget::excluded.javaMethod()
        val joinPoint = joinPoint(method)
        val annotation = method.getAnnotation(SlackExceptionNotify::class.java)

        aspect.afterThrowing(joinPoint, annotation, IllegalArgumentException("skip"))

        assertThat(sender.alerts).isEmpty()
    }

    private fun joinPoint(method: Method): JoinPoint {
        val signature = mock(MethodSignature::class.java)
        whenever(signature.method).thenReturn(method)
        whenever(signature.toShortString()).thenReturn("TestTarget.${method.name}(..)")
        val joinPoint = mock(JoinPoint::class.java)
        whenever(joinPoint.signature).thenReturn(signature)
        whenever(joinPoint.args).thenReturn(emptyArray())
        return joinPoint
    }

    private class RecordingSlackAlertSender : SlackAlertSender {
        val alerts = mutableListOf<SlackAlert>()
        override fun send(alert: SlackAlert) {
            alerts += alert
        }
    }

    private class TestTarget {
        @SlackExceptionNotify
        fun explode() = Unit

        @SlackExceptionNotify(exclude = [IllegalArgumentException::class])
        fun excluded() = Unit
    }
}

private fun kotlin.reflect.KFunction<*>.javaMethod(): Method =
    requireNotNull(javaMethod) { "method not found" }
```

Add imports for Kotlin reflection:

```kotlin
import kotlin.reflect.jvm.javaMethod
```

- [ ] **Step 2: Add Mockito dependency**

If `org.mockito.kotlin` is unavailable, add to `modules/notification-slack/build.gradle.kts`:

```kotlin
testImplementation("org.mockito.kotlin:mockito-kotlin:6.1.0")
```

- [ ] **Step 3: Run test and verify it fails**

Run:

```bash
./gradlew :modules:notification-slack:test --tests '*SlackExceptionAspectTest'
```

Expected: FAIL because annotation and aspect do not exist.

- [ ] **Step 4: Implement annotation**

Create `SlackExceptionNotify.kt`:

```kotlin
package dev.sumin.skeleton.notification.slack

import kotlin.reflect.KClass

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class SlackExceptionNotify(
    val topic: String = "",
    val type: String = "exception",
    val severity: SlackAlertSeverity = SlackAlertSeverity.ERROR,
    val value: Array<KClass<out Throwable>> = [],
    val exclude: Array<KClass<out Throwable>> = [],
)
```

- [ ] **Step 5: Implement aspect**

Create `SlackExceptionAspect.kt`:

```kotlin
package dev.sumin.skeleton.notification.slack

import dev.sumin.skeleton.common.TraceIdFilter
import jakarta.servlet.http.HttpServletRequest
import org.aspectj.lang.JoinPoint
import org.aspectj.lang.annotation.AfterThrowing
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.lang.reflect.Method

@Aspect
@Order(Ordered.LOWEST_PRECEDENCE)
class SlackExceptionAspect(
    private val sender: SlackAlertSender,
    private val contributors: List<SlackAlertContextContributor>,
    private val properties: SlackNotificationProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @AfterThrowing(pointcut = "@annotation(slackExceptionNotify)", throwing = "throwable")
    fun afterThrowing(
        joinPoint: JoinPoint,
        slackExceptionNotify: SlackExceptionNotify,
        throwable: Throwable,
    ) {
        if (!shouldNotify(slackExceptionNotify, throwable)) return
        runCatching {
            val method = (joinPoint.signature as? MethodSignature)?.method
            val request = currentRequest()
            val trace = currentTraceContext()
            val context = SlackExceptionAlertContext(
                throwable = throwable,
                method = method,
                arguments = joinPoint.args?.toList().orEmpty(),
                request = request,
                trace = trace,
            )
            val contributorFields = contributors.flatMap { it.contribute(context).entries }
                .associate { it.key to it.value }
            sender.send(
                SlackAlert(
                    topic = slackExceptionNotify.topic.ifBlank { properties.defaultTopic },
                    type = slackExceptionNotify.type,
                    severity = slackExceptionNotify.severity,
                    title = "${throwable.javaClass.simpleName} in ${method.displayName()}",
                    message = throwable.message,
                    fields = defaultFields(throwable, method, request) + contributorFields,
                    trace = trace,
                ),
            )
        }.onFailure { alertError ->
            log.warn("Slack exception alert failed: {}", alertError.message)
        }
    }

    private fun shouldNotify(annotation: SlackExceptionNotify, throwable: Throwable): Boolean {
        if (annotation.exclude.any { it.java.isAssignableFrom(throwable.javaClass) }) return false
        if (annotation.value.isEmpty()) return true
        return annotation.value.any { it.java.isAssignableFrom(throwable.javaClass) }
    }

    private fun defaultFields(
        throwable: Throwable,
        method: Method?,
        request: HttpServletRequest?,
    ): Map<String, String?> =
        buildMap {
            put("exception", throwable.javaClass.simpleName)
            method?.let { put("handler", it.displayName()) }
            request?.let {
                put("request", "${it.method} ${it.requestURI}")
                it.queryString?.takeIf(String::isNotBlank)?.let { query -> put("query", query) }
            }
        }

    private fun currentRequest(): HttpServletRequest? =
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request

    private fun currentTraceContext(): SlackTraceContext =
        SlackTraceContext(
            traceId = MDC.get(TraceIdFilter.MDC_KEY),
            spanId = MDC.get(TraceIdFilter.MDC_SPAN_ID_KEY),
            parentSpanId = MDC.get(TraceIdFilter.MDC_PARENT_SPAN_ID_KEY),
        )

    private fun Method?.displayName(): String =
        this?.let { "${declaringClass.simpleName}.${name}" } ?: "unknown"
}
```

- [ ] **Step 6: Run aspect tests**

Run:

```bash
./gradlew :modules:notification-slack:test --tests '*SlackExceptionAspectTest'
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add modules/notification-slack
git commit -m "feat: add slack exception notification aspect"
```

---

### Task 7: Auto-Configure The Slack Module

**Files:**
- Create: `modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack/NotificationSlackAutoConfiguration.kt`
- Test: `modules/notification-slack/src/test/kotlin/dev/sumin/skeleton/notification/slack/NotificationSlackAutoConfigurationTest.kt`

- [ ] **Step 1: Write auto-config tests**

Create `NotificationSlackAutoConfigurationTest.kt`:

```kotlin
package dev.sumin.skeleton.notification.slack

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.ExternalHttpRequestSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import reactor.core.publisher.Mono

class NotificationSlackAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withBean(ExternalHttpClient::class.java) { NoopExternalHttpClient() }
        .withUserConfiguration(NotificationSlackAutoConfiguration::class.java)

    @Test
    fun `creates slack beans by default`() {
        contextRunner.run { context ->
            assertThat(context).hasSingleBean(SlackAlertSender::class.java)
            assertThat(context).hasSingleBean(SlackExceptionAspect::class.java)
            assertThat(context).hasSingleBean(SlackAlertMessageFactory::class.java)
        }
    }

    @Test
    fun `backs off when custom sender exists`() {
        contextRunner
            .withBean(SlackAlertSender::class.java) { SlackAlertSender { } }
            .run { context ->
                assertThat(context).hasSingleBean(SlackAlertSender::class.java)
            }
    }

    private class NoopExternalHttpClient : ExternalHttpClient {
        override fun <T : Any> get(
            clientName: String,
            path: String,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = error("not used")

        override fun <T : Any> post(
            clientName: String,
            path: String,
            body: Any?,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> {
            @Suppress("UNCHECKED_CAST")
            return Mono.just("ok" as T)
        }

        override fun <T : Any> postForm(
            clientName: String,
            path: String,
            form: Map<String, String>,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = error("not used")

        override fun <T : Any> put(
            clientName: String,
            path: String,
            body: Any?,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = error("not used")

        override fun <T : Any> patch(
            clientName: String,
            path: String,
            body: Any?,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = error("not used")

        override fun <T : Any> delete(
            clientName: String,
            path: String,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = error("not used")
    }
}
```

- [ ] **Step 2: Run test and verify it fails**

Run:

```bash
./gradlew :modules:notification-slack:test --tests '*NotificationSlackAutoConfigurationTest'
```

Expected: FAIL because auto-configuration does not exist.

- [ ] **Step 3: Implement auto-configuration**

Create `NotificationSlackAutoConfiguration.kt`:

```kotlin
package dev.sumin.skeleton.notification.slack

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.notification.NotificationAutoConfiguration
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration(after = [NotificationAutoConfiguration::class])
@EnableConfigurationProperties(SlackNotificationProperties::class)
class NotificationSlackAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun slackAlertMessageFactory(properties: SlackNotificationProperties): SlackAlertMessageFactory =
        SlackAlertMessageFactory(properties)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ExternalHttpClient::class)
    fun slackAlertSender(
        httpClient: ExternalHttpClient,
        properties: SlackNotificationProperties,
        messageFactory: SlackAlertMessageFactory,
    ): SlackAlertSender =
        SlackWebhookAlertSender(httpClient, properties, messageFactory)

    @Bean
    @ConditionalOnMissingBean
    fun slackNotificationForwarder(
        sender: SlackAlertSender,
        properties: SlackNotificationProperties,
        subscriptionRegistry: NotificationSubscriptionRegistry?,
    ): SlackNotificationForwarder =
        SlackNotificationForwarder(sender, properties, subscriptionRegistry)

    @Bean
    @ConditionalOnMissingBean
    fun slackExceptionAspect(
        sender: SlackAlertSender,
        contributors: List<SlackAlertContextContributor>,
        properties: SlackNotificationProperties,
    ): SlackExceptionAspect =
        SlackExceptionAspect(sender, contributors, properties)
}
```

- [ ] **Step 4: Run auto-config test**

Run:

```bash
./gradlew :modules:notification-slack:test --tests '*NotificationSlackAutoConfigurationTest'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add modules/notification-slack
git commit -m "feat: auto-configure slack notifications"
```

---

### Task 8: Add App-Level Integration Proof

**Files:**
- Modify: `apps/api/build.gradle.kts`
- Create: `apps/api/src/test/kotlin/dev/sumin/skeleton/api/SlackExceptionNotifyIntegrationTest.kt`

- [ ] **Step 1: Add app dependency**

Add to `apps/api/build.gradle.kts` near notification-related dependencies:

```kotlin
implementation(project(":modules:notification-slack"))
```

- [ ] **Step 2: Write integration test**

Create `SlackExceptionNotifyIntegrationTest.kt`:

```kotlin
package dev.sumin.skeleton.api

import dev.sumin.skeleton.notification.slack.SlackAlert
import dev.sumin.skeleton.notification.slack.SlackAlertSender
import dev.sumin.skeleton.notification.slack.SlackExceptionNotify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [SlackExceptionNotifyIntegrationTest.TestConfig::class],
)
@TestPropertySource(
    properties = [
        "skeleton.notification.slack.enabled=true",
        "skeleton.notification.slack.default-topic=operations",
    ],
)
class SlackExceptionNotifyIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `annotated controller exception sends slack alert with trace`() {
        CapturingSlackAlertSender.clear()
        val response = RestClient.create()
            .get()
            .uri("http://localhost:$port/test/slack-exception")
            .retrieve()
            .toBodilessEntity()

        assertThat(response.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        assertThat(CapturingSlackAlertSender.alerts).hasSize(1)
        assertThat(CapturingSlackAlertSender.alerts.single().trace.traceId).isNotBlank()
    }

    @TestConfiguration
    class TestConfig {
        @Bean
        fun testSlackController(): TestSlackController = TestSlackController()

        @Bean
        fun slackAlertSender(): SlackAlertSender = CapturingSlackAlertSender
    }

    @RestController
    class TestSlackController {
        @SlackExceptionNotify
        @GetMapping("/test/slack-exception")
        fun explode(): String {
            error("integration boom")
        }
    }

    object CapturingSlackAlertSender : SlackAlertSender {
        val alerts = mutableListOf<SlackAlert>()

        override fun send(alert: SlackAlert) {
            alerts += alert
        }

        fun clear() {
            alerts.clear()
        }
    }
}
```

- [ ] **Step 3: Run integration test**

Run:

```bash
./gradlew :apps:api:test --tests '*SlackExceptionNotifyIntegrationTest'
```

Expected: PASS and one captured Slack alert.

- [ ] **Step 4: Run full affected tests**

Run:

```bash
./gradlew :modules:notification-slack:test :apps:api:test --tests '*Slack*'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/api/build.gradle.kts apps/api/src/test/kotlin/dev/sumin/skeleton/api/SlackExceptionNotifyIntegrationTest.kt
git commit -m "test: verify slack exception notification integration"
```

---

### Task 9: Update Roadmap And Verify

**Files:**
- Modify: `docs/superpowers/specs/2026-06-11-modular-skeleton-roadmap.md`

- [ ] **Step 1: Mark notification-slack checklist**

In the `modules:notification-slack` section, mark implemented items:

```markdown
### 1. `modules:notification-slack` - Done
```

Mark completed checklist items as `[x]`.

- [ ] **Step 2: Add completion log**

Append:

```markdown
- 2026-06-11: `notification-slack` completed. Commit range: record the actual first and last implementation commit hashes from `git log --oneline -8`.
```

Use actual commit hashes from:

```bash
git log --oneline -8
```

- [ ] **Step 3: Run verification**

Run:

```bash
./gradlew :modules:notification-slack:test :apps:api:test --tests '*Slack*'
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-06-11-modular-skeleton-roadmap.md
git commit -m "docs: mark notification slack roadmap progress"
```

---

## Self-Review

- Spec coverage: Covers `notification-slack` roadmap items for webhook sender, payload builder, exception annotation, trace fields, contributor API, topic routing, safe defaults, and no direct product-domain dependencies.
- Plan scan: No incomplete markers or angle-bracket tokens remain. The roadmap update step instructs the implementer to record real commit hashes from `git log`.
- Type consistency: `SlackAlertSeverity`, `SlackNotificationProperties`, `SlackAlertSender`, `SlackNotificationForwarder`, `SlackExceptionNotify`, and `SlackExceptionAspect` are consistently named across tasks.
