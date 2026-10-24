package dev.sumin.skeleton.common.authz

import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(OutputCaptureExtension::class)
class NoOpOperatorAuditLogTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AuthzAutoConfiguration::class.java))

    private val action = OperatorAction("acc_1", "authz.role.created", "role", "CS", mapOf("added" to "payment.read"))

    @Test
    fun `the default bean exists and writes nothing`(output: CapturedOutput) {
        runner.run { context ->
            val log = context.getBean(OperatorAuditLog::class.java)
            assertThat(log).isInstanceOf(NoOpOperatorAuditLog::class.java)
            log.record(action)
        }
        assertThat(output.all).doesNotContain("authz.role.created").doesNotContain("acc_1")
    }

    @Test
    fun `an app bean replaces the default`() {
        val seen = mutableListOf<OperatorAction>()
        runner.withBean(OperatorAuditLog::class.java, { OperatorAuditLog { seen += it } }).run { context ->
            context.getBean(OperatorAuditLog::class.java).record(action)
            assertThat(seen).containsExactly(action)
        }
    }
}
