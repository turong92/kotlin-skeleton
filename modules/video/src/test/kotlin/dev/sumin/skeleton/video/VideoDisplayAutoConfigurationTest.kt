package dev.sumin.skeleton.video

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.upload.UploadImageState
import dev.sumin.skeleton.common.upload.UploadImages
import dev.sumin.skeleton.storage.PresignedStorage
import dev.sumin.skeleton.storage.image.AnimatedDisplayConverter
import dev.sumin.skeleton.storage.image.AnimatedDisplays
import dev.sumin.skeleton.storage.image.ImageProperties
import dev.sumin.skeleton.storage.image.ImageRow
import dev.sumin.skeleton.storage.image.ImageStatusStore
import dev.sumin.skeleton.storage.image.StoredVariant
import java.lang.reflect.Proxy
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** `video` 와 `storage-image` 가 둘 다 켜졌을 때만 움직이는 표시본 변환이 선다 - 한쪽만 켜면 서로 모르고 지금처럼 동작한다 */
class VideoDisplayAutoConfigurationTest {
    private val hash = "cd".repeat(32)
    private val rows = mutableMapOf<String, ImageRow>()

    private val imageStore: ImageStatusStore = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ImageStatusStore::class.java)) { _, m, args ->
        when (m.name) {
            "find" -> rows[args[0] as String]
            "replaceVariant" -> true
            else -> error("not used: ${m.name}")
        }
    } as ImageStatusStore

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(VideoAutoConfiguration::class.java, VideoDisplayAutoConfiguration::class.java))
        .withPropertyValues("skeleton.video.enabled=true", "skeleton.video.playback.secret=${PlaybackFixtures.SECRET}")
        .withBean(TimeProvider::class.java, { TimeProvider { Instant.parse("2026-11-04T00:00:00Z") } })
        .withBean(PresignedStorage::class.java, { FakeStorage().also { it.objects["uploads/7/g/x.gif"] = 4000 } })
        .withBean(VideoStore::class.java, { FakeVideoStore() })
        .withBean(VideoWorkerStore::class.java, { FakeVideoWorkerStore() })
        .withBean(VideoFollowUps::class.java, { FakeFollowUps() })
        .withBean(VideoTransactions::class.java, { FakeTransactions() })
        .withBean(DisplayJobStore::class.java, { FakeDisplayJobStore() })

    private val worker = VideoWorker(1, "anim", setOf(VideoLane.ANIMATED), Instant.EPOCH, null, null)

    @Test
    fun `with the image module on, a requested conversion becomes a job that an animated-lane worker can take`() {
        rows[hash] = ImageRow(
            hash, "uploads/7/g/x.gif", UploadImageState.READY, "gif", 60, 40, true,
            mapOf(UploadImages.THUMB to StoredVariant(60, 40, "jpg"), UploadImages.DISPLAY to StoredVariant(60, 40, "gif")),
        )
        runner.withBean(AnimatedDisplays::class.java, { AnimatedDisplays(imageStore, ImageProperties(enabled = true)) }).run { ctx ->
            assertNull(ctx.startupFailure, ctx.startupFailure?.toString())
            ctx.getBean(AnimatedDisplayConverter::class.java).requestConversion(hash, "uploads/7/g/x.gif")

            val job = assertNotNull(ctx.getBean(VideoJobs::class.java).claim(worker, "w1", null))
            assertEquals(hash, job.jobId)
            assertEquals(VideoLane.ANIMATED, job.lane)
        }
    }

    @Test
    fun `without the image module's display nothing is wired and the video lanes stand as before`() {
        runner.run { ctx ->
            assertNull(ctx.startupFailure)
            assertTrue(ctx.getBeansOfType(VideoDisplayJobs::class.java).isEmpty())
            assertTrue(ctx.getBeansOfType(AnimatedDisplayConverter::class.java).isEmpty())
            assertNull(ctx.getBean(VideoJobs::class.java).claim(worker, "w1", null))
        }
    }

    @Test
    fun `video off means no conversion even when the image module is on`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(VideoAutoConfiguration::class.java, VideoDisplayAutoConfiguration::class.java))
            .withBean(AnimatedDisplays::class.java, { AnimatedDisplays(imageStore, ImageProperties(enabled = true)) })
            .withBean(DisplayJobStore::class.java, { FakeDisplayJobStore() })
            .run { ctx -> assertTrue(ctx.getBeansOfType(AnimatedDisplayConverter::class.java).isEmpty()) }
    }
}
