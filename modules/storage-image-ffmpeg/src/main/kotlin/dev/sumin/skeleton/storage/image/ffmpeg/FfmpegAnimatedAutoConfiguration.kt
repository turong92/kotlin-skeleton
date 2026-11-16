package dev.sumin.skeleton.storage.image.ffmpeg

import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.storage.ObjectStorage
import dev.sumin.skeleton.storage.image.AnimatedDisplayConverter
import dev.sumin.skeleton.storage.image.AnimatedDisplays
import dev.sumin.skeleton.storage.image.ImageStatusStore
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * `skeleton.storage.image.ffmpeg.enabled=true` 일 때만 선다(**기본 꺼짐**) - `storage-image` 의 확장 지점([AnimatedDisplayConverter])을 채워 움직이는 GIF · WebP 의 표시본을 앱 안 ffmpeg 로 바꾼다.
 * 켜면 `storage-image`(켜짐) · 저장소(`storage-s3`) · `job-queue-jdbc` 가 있어야 한다 - 없으면 기동이 실패하고 빠진 빈 이름을 말한다(변환을 약속하고 뒷일을 할 곳이 없다).
 *
 * **`video` 와 같은 자리를 채운다** - 둘 다 켜져 있으면 이 모듈이 이긴다: [AnimatedDisplayConverter] 빈은 먼저 만들어진 쪽이 이기고(`@ConditionalOnMissingBean`),
 * 이 자동설정이 `video` 의 [VIDEO_DISPLAY] 보다 먼저 평가된다(`beforeName`). 앱이 자기 [AnimatedDisplayConverter] 빈을 두면 그것이 둘 다 이긴다.
 */
@AutoConfiguration(
    beforeName = [FfmpegAnimatedAutoConfiguration.VIDEO_DISPLAY],
    afterName = ["dev.sumin.skeleton.storage.s3.S3StorageAutoConfiguration", "dev.sumin.skeleton.storage.image.StorageImageAutoConfiguration"],
)
@ConditionalOnProperty(prefix = "skeleton.storage.image.ffmpeg", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(FfmpegProperties::class)
class FfmpegAnimatedAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun ffmpegAnimatedConversion(storage: ObjectStorage, store: ImageStatusStore, displays: AnimatedDisplays, props: FfmpegProperties): AnimatedConversion =
        AnimatedConversion(storage, store, displays, props)

    /** 큐 워커가 `JobHandler` 빈을 모아 종류로 찾는다 */
    @Bean
    @ConditionalOnMissingBean
    fun ffmpegAnimatedJobHandler(conversion: AnimatedConversion): FfmpegAnimatedJobHandler = FfmpegAnimatedJobHandler(conversion)

    @Bean
    @ConditionalOnMissingBean(AnimatedDisplayConverter::class)
    fun ffmpegAnimatedDisplayConverter(queue: JobQueue, props: FfmpegProperties): AnimatedDisplayConverter = FfmpegAnimatedDisplayConverter(queue, props)

    companion object {
        /** `video` 모듈의 같은 확장 지점 구현 - 이 모듈은 `video` 를 모르므로 이름으로만 가리킨다 */
        const val VIDEO_DISPLAY = "dev.sumin.skeleton.video.VideoDisplayAutoConfiguration"
    }
}
