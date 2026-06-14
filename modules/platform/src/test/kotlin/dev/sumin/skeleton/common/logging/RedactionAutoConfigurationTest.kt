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
}
