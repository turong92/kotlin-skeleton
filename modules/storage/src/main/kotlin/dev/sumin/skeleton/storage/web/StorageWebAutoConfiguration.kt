package dev.sumin.skeleton.storage.web

import dev.sumin.skeleton.storage.PresignedStorage
import dev.sumin.skeleton.storage.StorageAutoConfiguration
import dev.sumin.skeleton.storage.StorageFileValidator
import dev.sumin.skeleton.storage.StorageProperties
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * `/api/v1/storage` 아래 엔드포인트 — 서블릿 웹 앱 · Spring Security(호출자) 가 클래스패스에 있고 · [PresignedStorage] 빈이 있을 때만.
 * 저장소 어댑터(`storage-s3`)보다 뒤에 평가한다 — 버킷을 정하지 않으면 저장소 빈이 없고 이 엔드포인트도 없다.
 */
@AutoConfiguration(after = [StorageAutoConfiguration::class], afterName = ["dev.sumin.skeleton.storage.s3.S3StorageAutoConfiguration"])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
@ConditionalOnProperty(prefix = "skeleton.storage.web", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class StorageWebAutoConfiguration {
    @Bean
    @ConditionalOnBean(PresignedStorage::class)
    @ConditionalOnMissingBean
    fun storageController(
        storage: PresignedStorage,
        validator: StorageFileValidator,
        properties: StorageProperties,
    ): StorageController = StorageController(storage, validator, properties.web)
}
