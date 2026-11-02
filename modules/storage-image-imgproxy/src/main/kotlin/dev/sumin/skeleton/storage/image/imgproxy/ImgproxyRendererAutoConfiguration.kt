package dev.sumin.skeleton.storage.image.imgproxy

import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.PresignedDownloadRequest
import dev.sumin.skeleton.storage.PresignedStorage
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order

/**
 * `skeleton.storage.image.imgproxy.enabled=true` 일 때만 선다(**기본 꺼짐**). 기본 엔진(`storage-image-imageio`)보다 **먼저** 종류를 맡는다 - 같이 두면 imgproxy 가 이미지를 맡고, 기본 엔진은 쓰이지 않는다.
 * 주소 · key · salt 가 비면 기동 실패([ImgproxyProperties]), 저장소(`PresignedStorage`)가 없으면 기동 실패(원본 서명 주소를 만들 곳이 없다).
 */
@AutoConfiguration(afterName = ["dev.sumin.skeleton.storage.s3.S3StorageAutoConfiguration"])
@ConditionalOnProperty(prefix = "skeleton.storage.image.imgproxy", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(ImgproxyProperties::class)
class ImgproxyRendererAutoConfiguration {
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnMissingBean
    fun imgproxyRenderer(properties: ImgproxyProperties, storage: ObjectProvider<PresignedStorage>): ImgproxyRenderer {
        val signer = storage.getIfAvailable { error("skeleton.storage.image.imgproxy.enabled=true needs a PresignedStorage bean to sign the original's address - add storage-s3") }
        return ImgproxyRenderer(properties) { key: ObjectKey, ttl -> signer.presignDownload(PresignedDownloadRequest(key, expiresIn = ttl)).url }
    }
}
