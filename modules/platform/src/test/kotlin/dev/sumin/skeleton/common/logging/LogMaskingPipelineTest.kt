package dev.sumin.skeleton.common.logging

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.boot.json.JsonWriter
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer

/** 실제 logback 설정(앱이 include 하는 조각)을 거친 **출력**에서 비밀값이 사라지는지 — 부르는 쪽이 아무것도 안 가려도 */
class LogMaskingPipelineTest {
    private val bearer = "local-admin-token-0123456789abcdef"

    @Test
    fun `a logback config that includes the skeleton fragment masks message and stack output`() {
        val out = ByteArrayOutputStream()
        val originalOut = System.out
        System.setOut(PrintStream(out, true))
        val context = LoggerContext().also { it.mdcAdapter = ch.qos.logback.classic.util.LogbackMDCAdapter() }
        var statuses = ""
        try {
            val xml = """
                <configuration>
                    <include resource="dev/sumin/skeleton/logging/logback-masking.xml"/>
                    <appender name="OUT" class="ch.qos.logback.core.ConsoleAppender">
                        <encoder><pattern>%m%n%wEx</pattern></encoder>
                    </appender>
                    <root level="INFO"><appender-ref ref="OUT"/></root>
                </configuration>
            """.trimIndent()
            JoranConfigurator().also { it.context = context }.doConfigure(ByteArrayInputStream(xml.toByteArray()))
            val log = context.getLogger("leaky")
            log.info("call failed Bearer {} password=hunter2", bearer)
            log.error("boom", IllegalStateException("insert failed, token=SECRETVALUE Authorization: Bearer $bearer"))
            statuses = context.statusManager.copyOfStatusList.joinToString("\n") { it.toString() }
        } finally {
            context.stop()
            System.setOut(originalOut)
        }

        val text = out.toString() + statuses
        assertTrue(text.contains("call failed"), text)
        assertTrue(text.contains("IllegalStateException"), "스택은 남는다: $text")
        listOf(bearer, "hunter2", "SECRETVALUE").forEach { assertFalse(text.contains(it), "비밀값이 남았다: $it\n$text") }
    }

    @Test
    fun `the structured json customizer masks string values but not field names`() {
        @Suppress("UNCHECKED_CAST")
        val customizer = MaskingStructuredLoggingCustomizer() as StructuredLoggingJsonMembersCustomizer<Any>
        val writer = JsonWriter.of<Map<String, String>> { members ->
            members.add("message", java.util.function.Function<Map<String, String>, String?> { it["message"] })
            members.add("password", java.util.function.Function<Map<String, String>, String?> { it["password"] })
            customizer.customize(members as JsonWriter.Members<Any>)
        }

        val json = writer.writeToString(mapOf("message" to "failed Bearer $bearer", "password" to "hunter2"))

        assertFalse(json.contains(bearer), json)
        assertFalse(json.contains("hunter2"), json)
        assertTrue(json.contains("\"password\""), "필드 이름은 그대로: $json")
    }
}
