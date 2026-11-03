package dev.sumin.skeleton.video

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.storage.PresignedStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class VideoAutoConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(VideoAutoConfiguration::class.java))
        .withBean(TimeProvider::class.java, { TimeProvider.systemUtc() })
        .withBean(PresignedStorage::class.java, { FakeStorage() })

    private fun messages(t: Throwable?) = generateSequence(t) { it.cause }.mapNotNull { it.message }.toList()

    @Test
    fun `off by default - no service, no policy, nothing registered`() {
        runner.withBean(VideoStore::class.java, { FakeVideoStore() }).run { ctx ->
            assertNull(ctx.startupFailure)
            assertTrue(ctx.getBeansOfType(VideoUploads::class.java).isEmpty())
        }
    }

    @Test
    fun `enabled without a VideoStore stops the start and names the module that brings one`() {
        runner.withPropertyValues("skeleton.video.enabled=true").withBean(VideoWorkerStore::class.java, { object : VideoWorkerStore {} }).run { ctx ->
            assertTrue(messages(ctx.startupFailure).any { "VideoStore" in it && "video-jdbc" in it }, messages(ctx.startupFailure).toString())
        }
    }

    @Test
    fun `enabled without a VideoWorkerStore stops the start too`() {
        runner.withPropertyValues("skeleton.video.enabled=true").withBean(VideoStore::class.java, { FakeVideoStore() }).run { ctx ->
            assertTrue(messages(ctx.startupFailure).any { "VideoWorkerStore" in it }, messages(ctx.startupFailure).toString())
        }
    }

    @Test
    fun `enabled with both stores stands with the owner-only policy and an open quota`() {
        enabled().run { ctx ->
            assertNull(ctx.startupFailure)
            assertSame(OwnerVideoAccessPolicy, ctx.getBean(VideoAccessPolicy::class.java))
            assertTrue(ctx.getBean(VideoQuota::class.java).allow("acc_a", Long.MAX_VALUE, "any"))
            assertEquals(1, ctx.getBeansOfType(VideoUploads::class.java).size)
        }
    }

    @Test
    fun `an app that brings its own policy and quota replaces the defaults`() {
        val own = object : VideoAccessPolicy by OwnerVideoAccessPolicy {}
        enabled().withBean(VideoAccessPolicy::class.java, { own }).withBean(VideoQuota::class.java, { VideoQuota { _, _, _ -> false } }).run { ctx ->
            assertSame(own, ctx.getBean(VideoAccessPolicy::class.java))
            assertEquals(false, ctx.getBean(VideoQuota::class.java).allow("acc_a", 1, "x"))
        }
    }

    @Test
    fun `a video prefix inside the upload area stops the start`() {
        enabled().withPropertyValues("skeleton.video.prefix=uploads/videos").run { ctx ->
            assertTrue(messages(ctx.startupFailure).any { "key-prefix" in it }, messages(ctx.startupFailure).toString())
        }
    }

    private fun enabled() = runner.withPropertyValues("skeleton.video.enabled=true")
        .withBean(VideoStore::class.java, { FakeVideoStore() })
        .withBean(VideoWorkerStore::class.java, { object : VideoWorkerStore {} })
}
