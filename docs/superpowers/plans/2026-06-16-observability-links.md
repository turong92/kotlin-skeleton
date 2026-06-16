# Observability Links Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add property-driven observability links that can turn trace/run/provider identifiers into clickable operator links in Slack alerts and notification forwarding.

**Architecture:** Put the core resolver contract in `modules:platform` because trace context, logging, and external HTTP already live there. Slack remains an optional consumer: it renders links when a resolver produces them and stays unchanged when no templates are configured.

**Tech Stack:** Kotlin, Spring Boot auto-configuration, `@ConfigurationProperties`, existing Slack alert blocks, existing notification contracts, JUnit/Kotlin tests.

---

## File Structure

- Create `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/observability/ObservabilityContext.kt`: safe search context value object and MDC factory.
- Create `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/observability/ObservabilityLink.kt`: link DTO and kind enum.
- Create `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/observability/ObservabilityLinkResolver.kt`: public resolver interface.
- Create `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/observability/ObservabilityLinkProperties.kt`: `skeleton.observability.links` properties.
- Create `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/observability/DefaultObservabilityLinkResolver.kt`: template expansion implementation.
- Create `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/observability/ObservabilityLinkAutoConfiguration.kt`: default resolver bean.
- Modify `modules/platform/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`: register the auto-configuration.
- Create `modules/platform/src/test/kotlin/dev/sumin/skeleton/common/observability/ObservabilityLinkResolverTest.kt`: platform resolver contract tests.
- Modify `modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack/SlackAlert.kt`: attach links to alerts.
- Modify `modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack/SlackAlertMessageFactory.kt`: render Slack link context.
- Modify `modules/notification-slack/src/test/kotlin/dev/sumin/skeleton/notification/slack/SlackAlertMessageFactoryTest.kt`: rendering tests.
- Modify `modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack/SlackExceptionAspect.kt`: resolve links for annotation-driven exception alerts.
- Modify `modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack/SlackNotificationForwarder.kt`: resolve links for forwarded notification events.
- Modify `modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack/NotificationSlackAutoConfiguration.kt`: inject the resolver into Slack components.
- Modify Slack tests in `modules/notification-slack/src/test/kotlin/dev/sumin/skeleton/notification/slack`.
- Modify `apps/api/src/test/kotlin/dev/sumin/skeleton/api/SlackExceptionNotifyIntegrationTest.kt`: composition proof.
- Create `docs/observability-links.md`: user-facing config and usage guide.
- Modify `docs/superpowers/specs/2026-06-11-modular-skeleton-roadmap.md`: mark operational alert links as done.

---

### Task 1: Platform Observability Link Resolver

**Files:**
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/observability/ObservabilityContext.kt`
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/observability/ObservabilityLink.kt`
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/observability/ObservabilityLinkResolver.kt`
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/observability/ObservabilityLinkProperties.kt`
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/observability/DefaultObservabilityLinkResolver.kt`
- Create: `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/observability/ObservabilityLinkAutoConfiguration.kt`
- Modify: `modules/platform/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `modules/platform/src/test/kotlin/dev/sumin/skeleton/common/observability/ObservabilityLinkResolverTest.kt`

- [ ] **Step 1: Write the failing platform resolver tests**

Create `modules/platform/src/test/kotlin/dev/sumin/skeleton/common/observability/ObservabilityLinkResolverTest.kt`:

```kotlin
package dev.sumin.skeleton.common.observability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class ObservabilityLinkResolverTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ObservabilityLinkAutoConfiguration::class.java))

    @Test
    fun `resolves configured links with encoded placeholders`() {
        contextRunner
            .withPropertyValues(
                "skeleton.observability.links.enabled=true",
                "skeleton.observability.links.templates.logs.label=Logs by traceId",
                "skeleton.observability.links.templates.logs.kind=LOGS",
                "skeleton.observability.links.templates.logs.url=https://grafana.example/explore?query={traceId}&account={accountId}",
                "skeleton.observability.links.templates.logs.required-fields[0]=traceId",
                "skeleton.observability.links.templates.logs.required-fields[1]=accountId",
            )
            .run { context ->
                val resolver = context.getBean(ObservabilityLinkResolver::class.java)

                val links = resolver.resolve(
                    ObservabilityContext.of(
                        "traceId" to "4bf92f3577b34da6a3ce929d0e0e4736",
                        "accountId" to "acc user",
                    ),
                )

                assertEquals(1, links.size)
                assertEquals("logs", links.single().id)
                assertEquals("Logs by traceId", links.single().label)
                assertEquals(ObservabilityLinkKind.LOGS, links.single().kind)
                assertEquals(
                    "https://grafana.example/explore?query=4bf92f3577b34da6a3ce929d0e0e4736&account=acc%20user",
                    links.single().url,
                )
            }
    }

    @Test
    fun `skips templates when required fields are missing or blank`() {
        contextRunner
            .withPropertyValues(
                "skeleton.observability.links.enabled=true",
                "skeleton.observability.links.templates.run.label=Flow by runId",
                "skeleton.observability.links.templates.run.kind=RUN",
                "skeleton.observability.links.templates.run.url=https://ops.example/runs/{runId}",
                "skeleton.observability.links.templates.run.required-fields[0]=runId",
            )
            .run { context ->
                val resolver = context.getBean(ObservabilityLinkResolver::class.java)

                assertTrue(resolver.resolve(ObservabilityContext.of("runId" to null)).isEmpty())
                assertTrue(resolver.resolve(ObservabilityContext.of("runId" to " ")).isEmpty())
                assertTrue(resolver.resolve(ObservabilityContext.of("traceId" to "abc")).isEmpty())
            }
    }

    @Test
    fun `returns empty links when disabled`() {
        contextRunner.run { context ->
            val resolver = context.getBean(ObservabilityLinkResolver::class.java)

            assertTrue(
                resolver.resolve(ObservabilityContext.of("traceId" to "4bf92f3577b34da6a3ce929d0e0e4736")).isEmpty(),
            )
        }
    }

    @Test
    fun `fails startup for unknown placeholders when enabled`() {
        contextRunner
            .withPropertyValues(
                "skeleton.observability.links.enabled=true",
                "skeleton.observability.links.templates.logs.label=Broken",
                "skeleton.observability.links.templates.logs.kind=LOGS",
                "skeleton.observability.links.templates.logs.url=https://logs.example/{unknownField}",
            )
            .run { context ->
                assertFailsWith<IllegalStateException> {
                    context.getBean(ObservabilityLinkResolver::class.java)
                }
            }
    }

    @Test
    fun `backs off when app provides resolver`() {
        val customResolver = ObservabilityLinkResolver {
            listOf(
                ObservabilityLink(
                    id = "custom",
                    label = "Custom",
                    url = "https://custom.example",
                    kind = ObservabilityLinkKind.CUSTOM,
                ),
            )
        }

        contextRunner
            .withBean(ObservabilityLinkResolver::class.java) { customResolver }
            .run { context ->
                assertEquals(customResolver, context.getBean(ObservabilityLinkResolver::class.java))
            }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :modules:platform:test --tests '*ObservabilityLinkResolverTest*'
```

Expected: FAIL with unresolved references such as `ObservabilityLinkAutoConfiguration`, `ObservabilityLinkResolver`, and `ObservabilityContext`.

- [ ] **Step 3: Create the platform observability types**

Create `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/observability/ObservabilityContext.kt`:

```kotlin
package dev.sumin.skeleton.common.observability

import dev.sumin.skeleton.common.TraceIdFilter
import org.slf4j.MDC

data class ObservabilityContext(
    private val values: Map<String, String?> = emptyMap(),
) {
    fun value(name: String): String? =
        values[name]?.trim()?.takeIf { it.isNotBlank() }

    fun asMap(): Map<String, String> =
        values.mapNotNull { (name, value) -> value?.trim()?.takeIf { it.isNotBlank() }?.let { name to it } }.toMap()

    fun withValues(additionalValues: Map<String, Any?>): ObservabilityContext =
        ObservabilityContext(values + additionalValues.toStringValues())

    companion object {
        val knownFields: Set<String> = setOf(
            "traceId",
            "spanId",
            "parentSpanId",
            "runId",
            "accountId",
            "errorCode",
            "provider",
            "providerTraceId",
            "providerRequestId",
            "providerOperationId",
            "topic",
            "type",
            "route",
            "method",
        )

        fun of(vararg pairs: Pair<String, Any?>): ObservabilityContext =
            ObservabilityContext(pairs.toMap().toStringValues())

        fun fromMdc(additionalValues: Map<String, Any?> = emptyMap()): ObservabilityContext =
            ObservabilityContext(
                mapOf(
                    "traceId" to MDC.get(TraceIdFilter.MDC_KEY),
                    "spanId" to MDC.get(TraceIdFilter.MDC_SPAN_ID_KEY),
                    "parentSpanId" to MDC.get(TraceIdFilter.MDC_PARENT_SPAN_ID_KEY),
                ) + additionalValues.toStringValues(),
            )

        private fun Map<String, Any?>.toStringValues(): Map<String, String?> =
            mapValues { (_, value) -> value?.toString() }
    }
}
```

Create `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/observability/ObservabilityLink.kt`:

```kotlin
package dev.sumin.skeleton.common.observability

data class ObservabilityLink(
    val id: String,
    val label: String,
    val url: String,
    val kind: ObservabilityLinkKind = ObservabilityLinkKind.CUSTOM,
)

enum class ObservabilityLinkKind {
    LOGS,
    TRACE,
    RUN,
    PROVIDER,
    CUSTOM,
}
```

Create `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/observability/ObservabilityLinkResolver.kt`:

```kotlin
package dev.sumin.skeleton.common.observability

fun interface ObservabilityLinkResolver {
    fun resolve(context: ObservabilityContext): List<ObservabilityLink>
}
```

- [ ] **Step 4: Create properties, resolver, and auto-configuration**

Create `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/observability/ObservabilityLinkProperties.kt`:

```kotlin
package dev.sumin.skeleton.common.observability

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("skeleton.observability.links")
data class ObservabilityLinkProperties(
    val enabled: Boolean = false,
    val customFields: Set<String> = emptySet(),
    val templates: Map<String, Template> = emptyMap(),
) {
    data class Template(
        val label: String = "",
        val kind: ObservabilityLinkKind = ObservabilityLinkKind.CUSTOM,
        val url: String = "",
        val requiredFields: List<String> = emptyList(),
    )
}
```

Create `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/observability/DefaultObservabilityLinkResolver.kt`:

```kotlin
package dev.sumin.skeleton.common.observability

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class DefaultObservabilityLinkResolver(
    private val properties: ObservabilityLinkProperties,
) : ObservabilityLinkResolver {
    private val placeholderRegex = Regex("\\{([A-Za-z][A-Za-z0-9]*)}")
    private val allowedFields = ObservabilityContext.knownFields + properties.customFields

    init {
        if (properties.enabled) validateTemplates()
    }

    override fun resolve(context: ObservabilityContext): List<ObservabilityLink> {
        if (!properties.enabled) return emptyList()

        return properties.templates.mapNotNull { (id, template) ->
            if (template.label.isBlank() || template.url.isBlank()) return@mapNotNull null
            if (template.requiredFields.any { field -> context.value(field).isNullOrBlank() }) return@mapNotNull null

            val placeholders = placeholders(template.url)
            if (placeholders.any { field -> context.value(field).isNullOrBlank() }) return@mapNotNull null

            ObservabilityLink(
                id = id,
                label = template.label,
                url = expand(template.url, context),
                kind = template.kind,
            )
        }
    }

    private fun validateTemplates() {
        properties.templates.forEach { (id, template) ->
            val fields = placeholders(template.url) + template.requiredFields
            val unknownFields = fields.filterNot { it in allowedFields }.toSet()
            check(unknownFields.isEmpty()) {
                "Unknown observability link fields for template '$id': ${unknownFields.joinToString(", ")}"
            }
        }
    }

    private fun placeholders(template: String): Set<String> =
        placeholderRegex.findAll(template).map { match -> match.groupValues[1] }.toSet()

    private fun expand(
        template: String,
        context: ObservabilityContext,
    ): String =
        placeholderRegex.replace(template) { match ->
            encode(context.value(match.groupValues[1]).orEmpty())
        }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}
```

Create `modules/platform/src/main/kotlin/dev/sumin/skeleton/common/observability/ObservabilityLinkAutoConfiguration.kt`:

```kotlin
package dev.sumin.skeleton.common.observability

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration
@EnableConfigurationProperties(ObservabilityLinkProperties::class)
class ObservabilityLinkAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ObservabilityLinkResolver::class)
    fun observabilityLinkResolver(properties: ObservabilityLinkProperties): ObservabilityLinkResolver =
        DefaultObservabilityLinkResolver(properties)
}
```

Modify `modules/platform/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` by appending:

```text
dev.sumin.skeleton.common.observability.ObservabilityLinkAutoConfiguration
```

- [ ] **Step 5: Run the platform tests to verify they pass**

Run:

```bash
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :modules:platform:test --tests '*ObservabilityLinkResolverTest*'
```

Expected: PASS.

- [ ] **Step 6: Commit platform observability core**

```bash
git add modules/platform/src/main/kotlin/dev/sumin/skeleton/common/observability \
  modules/platform/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports \
  modules/platform/src/test/kotlin/dev/sumin/skeleton/common/observability/ObservabilityLinkResolverTest.kt
git commit -m "feat: add observability link resolver"
```

---

### Task 2: Render Observability Links in Slack Alerts

**Files:**
- Modify: `modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack/SlackAlert.kt`
- Modify: `modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack/SlackAlertMessageFactory.kt`
- Modify: `modules/notification-slack/src/test/kotlin/dev/sumin/skeleton/notification/slack/SlackAlertMessageFactoryTest.kt`

- [ ] **Step 1: Write the failing Slack message rendering test**

Append this test to `SlackAlertMessageFactoryTest`:

```kotlin
@Test
fun `renders observability links when alert has links`() {
    val factory = SlackAlertMessageFactory(SlackNotificationProperties())
    val payload = factory.create(
        alert = SlackAlert(
            title = "Async failed",
            message = "worker failed",
            severity = SlackAlertSeverity.ERROR,
            topic = "async.exception",
            links = listOf(
                dev.sumin.skeleton.common.observability.ObservabilityLink(
                    id = "logs",
                    label = "Logs by traceId",
                    url = "https://grafana.example/explore?traceId=4bf92f3577b34da6a3ce929d0e0e4736",
                    kind = dev.sumin.skeleton.common.observability.ObservabilityLinkKind.LOGS,
                ),
            ),
        ),
    )

    val body = mapper.writeValueAsString(payload)

    assertTrue(body.contains("Links:"))
    assertTrue(body.contains("&lt;https://grafana.example/explore?traceId=4bf92f3577b34da6a3ce929d0e0e4736|Logs by traceId&gt;"))
}
```

- [ ] **Step 2: Run the Slack message test to verify it fails**

Run:

```bash
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :modules:notification-slack:test --tests '*SlackAlertMessageFactoryTest*'
```

Expected: FAIL because `SlackAlert.links` does not exist.

- [ ] **Step 3: Add links to `SlackAlert`**

Modify `SlackAlert.kt`:

```kotlin
package dev.sumin.skeleton.notification.slack

import dev.sumin.skeleton.common.observability.ObservabilityLink
import java.time.Instant

data class SlackAlert(
    val title: String,
    val message: String,
    val severity: SlackAlertSeverity = SlackAlertSeverity.ERROR,
    val topic: String = "operations",
    val route: String? = null,
    val fields: Map<String, String?> = emptyMap(),
    val trace: SlackTraceContext = SlackTraceContext.empty(),
    val links: List<ObservabilityLink> = emptyList(),
    val occurredAt: Instant = Instant.now(),
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

- [ ] **Step 4: Render links in Slack payloads**

Modify `SlackAlertMessageFactory.create` to build blocks in a mutable list:

```kotlin
        val blocks = mutableListOf(
            SlackBlock(
                type = "section",
                text = SlackText(
                    text = "*[${alert.severity.name}] ${escape(alert.title)}*\n${escape(alert.message)}",
                ),
            ),
            SlackBlock(
                type = "section",
                fields = fields
                    .filterValues { !it.isNullOrBlank() }
                    .map { (name, value) -> SlackText(text = "*${escape(name)}*\n${escape(value.orEmpty())}") },
            ),
        )
        alert.links
            .takeIf { it.isNotEmpty() }
            ?.let { links ->
                blocks += SlackBlock(
                    type = "context",
                    elements = listOf(SlackText(text = "Links: ${links.joinToString(" · ") { it.toSlackLink() }}")),
                )
            }
        blocks += SlackBlock(
            type = "context",
            elements = listOf(SlackText(text = "occurredAt=${alert.occurredAt}")),
        )

        return SlackWebhookPayload(
            text = "[${alert.severity.name}] ${alert.title}",
            username = route.username,
            iconEmoji = route.iconEmoji,
            blocks = blocks,
        )
```

Add this helper inside `SlackAlertMessageFactory`:

```kotlin
    private fun dev.sumin.skeleton.common.observability.ObservabilityLink.toSlackLink(): String =
        "<${escape(url)}|${escape(label)}>"
```

- [ ] **Step 5: Run the Slack message test to verify it passes**

Run:

```bash
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :modules:notification-slack:test --tests '*SlackAlertMessageFactoryTest*'
```

Expected: PASS.

- [ ] **Step 6: Commit Slack link rendering**

```bash
git add modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack/SlackAlert.kt \
  modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack/SlackAlertMessageFactory.kt \
  modules/notification-slack/src/test/kotlin/dev/sumin/skeleton/notification/slack/SlackAlertMessageFactoryTest.kt
git commit -m "feat: render observability links in slack alerts"
```

---

### Task 3: Resolve Links for Slack Exceptions and Notification Events

**Files:**
- Modify: `modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack/SlackExceptionAspect.kt`
- Modify: `modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack/SlackNotificationForwarder.kt`
- Modify: `modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack/NotificationSlackAutoConfiguration.kt`
- Modify: `modules/notification-slack/src/test/kotlin/dev/sumin/skeleton/notification/slack/SlackExceptionAspectTest.kt`
- Modify: `modules/notification-slack/src/test/kotlin/dev/sumin/skeleton/notification/slack/SlackNotificationForwarderTest.kt`
- Modify: `modules/notification-slack/src/test/kotlin/dev/sumin/skeleton/notification/slack/NotificationSlackAutoConfigurationTest.kt`

- [ ] **Step 1: Write failing tests for exception and notification link resolution**

In `SlackExceptionAspectTest`, add this assertion block to `sends exception alert with trace and contributor fields` after existing trace assertions:

```kotlin
        assertEquals(1, alert.links.size)
        assertEquals("logs", alert.links.single().id)
        assertEquals("https://logs.example/trace/4bf92f3577b34da6a3ce929d0e0e4736", alert.links.single().url)
```

Update the aspect construction in that test:

```kotlin
        val aspect = SlackExceptionAspect(
            sender = sender,
            contributors = listOf(
                SlackAlertContextContributor { mapOf("accountId" to "account-1") },
            ),
            properties = SlackNotificationProperties(defaultTopic = "operations"),
            linkResolver = RecordingObservabilityLinkResolver(),
        )
```

Add this helper class inside `SlackExceptionAspectTest`:

```kotlin
    private class RecordingObservabilityLinkResolver : dev.sumin.skeleton.common.observability.ObservabilityLinkResolver {
        override fun resolve(
            context: dev.sumin.skeleton.common.observability.ObservabilityContext,
        ): List<dev.sumin.skeleton.common.observability.ObservabilityLink> =
            listOf(
                dev.sumin.skeleton.common.observability.ObservabilityLink(
                    id = "logs",
                    label = "Logs",
                    url = "https://logs.example/trace/${context.value("traceId")}",
                    kind = dev.sumin.skeleton.common.observability.ObservabilityLinkKind.LOGS,
                ),
            )
    }
```

In `SlackNotificationForwarderTest`, update the forwarder construction in `forwards notification event as Slack alert`:

```kotlin
        SlackNotificationForwarder(
            sender = sender,
            properties = SlackNotificationProperties(notificationEvents = true),
            subscriptionRegistry = registry,
            linkResolver = RecordingObservabilityLinkResolver(),
        )
```

Add these assertions after the trace assertions:

```kotlin
        assertEquals(1, alert.links.size)
        assertEquals("run", alert.links.single().id)
        assertEquals("https://ops.example/runs/run-1", alert.links.single().url)
```

Add `runId` to the notification payload in that test:

```kotlin
payload = mapOf("orderId" to "order-1", "runId" to "run-1", "nullable" to null),
```

Add this helper class inside `SlackNotificationForwarderTest`:

```kotlin
    private class RecordingObservabilityLinkResolver : dev.sumin.skeleton.common.observability.ObservabilityLinkResolver {
        override fun resolve(
            context: dev.sumin.skeleton.common.observability.ObservabilityContext,
        ): List<dev.sumin.skeleton.common.observability.ObservabilityLink> =
            listOfNotNull(
                context.value("runId")?.let { runId ->
                    dev.sumin.skeleton.common.observability.ObservabilityLink(
                        id = "run",
                        label = "Run",
                        url = "https://ops.example/runs/$runId",
                        kind = dev.sumin.skeleton.common.observability.ObservabilityLinkKind.RUN,
                    )
                },
            )
    }
```

- [ ] **Step 2: Run Slack tests to verify they fail**

Run:

```bash
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :modules:notification-slack:test --tests '*SlackExceptionAspectTest*' --tests '*SlackNotificationForwarderTest*'
```

Expected: FAIL because constructors do not accept `linkResolver` and alerts do not receive links.

- [ ] **Step 3: Update `SlackExceptionAspect` to resolve links**

Modify the constructor:

```kotlin
class SlackExceptionAspect(
    private val sender: SlackAlertSender,
    private val contributors: List<SlackAlertContextContributor>,
    private val properties: SlackNotificationProperties,
    private val linkResolver: dev.sumin.skeleton.common.observability.ObservabilityLinkResolver =
        dev.sumin.skeleton.common.observability.ObservabilityLinkResolver { emptyList() },
) {
```

Before the `sender.send` call in `notifyAfterThrowing`, add:

```kotlin
            val links = linkResolver.resolve(
                dev.sumin.skeleton.common.observability.ObservabilityContext.fromMdc(
                    fields + mapOf(
                        "route" to route,
                        "method" to fields["method"],
                    ),
                ),
            )
```

Add `links = links` to the `SlackAlert` constructor call:

```kotlin
                    links = links,
```

- [ ] **Step 4: Update `SlackNotificationForwarder` to resolve links**

Modify the constructor:

```kotlin
class SlackNotificationForwarder(
    private val sender: SlackAlertSender,
    private val properties: SlackNotificationProperties,
    subscriptionRegistry: NotificationSubscriptionRegistry?,
    private val linkResolver: dev.sumin.skeleton.common.observability.ObservabilityLinkResolver =
        dev.sumin.skeleton.common.observability.ObservabilityLinkResolver { emptyList() },
) : AutoCloseable {
```

In `forward(event)`, add:

```kotlin
        val fields = event.fields()
        val links = linkResolver.resolve(
            dev.sumin.skeleton.common.observability.ObservabilityContext.fromMdc(
                fields + mapOf(
                    "topic" to event.topic,
                    "type" to event.type,
                    "route" to event.topic,
                ),
            ),
        )
```

Then use `fields = fields` and `links = links` in the `SlackAlert` constructor call:

```kotlin
                fields = fields,
                trace = SlackTraceContexts.current(),
                links = links,
                occurredAt = event.createdAt,
```

- [ ] **Step 5: Inject resolver through Slack auto-configuration**

Modify `NotificationSlackAutoConfiguration` imports:

```kotlin
import dev.sumin.skeleton.common.observability.ObservabilityLinkAutoConfiguration
import dev.sumin.skeleton.common.observability.ObservabilityLinkResolver
```

Add `ObservabilityLinkAutoConfiguration::class` to the `after` list.

Modify `slackNotificationForwarder`:

```kotlin
    fun slackNotificationForwarder(
        sender: SlackAlertSender,
        properties: SlackNotificationProperties,
        subscriptionRegistry: ObjectProvider<NotificationSubscriptionRegistry>,
        linkResolver: ObjectProvider<ObservabilityLinkResolver>,
    ): SlackNotificationForwarder =
        SlackNotificationForwarder(
            sender = sender,
            properties = properties,
            subscriptionRegistry = subscriptionRegistry.ifAvailable,
            linkResolver = linkResolver.getIfAvailable { ObservabilityLinkResolver { emptyList() } },
        )
```

Modify `slackExceptionAspect`:

```kotlin
    fun slackExceptionAspect(
        sender: SlackAlertSender,
        contributors: List<SlackAlertContextContributor>,
        properties: SlackNotificationProperties,
        linkResolver: ObjectProvider<ObservabilityLinkResolver>,
    ): SlackExceptionAspect =
        SlackExceptionAspect(
            sender = sender,
            contributors = contributors,
            properties = properties,
            linkResolver = linkResolver.getIfAvailable { ObservabilityLinkResolver { emptyList() } },
        )
```

- [ ] **Step 6: Update auto-configuration test**

In `NotificationSlackAutoConfigurationTest`, add this assertion to `creates Slack beans when module is present`:

```kotlin
            assertEquals(1, context.getBeansOfType(dev.sumin.skeleton.common.observability.ObservabilityLinkResolver::class.java).size)
```

Update the runner configuration:

```kotlin
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                dev.sumin.skeleton.common.observability.ObservabilityLinkAutoConfiguration::class.java,
                NotificationSlackAutoConfiguration::class.java,
            ),
        )
        .withBean(ExternalHttpClient::class.java, Supplier { NoopExternalHttpClient() })
        .withBean(NotificationSubscriptionRegistry::class.java, Supplier { NoopSubscriptionRegistry() })
```

- [ ] **Step 7: Run Slack tests to verify they pass**

Run:

```bash
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :modules:notification-slack:test --rerun-tasks
```

Expected: PASS.

- [ ] **Step 8: Commit Slack link resolution**

```bash
git add modules/notification-slack/src/main/kotlin/dev/sumin/skeleton/notification/slack \
  modules/notification-slack/src/test/kotlin/dev/sumin/skeleton/notification/slack
git commit -m "feat: attach observability links to slack alerts"
```

---

### Task 4: App Composition Proof and Documentation

**Files:**
- Modify: `apps/api/src/test/kotlin/dev/sumin/skeleton/api/SlackExceptionNotifyIntegrationTest.kt`
- Create: `docs/observability-links.md`
- Modify: `docs/superpowers/specs/2026-06-11-modular-skeleton-roadmap.md`

- [ ] **Step 1: Write the failing app composition assertion**

Modify `SlackExceptionNotifyIntegrationTest` `@SpringBootTest` properties:

```kotlin
@SpringBootTest(
    properties = [
        "skeleton.notification.slack.enabled=true",
        "skeleton.notification.slack.webhook-url=https://hooks.slack.example/test",
        "skeleton.observability.links.enabled=true",
        "skeleton.observability.links.templates.logs.label=Logs by traceId",
        "skeleton.observability.links.templates.logs.kind=LOGS",
        "skeleton.observability.links.templates.logs.url=https://logs.example/trace/{traceId}",
        "skeleton.observability.links.templates.logs.required-fields[0]=traceId",
    ],
)
```

Add assertions to `annotated controller exception creates Slack alert with request trace`:

```kotlin
        assertEquals(1, alert.links.size)
        assertEquals("logs", alert.links.single().id)
        assertEquals("Logs by traceId", alert.links.single().label)
        assertEquals("https://logs.example/trace/${alert.trace.traceId}", alert.links.single().url)
```

- [ ] **Step 2: Run the app integration test**

Run:

```bash
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :apps:api:test --tests '*SlackExceptionNotifyIntegrationTest*'
```

Expected: PASS after Task 3. If it fails, inspect whether `ObservabilityLinkAutoConfiguration` is loaded through `modules:platform`.

- [ ] **Step 3: Add user-facing documentation**

Create `docs/observability-links.md`:

```markdown
# Observability Links

`modules:platform` provides `ObservabilityLinkResolver`, a vendor-neutral way
to turn safe identifiers such as `traceId`, `runId`, and provider request ids
into clickable operator links.

## Configuration

Links are disabled by default.

```yaml
skeleton:
  observability:
    links:
      enabled: true
      templates:
        logs:
          label: Logs by traceId
          kind: LOGS
          url: "https://grafana.example/explore?traceId={traceId}"
          required-fields: [traceId]
        run:
          label: Flow by runId
          kind: RUN
          url: "https://ops.example/runs/{runId}"
          required-fields: [runId]
        provider:
          label: Provider request
          kind: PROVIDER
          url: "https://ops.example/providers/{provider}/requests/{providerRequestId}"
          required-fields: [provider, providerRequestId]
```

Template values are URL-encoded. Templates are skipped when required fields are
missing. Unknown placeholders fail startup when links are enabled.

## Slack Alerts

`modules:notification-slack` consumes the resolver automatically. Exception
alerts and forwarded notification events render links when templates can be
resolved from MDC or event payload fields.

## API Errors

API error responses do not include links. Keep public errors stable and expose
diagnostic links through Slack, logs, dashboards, or internal workbench views.
```

- [ ] **Step 4: Mark the roadmap checklist item**

In `docs/superpowers/specs/2026-06-11-modular-skeleton-roadmap.md`, change:

```markdown
- [ ] Optional Grafana/Loki URL.
```

to:

```markdown
- [x] Optional Grafana/Loki URL.
```

- [ ] **Step 5: Run docs and app checks**

Run:

```bash
git diff --check
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :apps:api:test --tests '*SlackExceptionNotifyIntegrationTest*'
```

Expected: `git diff --check` prints nothing and the Gradle command passes.

- [ ] **Step 6: Commit app proof and docs**

```bash
git add apps/api/src/test/kotlin/dev/sumin/skeleton/api/SlackExceptionNotifyIntegrationTest.kt \
  docs/observability-links.md \
  docs/superpowers/specs/2026-06-11-modular-skeleton-roadmap.md
git commit -m "docs: document observability links"
```

---

### Task 5: Final Verification

**Files:**
- No planned file edits.

- [ ] **Step 1: Run focused module and app tests**

Run:

```bash
JAVA_HOME=/Users/sumin/.sdkman/candidates/java/21.0.5-tem ./gradlew :modules:platform:test :modules:notification-slack:test :apps:api:test --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Check generated Slack payload shape manually through tests**

Run:

```bash
rg -n "Links:|logs.example|grafana.example" modules/notification-slack/build/reports/tests apps/api/build/reports/tests -S || true
```

Expected: test report output contains link text in Slack payload assertions or no output when reports are HTML-escaped differently. This command is informational; the Gradle tests are the pass/fail gate.

- [ ] **Step 3: Confirm git state**

Run:

```bash
git status --short
git log --oneline -5
```

Expected: no uncommitted files, and the latest commits include:

```text
docs: document observability links
feat: attach observability links to slack alerts
feat: render observability links in slack alerts
feat: add observability link resolver
```

No cleanup commit is expected after this step. If verification fails, fix the
specific failing task and rerun the same verification command before reporting
completion.

---

## Self-Review

- Spec coverage: platform resolver, property templates, URL encoding, missing-field skipping, startup validation, Slack exception alerts, notification forwarding, app proof, docs, and unchanged API errors are covered.
- Placeholder scan: no open placeholder language remains in executable tasks.
- Type consistency: `ObservabilityContext`, `ObservabilityLink`, `ObservabilityLinkKind`, and `ObservabilityLinkResolver` are introduced in Task 1 and used consistently in Tasks 2-4.
