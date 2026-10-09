package dev.sumin.skeleton.common.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class RedactionAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RedactionAutoConfiguration::class.java))

    @Test
    fun `auto configuration registers configured redactor`() {
        contextRunner
            .withPropertyValues(
                "skeleton.redaction.replacement=***",
                "skeleton.redaction.additional-sensitive-names[0]=merchantId",
            )
            .run { context ->
                val redactor = context.getBean(SensitiveValueRedactor::class.java)

                assertEquals("***", redactor.redact("merchantId", "merchant-1"))
                assertEquals("order-1", redactor.redact("orderId", "order-1"))
            }
    }

    @Test
    fun `a contributor bean adds its patterns to the log masker and the app patterns stay`() {
        contextRunner
            .withPropertyValues("skeleton.redaction.output.patterns[0]=(?<value>APPSECRET)")
            .withBean(LogMaskingContributor::class.java, { LogMaskingContributor { listOf("""/hooks/[a-z]+/(?<value>[^/\s]+)""") } })
            .run { context ->
                val masker = context.getBean(LogMasker::class.java)

                assertEquals("POST /hooks/toss/[REDACTED] 200 APPSECRET".replace("APPSECRET", "[REDACTED]"), masker.mask("POST /hooks/toss/abc123 200 APPSECRET"))
            }
    }

    @Test
    fun `a request path is masked by the contributor rules only - not by the app patterns and not by the default rules`() {
        contextRunner
            .withPropertyValues("skeleton.redaction.output.patterns[0]=(?<value>APPSECRET)")
            .withBean(LogMaskingContributor::class.java, { LogMaskingContributor { listOf("""/hooks/[a-z]+/(?<value>\S+)""") } })
            .run { context ->
                val masker = context.getBean(LogMasker::class.java)

                assertEquals("/hooks/toss/[REDACTED]", masker.maskRequestPath("/hooks/toss/abc123"))
                assertEquals("/x/APPSECRET/token=abc", masker.maskRequestPath("/x/APPSECRET/token=abc"))
            }
    }

    @Test
    fun `without a contributor a request path stays exactly as it came`() {
        contextRunner
            .withPropertyValues("skeleton.redaction.output.patterns[0]=(?<value>APPSECRET)")
            .run { context ->
                val path = "/x/APPSECRET/token=abc/" + "z".repeat(900)
                assertEquals(path, context.getBean(LogMasker::class.java).maskRequestPath(path))
            }
    }

    @Test
    fun `without a contributor the masker is the same as before`() {
        contextRunner.run { context ->
            assertEquals("POST /hooks/toss/abc123 200", context.getBean(LogMasker::class.java).mask("POST /hooks/toss/abc123 200"))
        }
    }
}
