package dev.sumin.skeleton.video

import dev.sumin.skeleton.common.deploy.DeployGuard
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

/** 켰을 때 있어야 하는 포트 — 한 곳에서 모아 확인해, 없으면 어느 모듈이 내는지 말하며 기동 실패한다 */
class VideoPorts(
    val store: VideoStore,
    val workerStore: VideoWorkerStore,
    val storage: PresignedStorage,
    val followUps: VideoFollowUps,
    val tx: VideoTransactions,
)

/**
 * 올리기 서비스 · 일꾼 일 · 청소 · 기본 정책(올린 계정만) · 기본 용량(늘 허용). **기본 꺼짐** — `skeleton.video.enabled=true` 로 켠다.
 * 켰는데 저장소 포트([VideoStore] · [VideoWorkerStore] · [VideoFollowUps] · [VideoTransactions] — `video-jdbc` 가 낸다)나 객체 저장소([PresignedStorage])가 없으면 기동 실패,
 * 영상 키 구역이 업로드 · 공개 구역과 겹쳐도 기동 실패다.
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
    @ConditionalOnMissingBean(VideoEvents::class)
    fun silentVideoEvents(): VideoEvents = VideoEvents.NONE

    /** 깨우기 구현(`video-cloudrun`)이 없으면 아무것도 안 한다 — 상주 일꾼이 스스로 집는다 */
    @Bean
    @ConditionalOnMissingBean(VideoWaker::class)
    fun noopVideoWaker(): VideoWaker = NoopWaker

    @Bean
    fun videoPorts(
        stores: ObjectProvider<VideoStore>,
        workerStores: ObjectProvider<VideoWorkerStore>,
        storage: ObjectProvider<PresignedStorage>,
        followUps: ObjectProvider<VideoFollowUps>,
        transactions: ObjectProvider<VideoTransactions>,
    ): VideoPorts = VideoPorts(
        store = stores.getIfAvailable() ?: error("skeleton.video.enabled=true needs a VideoStore bean (add modules:video-jdbc)"),
        workerStore = workerStores.getIfAvailable() ?: error("skeleton.video.enabled=true needs a VideoWorkerStore bean (add modules:video-jdbc)"),
        storage = storage.getIfAvailable() ?: error("skeleton.video.enabled=true needs a PresignedStorage bean (add modules:storage-s3)"),
        followUps = followUps.getIfAvailable() ?: error("skeleton.video.enabled=true needs a VideoFollowUps bean (add modules:video-jdbc and modules:job-queue-jdbc)"),
        tx = transactions.getIfAvailable() ?: error("skeleton.video.enabled=true needs a VideoTransactions bean (add modules:video-jdbc)"),
    )

    @Bean
    @ConditionalOnMissingBean
    fun videoKeys(props: VideoProperties, storageProperties: ObjectProvider<StorageProperties>): VideoKeys {
        val web = storageProperties.getIfAvailable { StorageProperties() }.web
        props.requireDisjointFrom(web)
        return VideoKeys(props, web)
    }

    @Bean
    @ConditionalOnMissingBean
    fun videoUploads(props: VideoProperties, ports: VideoPorts, policy: VideoAccessPolicy, quota: VideoQuota, time: TimeProvider): VideoUploads =
        VideoUploads(props, ports.store, ports.storage, policy, quota, time)

    /** 호환 · 사다리 일꾼이 읽을 원본 단 재생 목록 주소 — 기본은 공개 영상의 공개 주소. 비공개 영상은 재생 모듈이 채울 때까지 지원하지 않는다 */
    @Bean
    @ConditionalOnMissingBean(VideoWorkerInputs::class)
    fun publicVideoWorkerInputs(storageProperties: ObjectProvider<StorageProperties>): VideoWorkerInputs = VideoWorkerInputs { video, _ ->
        val pub = storageProperties.getIfAvailable { StorageProperties() }.web.`public`
        check(video.visibility == VideoVisibility.PUBLIC) { "a private video's track input is a signed playlist address - bring a VideoWorkerInputs bean (the playback module does)" }
        check(pub.enabled) { "a public video needs skeleton.storage.web.public.enabled=true to be read by the track workers" }
        pub.absoluteUrl(checkNotNull(video.origKey) { "video without an original tier" })
    }

    @Bean
    @ConditionalOnMissingBean
    fun videoWorkerService(ports: VideoPorts, time: TimeProvider): VideoWorkerService = VideoWorkerService(ports.workerStore, time)

    @Bean
    @ConditionalOnMissingBean
    fun videoWorkerBootstrap(props: VideoProperties, ports: VideoPorts, time: TimeProvider): VideoWorkerBootstrap = VideoWorkerBootstrap(props, ports.workerStore, time)

    @Bean
    @ConditionalOnMissingBean(name = ["videoDeployGuard"])
    fun videoDeployGuard(props: VideoProperties): DeployGuard = VideoDeployGuard(props)

    @Bean
    @ConditionalOnMissingBean
    fun videoJobs(props: VideoProperties, ports: VideoPorts, keys: VideoKeys, inputs: VideoWorkerInputs, waker: VideoWaker, time: TimeProvider): VideoJobs =
        VideoJobs(props, ports.store, ports.storage, keys, inputs, ports.followUps, ports.tx, waker, time)

    @Bean
    @ConditionalOnMissingBean
    fun videoFinalizer(ports: VideoPorts, keys: VideoKeys, events: VideoEvents, time: TimeProvider): VideoFinalizer = VideoFinalizer(ports.store, ports.storage, keys, events, time)

    @Bean
    @ConditionalOnMissingBean
    fun videoWakeRunner(ports: VideoPorts, waker: VideoWaker): VideoWakeRunner = VideoWakeRunner(waker, ports.store)

    @Bean
    @ConditionalOnMissingBean
    fun videoMaintenance(props: VideoProperties, uploads: VideoUploads, ports: VideoPorts, waker: VideoWaker, time: TimeProvider): VideoMaintenance =
        VideoMaintenance(props, uploads, ports.store, ports.followUps, ports.tx, waker, time)

    /** 요청 구동 모드(인스턴스 0대 배포)의 깨우기가 부른다 — 멱등 */
    @Bean
    @ConditionalOnMissingBean(name = ["videoCleanupTask"])
    fun videoCleanupTask(maintenance: VideoMaintenance): OnRequestTask = SimpleOnRequestTask("video-cleanup") { maintenance.run() }

    /** `scheduler` 모듈이 클래스패스에 있을 때만 - 없으면 이 표시를 읽는 쪽이 없다 */
    @Bean
    @ConditionalOnClass(name = ["dev.sumin.skeleton.scheduler.SkeletonScheduled"])
    @ConditionalOnMissingBean
    fun videoCleanupJob(maintenance: VideoMaintenance): VideoCleanupJob = VideoCleanupJob(maintenance)
}
