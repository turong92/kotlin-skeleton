package dev.sumin.skeleton.storage.image.ffmpeg

import dev.sumin.skeleton.jobqueue.jdbc.JobHandler
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.storage.ObjectStorage
import dev.sumin.skeleton.storage.image.AnimatedDisplayConverter
import dev.sumin.skeleton.storage.image.AnimatedDisplays
import dev.sumin.skeleton.storage.image.ImageProperties
import dev.sumin.skeleton.storage.image.ImageStatusStore
import java.time.Instant
import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class FfmpegAutoConfigurationTest {
    /** 켜졌을 때 이 모듈이 기대는 것들 - storage-image(상태 줄 · 표시본) · storage-s3(저장소) · job-queue-jdbc(큐) 자리 */
    @Configuration(proxyBeanMethods = false)
    class Surroundings {
        @Bean fun storage(): ObjectStorage = MemoryStorage()
        @Bean fun store(): ImageStatusStore = MemoryImageStore()
        @Bean fun displays(store: ImageStatusStore) = AnimatedDisplays(store, ImageProperties())
        @Bean fun queue(): JobQueue = object : JobQueue {
            override fun enqueue(type: String, payloadJson: String, runAt: Instant?, maxAttempts: Int?): Long = 1L
        }
    }

    private val runner = ApplicationContextRunner()
        .withUserConfiguration(Surroundings::class.java)
        .withConfiguration(AutoConfigurations.of(FfmpegAnimatedAutoConfiguration::class.java))

    @Test
    fun `모듈을 넣기만 하면 아무것도 서지 않는다 - 기본 꺼짐`() {
        runner.run { ctx ->
            assertThat(ctx).doesNotHaveBean(AnimatedDisplayConverter::class.java)
            assertThat(ctx).doesNotHaveBean(FfmpegAnimatedJobHandler::class.java)
            assertThat(ctx).doesNotHaveBean(FfmpegProperties::class.java)
        }
    }

    @Test
    fun `켜면 변환 확장 지점과 작업 처리기가 선다`() {
        runner.withPropertyValues("skeleton.storage.image.ffmpeg.enabled=true", "skeleton.storage.image.ffmpeg.crf=30").run { ctx ->
            assertThat(ctx).hasSingleBean(AnimatedDisplayConverter::class.java)
            assertThat(ctx.getBean(AnimatedDisplayConverter::class.java)).isInstanceOf(FfmpegAnimatedDisplayConverter::class.java)
            assertThat(ctx.getBeansOfType(JobHandler::class.java).values.map { it.type }).containsExactly(FFMPEG_JOB_TYPE)
            assertThat(ctx.getBean(FfmpegProperties::class.java).crf).isEqualTo(30)
        }
    }

    @Test
    fun `앱이 자기 변환기를 두었으면 그것이 이긴다`() {
        val own = AnimatedDisplayConverter { _, _ -> }
        runner.withPropertyValues("skeleton.storage.image.ffmpeg.enabled=true")
            .withBean("ownConverter", AnimatedDisplayConverter::class.java, { own })
            .run { ctx -> assertThat(ctx.getBean(AnimatedDisplayConverter::class.java)).isSameAs(own) }
    }

    @Test
    fun `같은 확장 지점을 채우는 video 의 자동설정보다 먼저 평가돼 둘 다 켜져 있으면 앱 안 ffmpeg 가 이긴다`() {
        val annotation = FfmpegAnimatedAutoConfiguration::class.java.getAnnotation(AutoConfiguration::class.java)
        assertThat(annotation.beforeName).contains("dev.sumin.skeleton.video.VideoDisplayAutoConfiguration")
    }

    @Test
    fun `큐가 없으면 켠 채로 기동하지 못한다 - 변환을 약속하고 뒷일을 할 곳이 없다`() {
        ApplicationContextRunner()
            .withBean(ObjectStorage::class.java, { MemoryStorage() })
            .withBean(ImageStatusStore::class.java, { MemoryImageStore() })
            .withBean(AnimatedDisplays::class.java, { AnimatedDisplays(MemoryImageStore(), ImageProperties()) })
            .withConfiguration(AutoConfigurations.of(FfmpegAnimatedAutoConfiguration::class.java))
            .withPropertyValues("skeleton.storage.image.ffmpeg.enabled=true")
            .run { ctx -> assertThat(ctx).hasFailed().getFailure().hasStackTraceContaining("JobQueue") }
    }
}
