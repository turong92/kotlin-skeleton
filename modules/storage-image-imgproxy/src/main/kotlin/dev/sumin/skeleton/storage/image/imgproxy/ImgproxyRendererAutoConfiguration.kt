package dev.sumin.skeleton.storage.image.imgproxy

import dev.sumin.skeleton.storage.PresignedStorage
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.core.env.Environment
import org.springframework.core.annotation.Order

/**
 * `skeleton.storage.image.imgproxy.enabled=true` 일 때만 선다(**기본 꺼짐**). 기본 엔진(`storage-image-imageio`)보다 **먼저** 종류를 맡는다 - 같이 두면 imgproxy 가 이미지를 맡고, 기본 엔진은 쓰이지 않는다.
 * 주소 · key · salt 가 비면 기동 실패([ImgproxyProperties]). 원본 주소는 [ImgproxySourceResolver] 가 만든다 - 앱이 같은 타입의 빈을 두면 그것을 쓴다(다른 저장소).
 * 기본은 저장소(`PresignedStorage`)의 서명 주소 - 저장소가 없으면 기동 실패. `source=S3` 면 버킷(`s3-bucket`, 비면 `skeleton.storage-s3.bucket`)이 있어야 한다.
 */
@AutoConfiguration(afterName = ["dev.sumin.skeleton.storage.s3.S3StorageAutoConfiguration"])
@ConditionalOnProperty(prefix = "skeleton.storage.image.imgproxy", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(ImgproxyProperties::class)
class ImgproxyRendererAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun imgproxySourceResolver(properties: ImgproxyProperties, storage: ObjectProvider<PresignedStorage>, environment: Environment): ImgproxySourceResolver = when (properties.source) {
        ImgproxyProperties.Source.SIGNED_URL ->
            SignedUrlSourceResolver(storage.getIfAvailable { error("skeleton.storage.image.imgproxy.enabled=true needs a PresignedStorage bean to sign the original's address - add storage-s3 (or set source=S3, or provide an ImgproxySourceResolver bean)") })
        ImgproxyProperties.Source.S3 -> {
            val bucket = properties.s3Bucket.ifBlank { environment.getProperty("skeleton.storage-s3.bucket").orEmpty() }
            check(bucket.isNotBlank()) { "skeleton.storage.image.imgproxy.source=S3 needs skeleton.storage.image.imgproxy.s3-bucket (or skeleton.storage-s3.bucket) - the bucket imgproxy reads the originals from" }
            S3SourceResolver(bucket, properties.s3KeyPrefix)
        }
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean
    fun imgproxyRenderer(properties: ImgproxyProperties, sources: ImgproxySourceResolver): ImgproxyRenderer = ImgproxyRenderer(properties, sources = sources)
}
