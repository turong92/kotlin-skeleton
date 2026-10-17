package dev.sumin.skeleton.storage.web

import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.time.asClock
import dev.sumin.skeleton.common.upload.PrivateUploadAccess
import dev.sumin.skeleton.common.upload.PublicUploadPurger
import dev.sumin.skeleton.common.upload.PublicUploads
import dev.sumin.skeleton.common.upload.UploadedKeyUsage
import dev.sumin.skeleton.common.upload.UploadedKeyUsages
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.common.web.InMemoryFixedWindowRateLimitStore
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import dev.sumin.skeleton.common.web.RateLimitStore
import dev.sumin.skeleton.storage.ObjectStorage
import dev.sumin.skeleton.storage.PresignedStorage
import dev.sumin.skeleton.storage.StorageAutoConfiguration
import dev.sumin.skeleton.storage.StorageFileValidator
import dev.sumin.skeleton.storage.StorageGuestCaptcha
import dev.sumin.skeleton.storage.StorageGuestCaptchaMode
import dev.sumin.skeleton.storage.StorageGuestCleanup
import dev.sumin.skeleton.storage.StorageGuestUploads
import dev.sumin.skeleton.storage.StorageKeySchemes
import dev.sumin.skeleton.storage.StoragePrivateUploadAccess
import dev.sumin.skeleton.storage.StorageProperties
import dev.sumin.skeleton.storage.StoragePublicUploads
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.TaskScheduler
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * `/api/v1/storage` 아래 엔드포인트 - 서블릿 웹 앱 · Spring Security(호출자) 가 클래스패스에 있고 · [PresignedStorage] 빈이 있을 때만.
 * 저장소 어댑터(`storage-s3`)보다 뒤에 평가한다 - 버킷을 정하지 않으면 저장소 빈이 없고 이 엔드포인트도 없다.
 */
@AutoConfiguration(after = [StorageAutoConfiguration::class], afterName = ["dev.sumin.skeleton.storage.s3.S3StorageAutoConfiguration", "dev.sumin.skeleton.storage.StorageCaptchaAutoConfiguration"])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
@ConditionalOnProperty(prefix = "skeleton.storage.web", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class StorageWebAutoConfiguration {
    /** 한도 - platform 의 [RateLimitStore] 빈이 있으면 그것(Redis), 없으면 인메모리. `rate-limit.enabled` 와 무관하게 `guest.enabled` 면 늘 걸린다 */
    @Bean
    @ConditionalOnMissingBean
    fun storageGuestUploads(
        properties: StorageProperties,
        projects: ObjectProvider<ProjectRegistry>,
        stores: ObjectProvider<RateLimitStore>,
        captchas: ObjectProvider<StorageGuestCaptcha>,
        time: ObjectProvider<TimeProvider>,
    ): StorageGuestUploads {
        check(!(properties.web.guest.enabled && properties.web.guest.captcha == StorageGuestCaptchaMode.REQUIRED) || captchas.getIfAvailable() != null) {
            "skeleton.storage.web.guest.captcha=REQUIRED needs a StorageGuestCaptcha bean (add captcha-turnstile and enable it, or define your own)"
        }
        val fallback by lazy { InMemoryFixedWindowRateLimitStore() }
        return StorageGuestUploads(StorageKeySchemes.create(properties, projects.getIfAvailable()), { stores.getIfAvailable { fallback } }, { captchas.getIfAvailable() }, time.getIfAvailable { TimeProvider.systemUtc() }.asClock())
    }

    @Bean
    @ConditionalOnBean(PresignedStorage::class)
    @ConditionalOnMissingBean
    fun storageController(
        storage: PresignedStorage,
        validator: StorageFileValidator,
        properties: StorageProperties,
        projects: ObjectProvider<ProjectRegistry>,
        guestUploads: StorageGuestUploads,
        clientIps: ObjectProvider<ClientIps>,
    ): StorageController = StorageController(storage, validator, properties.web, StorageKeySchemes.create(properties, projects.getIfAvailable()), guestUploads, clientIps.getIfAvailable { ClientIps() })

    /** 켜져 있을 때만 비회원 발급 경로 하나를 익명에 연다 - 다른 경로는 그대로 로그인이 필요하다 */
    @Bean
    @ConditionalOnMissingBean(name = ["storageGuestPublicEndpointContributor"])
    @ConditionalOnProperty(prefix = "skeleton.storage.web.guest", name = ["enabled"], havingValue = "true")
    fun storageGuestPublicEndpointContributor(): PublicEndpointContributor =
        PublicEndpointContributor { registry -> registry.add("POST", "/api/v1/storage/guest/presign") }

    /** 공개 영역 - `public.enabled=true` 일 때만. 꺼져 있으면 빈이 없어 board 는 지금처럼 키만 낸다 */
    @Bean
    @ConditionalOnBean(PresignedStorage::class)
    @ConditionalOnMissingBean(PublicUploads::class)
    @ConditionalOnProperty(prefix = "skeleton.storage.web.public", name = ["enabled"], havingValue = "true")
    fun publicUploads(
        storage: PresignedStorage,
        properties: StorageProperties,
        projects: ObjectProvider<ProjectRegistry>,
        purgers: ObjectProvider<PublicUploadPurger>,
    ): PublicUploads = StoragePublicUploads(storage, StorageKeySchemes.create(properties, projects.getIfAvailable())) { purgers.getIfAvailable() }

    @Bean
    @ConditionalOnBean(PresignedStorage::class)
    @ConditionalOnMissingBean(PrivateUploadAccess::class)
    @ConditionalOnProperty(prefix = "skeleton.storage.web.public", name = ["enabled"], havingValue = "true")
    fun privateUploadAccess(storage: PresignedStorage, properties: StorageProperties, projects: ObjectProvider<ProjectRegistry>): PrivateUploadAccess =
        StoragePrivateUploadAccess(storage, StorageKeySchemes.create(properties, projects.getIfAvailable()))

    /** 붙지 않은 비회원 업로드 회수 - `guest.cleanup.enabled=true` 일 때만. 주기는 [StorageGuestCleanupJob] (scheduler 모듈이 있어야 돈다) */
    @Bean
    @ConditionalOnBean(ObjectStorage::class)
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "skeleton.storage.web.guest.cleanup", name = ["enabled"], havingValue = "true")
    fun storageGuestCleanup(
        storage: ObjectStorage,
        properties: StorageProperties,
        usage: ObjectProvider<UploadedKeyUsage>,
        time: ObjectProvider<TimeProvider>,
        projects: ObjectProvider<ProjectRegistry>,
    ): StorageGuestCleanup = StorageGuestCleanup(storage, StorageKeySchemes.create(properties, projects.getIfAvailable()), { UploadedKeyUsages.union(usage.orderedStream().toList()) }, time.getIfAvailable { TimeProvider.systemUtc() }, projects.getIfAvailable())

    /**
     * 한 시간마다(처음은 5분 뒤) 회수를 돌린다 - `scheduler` 모듈의 `skeletonTaskScheduler` 빈이 있어야 돈다 (없으면 경고만 하고 돌지 않는다).
     * 락 없이 인스턴스마다 돌아도 안전하다: 지우기는 멱등이고 붙은 키는 건드리지 않는다.
     */
    @Bean
    @ConditionalOnBean(StorageGuestCleanup::class)
    @ConditionalOnMissingBean(name = ["storageGuestCleanupSchedule"])
    fun storageGuestCleanupSchedule(
        cleanup: StorageGuestCleanup,
        @Qualifier("skeletonTaskScheduler") schedulers: ObjectProvider<TaskScheduler>,
    ): SmartInitializingSingleton = SmartInitializingSingleton {
        val log = LoggerFactory.getLogger(StorageGuestCleanup::class.java)
        val scheduler = schedulers.getIfAvailable()
        if (scheduler == null) {
            log.warn("skeleton.storage.web.guest.cleanup.enabled=true but there is no skeletonTaskScheduler bean (add the scheduler module) - unattached guest uploads are not cleaned")
        } else {
            scheduler.scheduleWithFixedDelay(
                {
                    try {
                        cleanup.run().takeIf { it > 0 }?.let { log.info("storage guest cleanup deleted {} unattached upload(s)", it) }
                    } catch (e: Exception) {
                        log.warn("storage guest cleanup failed: {}", e.toString())
                    }
                },
                Instant.now().plus(Duration.ofMinutes(5)),
                Duration.ofHours(1),
            )
        }
    }
}
