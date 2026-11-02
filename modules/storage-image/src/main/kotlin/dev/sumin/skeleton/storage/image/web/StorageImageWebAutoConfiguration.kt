package dev.sumin.skeleton.storage.image.web

import dev.sumin.skeleton.common.upload.UploadImages
import dev.sumin.skeleton.storage.StorageKeyOwnership
import dev.sumin.skeleton.storage.image.ImageProperties
import dev.sumin.skeleton.storage.image.StorageImageAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/** `POST /api/v1/storage/images` - 서블릿 웹 앱 · Spring Security(호출자) 가 있고 이미지 모듈이 서 있을 때만. `skeleton.storage.image.http.enabled=false` 면 빠진다 */
@AutoConfiguration(after = [StorageImageAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
@ConditionalOnProperty(prefix = "skeleton.storage.image", name = ["enabled"], havingValue = "true")
@ConditionalOnProperty(prefix = "skeleton.storage.image.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class StorageImageWebAutoConfiguration {
    @Bean
    @ConditionalOnBean(UploadImages::class)
    @ConditionalOnMissingBean
    fun storageImagesController(images: UploadImages, ownership: StorageKeyOwnership, properties: ImageProperties): StorageImagesController =
        StorageImagesController(images, ownership, properties.accessTtl)
}
