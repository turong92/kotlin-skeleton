package dev.sumin.skeleton.video

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.storage.PresignedStorage
import dev.sumin.skeleton.storage.image.AnimatedDisplayConverter
import dev.sumin.skeleton.storage.image.AnimatedDisplays
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 움직이는 GIF · WebP 표시본 변환 - `video` 와 `storage-image` 가 **둘 다** 켜졌을 때만 서고, 한쪽만 켜면 서로를 모른 채 지금처럼 동작한다(모듈 기본값은 꺼짐 그대로).
 * `storage-image` 는 `video` 를 모른다: 그쪽이 낸 확장 지점([AnimatedDisplayConverter])을 여기서 채워 일꾼의 ANIMATED 차선([VideoDisplayJobs])으로 넘긴다.
 * 변환이 끝나기 전에도 · 실패해도 사본 display 가 그대로 쓰인다
 */
@AutoConfiguration(
    after = [VideoAutoConfiguration::class],
    afterName = ["dev.sumin.skeleton.video.jdbc.VideoJdbcAutoConfiguration", "dev.sumin.skeleton.storage.image.StorageImageAutoConfiguration"],
)
@ConditionalOnProperty(prefix = "skeleton.video", name = ["enabled"], havingValue = "true")
class VideoDisplayAutoConfiguration {
    /** 중첩이라 `storage-image` 가 클래스패스에 없으면 이 안의 타입은 읽히지도 않는다 */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(AnimatedDisplays::class)
    @ConditionalOnBean(AnimatedDisplays::class, DisplayJobStore::class)
    class Wiring {
        @Bean
        @ConditionalOnMissingBean
        fun videoDisplayTarget(displays: AnimatedDisplays): DisplayTarget = object : DisplayTarget {
            override fun folder(keyHash: String) = displays.folder(keyHash)
            override fun copyExtension(keyHash: String) = displays.copyExtension(keyHash)
            override fun use(keyHash: String, ext: String) = displays.use(keyHash, ext)
        }

        @Bean
        @ConditionalOnMissingBean
        fun videoDisplayJobs(props: VideoProperties, store: DisplayJobStore, target: DisplayTarget, storage: PresignedStorage, time: TimeProvider): VideoDisplayJobs =
            VideoDisplayJobs(props, store, target, storage, time)

        @Bean
        @ConditionalOnMissingBean(AnimatedDisplayConverter::class)
        fun videoAnimatedDisplayConverter(jobs: VideoDisplayJobs): AnimatedDisplayConverter = AnimatedDisplayConverter { keyHash, objectKey -> jobs.enqueue(keyHash, objectKey) }
    }
}
