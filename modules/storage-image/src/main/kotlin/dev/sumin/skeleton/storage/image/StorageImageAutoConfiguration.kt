package dev.sumin.skeleton.storage.image

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.upload.UploadImages
import dev.sumin.skeleton.storage.PresignedStorage
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * 이미지 변환본 - `skeleton.storage.image.enabled=true` 일 때만(**기본 꺼짐**). 켜면 엔진([ImageRenderer], `storage-image-imageio`)과 상태 저장소([ImageStatusStore], `storage-image-jdbc`)가
 * 있어야 한다 - 없으면 기동 실패이고 메시지가 어느 모듈을 얹을지 말한다. 저장소([PresignedStorage], `storage-s3`)가 없으면 변환본도 없다.
 */
@AutoConfiguration(
    afterName = [
        "dev.sumin.skeleton.storage.s3.S3StorageAutoConfiguration",
        "dev.sumin.skeleton.storage.image.imageio.ImageIoRendererAutoConfiguration",
        "dev.sumin.skeleton.storage.image.jdbc.StorageImageJdbcAutoConfiguration",
    ],
)
@ConditionalOnProperty(prefix = "skeleton.storage.image", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(ImageProperties::class)
class StorageImageAutoConfiguration {
    /** 엔진 빈이 없을 때만 서는 자리 표시 - 만들어지는 순간 기동이 실패한다 */
    @Bean
    @ConditionalOnMissingBean(ImageRenderer::class)
    fun missingImageRenderer(): ImageRenderer =
        error("skeleton.storage.image.enabled=true needs an image engine (an ImageRenderer bean) - add storage-image-imageio (the default engine, no extra server)")

    @Bean
    @ConditionalOnMissingBean(ImageStatusStore::class)
    fun missingImageStatusStore(): ImageStatusStore =
        error("skeleton.storage.image.enabled=true needs a status store (an ImageStatusStore bean) - add storage-image-jdbc and its migration")

    @Bean
    @ConditionalOnMissingBean
    fun imageRenderers(engines: ObjectProvider<ImageRenderer>): ImageRenderers = ImageRenderers(engines.orderedStream().toList())

    @Bean
    @ConditionalOnMissingBean
    fun imageHeaderInspector(renderers: ImageRenderers, properties: ImageProperties): ImageHeaderInspector = ImageHeaderInspector(renderers, properties)

    @Bean
    @ConditionalOnBean(PresignedStorage::class)
    @ConditionalOnMissingBean(UploadImages::class)
    fun storageImages(storage: PresignedStorage, store: ImageStatusStore, tasks: ObjectProvider<ImageVariantTasks>, renderers: ImageRenderers, properties: ImageProperties): StorageImages =
        StorageImages(storage, store, tasks.getIfAvailable { error("skeleton.storage.image.enabled=true needs an ImageVariantTasks bean (storage-image-jdbc provides it on the job queue)") }, renderers, properties)

    @Bean
    @ConditionalOnBean(StorageImages::class)
    @ConditionalOnMissingBean
    fun imageCompletionListener(images: StorageImages, renderers: ImageRenderers): ImageCompletionListener = ImageCompletionListener(images, renderers)

    @Bean
    @ConditionalOnBean(PresignedStorage::class)
    @ConditionalOnMissingBean
    fun imageVariantProcessor(storage: PresignedStorage, store: ImageStatusStore, renderers: ImageRenderers, properties: ImageProperties, time: ObjectProvider<TimeProvider>): ImageVariantProcessor =
        ImageVariantProcessor(storage, store, renderers, properties, time.getIfAvailable { TimeProvider.systemUtc() })
}
