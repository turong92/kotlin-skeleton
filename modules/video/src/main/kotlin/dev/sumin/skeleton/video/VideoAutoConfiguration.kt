package dev.sumin.skeleton.video

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.wake.OnRequestTask
import dev.sumin.skeleton.common.wake.SimpleOnRequestTask
import dev.sumin.skeleton.storage.PresignedStorage
import dev.sumin.skeleton.storage.StorageProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * 올리기 서비스 · 기본 정책(올린 계정만) · 기본 용량(늘 허용) · 청소. **기본 꺼짐** — `skeleton.video.enabled=true` 로 켠다.
 * 켰는데 저장소 포트([VideoStore] · [VideoWorkerStore] — `video-jdbc` 가 낸다)나 객체 저장소([PresignedStorage])가 없으면 기동 실패, 영상 키 구역이 업로드 · 공개 구역과 겹쳐도 기동 실패다.
 */
@AutoConfiguration(afterName = ["dev.sumin.skeleton.video.jdbc.VideoJdbcAutoConfiguration"])
@EnableConfigurationProperties(VideoProperties::class)
@ConditionalOnProperty(prefix = "skeleton.video", name = ["enabled"], havingValue = "true")
class VideoAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(VideoAccessPolicy::class)
    fun ownerVideoAccessPolicy(): VideoAccessPolicy = OwnerVideoAccessPolicy

    @Bean
    @ConditionalOnMissingBean(VideoQuota::class)
    fun openVideoQuota(): VideoQuota = VideoQuota { _, _, _ -> true }

    @Bean
    @ConditionalOnMissingBean
    fun videoUploads(
        props: VideoProperties,
        stores: ObjectProvider<VideoStore>,
        workerStores: ObjectProvider<VideoWorkerStore>,
        storage: ObjectProvider<PresignedStorage>,
        storageProperties: ObjectProvider<StorageProperties>,
        policy: VideoAccessPolicy,
        quota: VideoQuota,
        time: TimeProvider,
    ): VideoUploads {
        val store = stores.getIfAvailable() ?: error("skeleton.video.enabled=true needs a VideoStore bean (add modules:video-jdbc)")
        workerStores.getIfAvailable() ?: error("skeleton.video.enabled=true needs a VideoWorkerStore bean (add modules:video-jdbc)")
        val objects = storage.getIfAvailable() ?: error("skeleton.video.enabled=true needs a PresignedStorage bean (add modules:storage-s3)")
        props.requireDisjointFrom(storageProperties.getIfAvailable { StorageProperties() }.web)
        return VideoUploads(props, store, objects, policy, quota, time)
    }

    /** 요청 구동 모드(인스턴스 0대 배포)의 깨우기가 부른다 — 멱등 */
    @Bean
    @ConditionalOnMissingBean(name = ["videoCleanupTask"])
    fun videoCleanupTask(uploads: VideoUploads): OnRequestTask = SimpleOnRequestTask("video-cleanup") { uploads.cleanupStaleUploads() }

    /** `scheduler` 모듈이 클래스패스에 있을 때만 - 없으면 이 표시를 읽는 쪽이 없다 */
    @Bean
    @ConditionalOnClass(name = ["dev.sumin.skeleton.scheduler.SkeletonScheduled"])
    @ConditionalOnMissingBean
    fun videoCleanupJob(uploads: VideoUploads): VideoCleanupJob = VideoCleanupJob(uploads)
}
