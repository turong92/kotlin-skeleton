package dev.sumin.skeleton.video.nooptional

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.storage.PresignedStorage
import dev.sumin.skeleton.video.VideoAutoConfiguration
import dev.sumin.skeleton.video.web.VideoWebAutoConfiguration
import dev.sumin.skeleton.video.VideoFollowUps
import dev.sumin.skeleton.video.VideoStore
import dev.sumin.skeleton.video.VideoTransactions
import dev.sumin.skeleton.video.VideoUploads
import dev.sumin.skeleton.video.VideoWorkerStore
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner

/** 스케줄러 · Spring Security 가 클래스패스에 없어도 영상 올리기가 선다 — 청소는 요청 구동 모드가 맡고, HTTP 경로는 등록되지 않는다 */
class VideoWithoutOptionalModulesTest {
    private fun <T> unused(type: Class<T>): T = type.cast(Proxy.newProxyInstance(javaClass.classLoader, arrayOf(type)) { _, m, _ -> error("not used: ${m.name}") })

    @Test
    fun `video stands without the scheduler and spring security on the classpath`() {
        WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(VideoAutoConfiguration::class.java, VideoWebAutoConfiguration::class.java))
            .withPropertyValues("skeleton.video.enabled=true", "skeleton.video.playback.secret=${"k".repeat(40)}")
            .withBean(TimeProvider::class.java, { TimeProvider { java.time.Instant.now() } })
            .withBean(PresignedStorage::class.java, { unused(PresignedStorage::class.java) })
            .withBean(VideoStore::class.java, { unused(VideoStore::class.java) })
            .withBean(VideoWorkerStore::class.java, { unused(VideoWorkerStore::class.java) })
            .withBean(VideoFollowUps::class.java, { unused(VideoFollowUps::class.java) })
            .withBean(VideoTransactions::class.java, { unused(VideoTransactions::class.java) })
            .run { ctx ->
                assertNull(ctx.startupFailure)
                assertTrue(ctx.getBean(VideoUploads::class.java) != null)
                assertTrue(!ctx.containsBean("videoCleanupJob"), "the scheduled marker needs the scheduler module")
                assertTrue(!ctx.containsBean("videoController"), "HTTP needs spring security")
                assertTrue(!ctx.containsBean("videoWorkerController"), "the worker API needs spring security too")
                assertTrue(!ctx.containsBean("videoPlaylistController") && !ctx.containsBean("videoAdminController"), "the playlist and operator doors need spring security too")
                assertTrue(!ctx.containsBean("videoMonitor"), "the alerts need the alert module")
            }
    }
}
