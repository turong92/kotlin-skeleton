package dev.sumin.skeleton.notification

import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.mock.env.MockEnvironment
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

class NotificationInboxRetentionTest {
    /** 정리가 부른 자국 — (프로젝트, cutoff) */
    private class RecordingInbox : NotificationInboxRepository by NoopNotificationInboxRepository {
        val calls = CopyOnWriteArrayList<Pair<String?, Instant>>()

        override fun deleteOlderThan(cutoff: Instant): Int {
            calls += ProjectContext.currentOrNull()?.id to cutoff
            return 3
        }
    }

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(NotificationAutoConfiguration::class.java))

    @Test
    fun `run deletes everything older than now minus max-age`() {
        val inbox = RecordingInbox()
        val retention = NotificationInboxRetention(inbox, NotificationRetentionProperties(enabled = true, maxAge = Duration.ofDays(90)), TimeProvider.systemUtc())

        val deleted = retention.run(Instant.parse("2026-10-28T00:00:00Z"))

        assertEquals(3, deleted)
        assertEquals(listOf(null to Instant.parse("2026-10-28T00:00:00Z").minus(Duration.ofDays(90))), inbox.calls.toList())
    }

    @Test
    fun `retention is off by default - no bean and nothing scheduled`() {
        contextRunner.withBean(TaskScheduler::class.java, { ThreadPoolTaskScheduler().apply { initialize() } }).run { context ->
            assertTrue(context.getBeansOfType(NotificationInboxRetention::class.java).isEmpty())
        }
    }

    @Test
    fun `defaults are 90 days and 1 hour`() {
        val defaults = NotificationRetentionProperties()
        assertEquals(false, defaults.enabled)
        assertEquals(Duration.ofDays(90), defaults.maxAge)
        assertEquals(Duration.ofHours(1), defaults.interval)
    }

    @Test
    fun `when enabled the scheduler runs it on the interval`() {
        val inbox = RecordingInbox()
        val scheduler = ThreadPoolTaskScheduler().apply { initialize() }
        try {
            contextRunner
                .withBean(NotificationInboxRepository::class.java, { inbox })
                .withBean("skeletonTaskScheduler", TaskScheduler::class.java, { scheduler })
                .withPropertyValues("skeleton.notification.retention.enabled=true", "skeleton.notification.retention.interval=50ms")
                .run { context ->
                    assertEquals(1, context.getBeansOfType(NotificationInboxRetention::class.java).size)
                    awaitUntil { inbox.calls.size >= 2 }
                }
        } finally {
            scheduler.shutdown()
        }
    }

    @Test
    fun `without a scheduler enabling it only warns and still starts`() {
        contextRunner.withPropertyValues("skeleton.notification.retention.enabled=true").run { context ->
            assertEquals(null, context.startupFailure)
            assertEquals(1, context.getBeansOfType(NotificationInboxRetention::class.java).size)
        }
    }

    @Test
    fun `in project mode every active project is cleaned inside its own context`() {
        val inbox = RecordingInbox()
        val scheduler = ThreadPoolTaskScheduler().apply { initialize() }
        val env = MockEnvironment()
            .withProperty("skeleton.projects.a.hosts[0]", "a.test").withProperty("skeleton.projects.a.schema", "pa")
            .withProperty("skeleton.projects.b.hosts[0]", "b.test").withProperty("skeleton.projects.b.schema", "pb")
        try {
            contextRunner
                .withBean(NotificationInboxRepository::class.java, { inbox })
                .withBean("skeletonTaskScheduler", TaskScheduler::class.java, { scheduler })
                .withBean(ProjectRegistry::class.java, { ProjectRegistry.from(env) })
                .withPropertyValues("skeleton.notification.retention.enabled=true", "skeleton.notification.retention.interval=50ms")
                .run {
                    awaitUntil { inbox.calls.map { it.first }.toSet().containsAll(setOf("a", "b")) }
                    assertTrue(inbox.calls.none { it.first == null })
                }
        } finally {
            scheduler.shutdown()
        }
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition not met in time" }
            Thread.sleep(20)
        }
    }
}
