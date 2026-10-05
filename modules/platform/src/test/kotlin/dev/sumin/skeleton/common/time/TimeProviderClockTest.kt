package dev.sumin.skeleton.common.time

import dev.sumin.skeleton.common.web.InMemoryFixedWindowRateLimitStore
import dev.sumin.skeleton.common.web.WebPolicyAutoConfiguration
import dev.sumin.skeleton.common.web.RateLimitStore
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class TimeProviderClockTest {
    @Test
    fun `asClock follows the provider, so Clock consumers see the same time as TimeProvider consumers`() {
        val time = AtomicReference(Instant.parse("2026-10-01T00:00:00.123456789Z"))
        val clock = TimeProvider { TimeProvider.truncateToDatabasePrecision(time.get()) }.asClock()

        assertEquals(Instant.parse("2026-10-01T00:00:00.123456Z"), clock.instant())
        time.set(Instant.parse("2026-10-02T00:00:00Z"))
        assertEquals(Instant.parse("2026-10-02T00:00:00Z"), clock.instant())
        assertEquals(time.get().toEpochMilli(), clock.millis())
        assertEquals(ZoneOffset.UTC, clock.zone)
    }

    @Test
    fun `asClock keeps UTC when a zone is requested because instants carry no zone`() {
        val clock = TimeProvider.fixed(Instant.parse("2026-10-01T00:00:00Z")).asClock()
        assertSame(clock, clock.withZone(java.time.ZoneId.of("Asia/Seoul")))
    }

    @Test
    fun `the default in-memory rate limit store reads the TimeProvider bean, not the system clock`() {
        val past = Instant.parse("2020-01-01T00:00:00Z") // 시스템 시계로 보면 이 창은 오래전에 끝났다
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WebPolicyAutoConfiguration::class.java))
            .withPropertyValues("skeleton.web.rate-limit.enabled=true")
            .withBean(TimeProvider::class.java, { TimeProvider.fixed(past) })
            .withBean(tools.jackson.databind.ObjectMapper::class.java, { tools.jackson.databind.ObjectMapper() })
            .run { context ->
                val store = context.getBean(RateLimitStore::class.java) as InMemoryFixedWindowRateLimitStore
                store.consume("k", 5, 60_000, past)
                assertEquals(0, store.sweep(), "sweep() 기본 시각은 TimeProvider 의 고정 시각이라 창이 아직 살아 있다")
                assertEquals(1, store.size())
            }
    }
}
