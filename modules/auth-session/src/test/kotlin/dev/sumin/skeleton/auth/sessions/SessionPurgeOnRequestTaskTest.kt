package dev.sumin.skeleton.auth.sessions

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.support.StaticListableBeanFactory

/** 깨우기(요청 구동)가 세션 정리의 "끄기"(주기 0)를 무시하지 않는다. */
class SessionPurgeOnRequestTaskTest {
    private val time = object : TimeProvider { override fun now(): Instant = Instant.parse("2026-10-15T00:00:00Z") }
    private var purged = 0

    private fun task(interval: Duration) = AuthSessionAutoConfiguration().authSessionPurgeOnRequestTask(
        AuthSessionProperties(purge = AuthSessionProperties.Purge(interval = interval)),
        SessionPurge(
            object : SessionStore by InMemorySessionStore() { override fun purge(before: Instant): Int { purged++; return 0 } },
            time,
            AuthSessionProperties.Purge(interval = interval),
        ),
        StaticListableBeanFactory().getBeanProvider(dev.sumin.skeleton.common.project.ProjectRegistry::class.java),
    )

    @Test
    fun `a purge interval of zero keeps the wake address from purging sessions`() {
        task(Duration.ZERO).run()
        assertEquals(0, purged)
    }

    @Test
    fun `a running purge interval lets the wake address purge`() {
        task(Duration.ofHours(1)).run()
        assertEquals(1, purged)
    }
}
