package dev.sumin.skeleton.common.time

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class TimeAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TimeAutoConfiguration::class.java))

    @Test
    fun `auto configuration registers default microsecond precision time provider`() {
        contextRunner.run { context ->
            val provider = context.getBean(TimeProvider::class.java)

            assertTrue(provider.now().nano % 1_000 == 0)
        }
    }

    @Test
    fun `custom time provider overrides the default`() {
        val fixed = TimeProvider.fixed(Instant.parse("2026-06-09T06:00:00.123456789Z"))

        contextRunner
            .withBean(TimeProvider::class.java, { fixed })
            .run { context ->
                assertEquals(Instant.parse("2026-06-09T06:00:00.123456Z"), context.getBean(TimeProvider::class.java).now())
            }
    }
}
