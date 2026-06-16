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
            .withBean(ObservabilityLinkResolver::class.java, { customResolver })
            .run { context ->
                assertEquals(customResolver, context.getBean(ObservabilityLinkResolver::class.java))
            }
    }
}
