package dev.sumin.skeleton.persistence.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class JdbcAuditAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JdbcAuditAutoConfiguration::class.java))

    @Test
    fun `auto configuration registers jdbc audit callback using custom time provider`() {
        val fixed = Instant.parse("2026-06-09T05:00:00.987654321Z")

        contextRunner
            .withBean(TimeProvider::class.java, { TimeProvider.fixed(fixed) })
            .run { context ->
                assertThat(context).hasSingleBean(JdbcAuditBeforeConvertCallback::class.java)

                val callback = context.getBean(JdbcAuditBeforeConvertCallback::class.java)
                val entity = callback.onBeforeConvert(
                    SampleJdbcEntity(
                        id = null,
                        audit = AuditTimestamps.now(TimeProvider.fixed(Instant.parse("2026-06-09T01:00:00Z"))),
                    ),
                ) as SampleJdbcEntity

                assertThat(entity.audit.createdAt).isEqualTo(Instant.parse("2026-06-09T05:00:00.987654Z"))
                assertThat(entity.audit.updatedAt).isEqualTo(Instant.parse("2026-06-09T05:00:00.987654Z"))
            }
    }

    private data class SampleJdbcEntity(
        val id: Long?,
        override val audit: AuditTimestamps,
    ) : JdbcAuditable {
        override val isNew: Boolean
            get() = id == null

        override fun withAudit(audit: AuditTimestamps): SampleJdbcEntity =
            copy(audit = audit)
    }
}
