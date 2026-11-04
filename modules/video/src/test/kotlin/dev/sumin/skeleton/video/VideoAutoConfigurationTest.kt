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
    fun `enabled without an object store stays off instead of stopping the start`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(VideoAutoConfiguration::class.java))
            .withBean(TimeProvider::class.java, { TimeProvider.systemUtc() })
            .withPropertyValues("skeleton.video.enabled=true")
            .run { ctx ->
                assertNull(ctx.startupFailure)
                assertTrue(ctx.getBeansOfType(VideoUploads::class.java).isEmpty())
            }
    }

    @Test
    fun `enabled without a VideoStore stops the start and names the module that brings one`() {
        runner.withPropertyValues("skeleton.video.enabled=true").withBean(VideoWorkerStore::class.java, { FakeVideoWorkerStore() }).run { ctx ->
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
    fun `enabled without the follow-up port or the transaction port stops the start and names the module that brings them`() {
        runner.withPropertyValues("skeleton.video.enabled=true")
            .withBean(VideoStore::class.java, { FakeVideoStore() }).withBean(VideoWorkerStore::class.java, { FakeVideoWorkerStore() })
            .withBean(VideoTransactions::class.java, { FakeTransactions() })
            .run { ctx -> assertTrue(messages(ctx.startupFailure).any { "VideoFollowUps" in it && "video-jdbc" in it }, messages(ctx.startupFailure).toString()) }
    }

    @Test
    fun `enabled stands with the job services, a waker that does nothing, silent events and the deploy guard`() {
        enabled().run { ctx ->
            assertNull(ctx.startupFailure)
            assertEquals(1, ctx.getBeansOfType(VideoJobs::class.java).size)
            assertEquals(1, ctx.getBeansOfType(VideoWorkerService::class.java).size)
            assertEquals(1, ctx.getBeansOfType(VideoMaintenance::class.java).size)
            assertEquals(1, ctx.getBeansOfType(VideoFinalizer::class.java).size)
            assertSame(NoopWaker, ctx.getBean(VideoWaker::class.java))
            assertSame(VideoEvents.NONE, ctx.getBean(VideoEvents::class.java))
            assertTrue(ctx.getBeansOfType(dev.sumin.skeleton.common.deploy.DeployGuard::class.java).values.any { it is VideoDeployGuard })
        }
    }

    @Test
    fun `an app that brings its own events and waker replaces the defaults`() {
        val events = RecordingEvents()
        val waker = object : VideoWaker { override val enabled = true; override fun wake() = WakeTicket("x") }
        enabled().withBean(VideoEvents::class.java, { events }).withBean(VideoWaker::class.java, { waker }).run { ctx ->
            assertSame(events, ctx.getBean(VideoEvents::class.java))
            assertSame(waker, ctx.getBean(VideoWaker::class.java))
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
    fun `enabled without the playback secret, or with one shorter than 32 characters, stops the start and names the setting and the variable`() {
        listOf(bare(), bare().withPropertyValues("skeleton.video.playback.secret=short")).forEach { r ->
            r.run { ctx ->
                val all = messages(ctx.startupFailure).joinToString(" | ")
                assertTrue("skeleton.video.playback.secret" in all && "VIDEO_PLAYBACK_SECRET" in all, all)
                assertTrue("short" !in all.replace("shorter", ""), "the value is never echoed")
            }
        }
    }

    @Test
    fun `enabled with the secret stands with the library, the playlists, the default playback and the worker inputs`() {
        enabled().run { ctx ->
            assertNull(ctx.startupFailure)
            assertEquals(1, ctx.getBeansOfType(VideoLibrary::class.java).size)
            assertEquals(1, ctx.getBeansOfType(VideoPlaylists::class.java).size)
            assertTrue(ctx.getBean(VideoPlayback::class.java) is StorageVideoPlayback)
            assertTrue(ctx.getBean(VideoWorkerInputs::class.java) is StorageVideoWorkerInputs)
        }
    }

    @Test
    fun `an app that brings its own playback replaces the default - a vendor stream plugs in here`() {
        val own = VideoPlayback { _, _ -> PlaybackGrant("s", null, null, null, "c", 1, 1, 1, false, null) }
        enabled().withBean(VideoPlayback::class.java, { own }).run { ctx -> assertSame(own, ctx.getBean(VideoPlayback::class.java)) }
    }

    @Test
    fun `a video prefix inside the upload area stops the start`() {
        enabled().withPropertyValues("skeleton.video.prefix=uploads/videos").run { ctx ->
            assertTrue(messages(ctx.startupFailure).any { "key-prefix" in it }, messages(ctx.startupFailure).toString())
        }
    }

    private fun enabled() = bare().withPropertyValues("skeleton.video.playback.secret=${PlaybackFixtures.SECRET}")

    private fun bare() = runner.withPropertyValues("skeleton.video.enabled=true")
        .withBean(VideoStore::class.java, { FakeVideoStore() })
        .withBean(VideoWorkerStore::class.java, { FakeVideoWorkerStore() })
        .withBean(VideoFollowUps::class.java, { FakeFollowUps() })
        .withBean(VideoTransactions::class.java, { FakeTransactions() })
}
