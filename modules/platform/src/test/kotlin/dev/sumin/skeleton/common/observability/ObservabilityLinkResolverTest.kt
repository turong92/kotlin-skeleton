package dev.sumin.skeleton.common.observability

import dev.sumin.skeleton.common.TraceIdFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.slf4j.MDC
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class ObservabilityLinkResolverTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ObservabilityLinkAutoConfiguration::class.java))

    @Test
    fun `context trims values and omits null or blank values`() {
        val context = ObservabilityContext.of(
            "traceId" to " 4bf92f3577b34da6a3ce929d0e0e4736 ",
            "runId" to " ",
            "accountId" to null,
            "provider" to "openai",
        )

        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", context.value("traceId"))
        assertEquals(null, context.value("runId"))
        assertEquals(null, context.value("accountId"))
        assertEquals(null, context.value("missing"))
        assertEquals(
            mapOf(
                "traceId" to "4bf92f3577b34da6a3ce929d0e0e4736",
                "provider" to "openai",
            ),
            context.asMap(),
        )
    }

    @Test
    fun `context withValues merges additional values`() {
        val context = ObservabilityContext.of(
            "traceId" to "old-trace",
            "spanId" to " ",
        ).withValues(
            mapOf(
                "traceId" to " new-trace ",
                "runId" to 42,
                "accountId" to null,
            ),
        )

        assertEquals("new-trace", context.value("traceId"))
        assertEquals("42", context.value("runId"))
        assertEquals(null, context.value("spanId"))
        assertEquals(null, context.value("accountId"))
        assertEquals(
            mapOf(
                "traceId" to "new-trace",
                "runId" to "42",
            ),
            context.asMap(),
        )
    }

    @Test
    fun `context fromMdc includes trace values and additional values`() {
        try {
            MDC.put(TraceIdFilter.MDC_KEY, "4bf92f3577b34da6a3ce929d0e0e4736")
            MDC.put(TraceIdFilter.MDC_SPAN_ID_KEY, "00f067aa0ba902b7")
            MDC.put(TraceIdFilter.MDC_PARENT_SPAN_ID_KEY, "f6b7a2c3d4e5f601")

            val context = ObservabilityContext.fromMdc(
                mapOf(
                    "accountId" to " acc user ",
                    "provider" to null,
                    "type" to " ",
                ),
            )

            assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", context.value("traceId"))
            assertEquals("00f067aa0ba902b7", context.value("spanId"))
            assertEquals("f6b7a2c3d4e5f601", context.value("parentSpanId"))
            assertEquals("acc user", context.value("accountId"))
            assertEquals(
                mapOf(
                    "traceId" to "4bf92f3577b34da6a3ce929d0e0e4736",
                    "spanId" to "00f067aa0ba902b7",
                    "parentSpanId" to "f6b7a2c3d4e5f601",
                    "accountId" to "acc user",
                ),
                context.asMap(),
            )
        } finally {
            MDC.remove(TraceIdFilter.MDC_KEY)
            MDC.remove(TraceIdFilter.MDC_SPAN_ID_KEY)
            MDC.remove(TraceIdFilter.MDC_PARENT_SPAN_ID_KEY)
        }
    }

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
    fun `allows configured custom fields in templates`() {
        contextRunner
            .withPropertyValues(
                "skeleton.observability.links.enabled=true",
                "skeleton.observability.links.custom-fields[0]=tenantId",
                "skeleton.observability.links.templates.tenant.label=Tenant logs",
                "skeleton.observability.links.templates.tenant.kind=CUSTOM",
                "skeleton.observability.links.templates.tenant.url=https://logs.example/tenants/{tenantId}",
                "skeleton.observability.links.templates.tenant.required-fields[0]=tenantId",
            )
            .run { context ->
                val resolver = context.getBean(ObservabilityLinkResolver::class.java)

                val links = resolver.resolve(ObservabilityContext.of("tenantId" to "tenant user"))

                assertEquals(1, links.size)
                assertEquals(
                    "https://logs.example/tenants/tenant%20user",
                    links.single().url,
                )
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
                val exception = assertFailsWith<IllegalStateException> {
                    context.getBean(ObservabilityLinkResolver::class.java)
                }
                val message = rootCauseMessage(exception)
                assertTrue(message.contains("logs"))
                assertTrue(message.contains("unknownField"))
            }
    }

    @Test
    fun `fails startup for malformed underscore placeholders when enabled`() {
        contextRunner
            .withPropertyValues(
                "skeleton.observability.links.enabled=true",
                "skeleton.observability.links.templates.tenant.label=Broken",
                "skeleton.observability.links.templates.tenant.kind=LOGS",
                "skeleton.observability.links.templates.tenant.url=https://logs.example/{tenant_id}",
            )
            .run { context ->
                val exception = assertFailsWith<IllegalStateException> {
                    context.getBean(ObservabilityLinkResolver::class.java)
                }
                val message = rootCauseMessage(exception)
                assertTrue(message.contains("tenant"))
                assertTrue(message.contains("tenant_id"))
            }
    }

    @Test
    fun `fails startup for malformed dashed placeholders when enabled`() {
        contextRunner
            .withPropertyValues(
                "skeleton.observability.links.enabled=true",
                "skeleton.observability.links.templates.trace.label=Broken",
                "skeleton.observability.links.templates.trace.kind=TRACE",
                "skeleton.observability.links.templates.trace.url=https://traces.example/{trace-id}",
            )
            .run { context ->
                val exception = assertFailsWith<IllegalStateException> {
                    context.getBean(ObservabilityLinkResolver::class.java)
                }
                val message = rootCauseMessage(exception)
                assertTrue(message.contains("trace"))
                assertTrue(message.contains("trace-id"))
            }
    }

    @Test
    fun `fails startup for empty or unbalanced placeholders when enabled`() {
        listOf(
            "https://logs.example/{traceId" to "{traceId",
            "https://logs.example/{}" to "{}",
            "https://logs.example/{traceId}}" to "}",
        ).forEachIndexed { index, (url, expectedToken) ->
            contextRunner
                .withPropertyValues(
                    "skeleton.observability.links.enabled=true",
                    "skeleton.observability.links.templates.malformed$index.label=Broken",
                    "skeleton.observability.links.templates.malformed$index.kind=LOGS",
                    "skeleton.observability.links.templates.malformed$index.url=$url",
                )
                .run { context ->
                    val exception = assertFailsWith<IllegalStateException> {
                        context.getBean(ObservabilityLinkResolver::class.java)
                    }
                    val message = rootCauseMessage(exception)
                    assertTrue(message.contains("malformed$index"))
                    assertTrue(message.contains(expectedToken))
                }
        }
    }

    @Test
    fun `fails startup for unknown required fields when enabled`() {
        contextRunner
            .withPropertyValues(
                "skeleton.observability.links.enabled=true",
                "skeleton.observability.links.templates.logs.label=Broken",
                "skeleton.observability.links.templates.logs.kind=LOGS",
                "skeleton.observability.links.templates.logs.url=https://logs.example/trace",
                "skeleton.observability.links.templates.logs.required-fields[0]=unknownField",
            )
            .run { context ->
                val exception = assertFailsWith<IllegalStateException> {
                    context.getBean(ObservabilityLinkResolver::class.java)
                }
                val message = rootCauseMessage(exception)
                assertTrue(message.contains("logs"))
                assertTrue(message.contains("unknownField"))
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
            .withBean(ObservabilityLinkResolver::class.java, { customResolver })
            .run { context ->
                assertEquals(customResolver, context.getBean(ObservabilityLinkResolver::class.java))
            }
    }

    private fun rootCauseMessage(exception: Throwable): String =
        generateSequence(exception) { it.cause }.last().message.orEmpty()
}
