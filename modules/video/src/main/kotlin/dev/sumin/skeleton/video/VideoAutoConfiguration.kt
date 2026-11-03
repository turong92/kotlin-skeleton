package dev.sumin.skeleton.video

import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.upload.PublicUploadPurger
import dev.sumin.skeleton.common.upload.UploadImages
import dev.sumin.skeleton.common.wake.OnRequestTask
import dev.sumin.skeleton.common.wake.SimpleOnRequestTask
import dev.sumin.skeleton.storage.PresignedStorage
import dev.sumin.skeleton.storage.StorageKeyOwnership
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

    /** 비공개 재생 출입증 — 비밀이 없거나 32자보다 짧으면 기동 실패(비공개 영상은 이 비밀 없이 틀 수 없다) */
    @Bean
    @ConditionalOnMissingBean
    fun videoPlaybackTokens(props: VideoProperties, time: TimeProvider): VideoPlaybackTokens {
        require(props.playback.secret.length >= VideoPlaybackTokens.MIN_SECRET_LENGTH) {
            "skeleton.video.playback.secret must be at least ${VideoPlaybackTokens.MIN_SECRET_LENGTH} characters when skeleton.video.enabled=true " +
                "(env <P>_VIDEO_PLAYBACK_SECRET - list it under secrets:)"
        }
        return VideoPlaybackTokens(props.playback.secret, time)
    }

    /** 재생 허가 — 공개 영상은 공개 주소, 비공개 영상은 출입증 경로. 벤더 스트리밍은 이 빈을 바꿔 끼운다 */
    @Bean
    @ConditionalOnMissingBean(VideoPlayback::class)
    fun videoPlayback(props: VideoProperties, keys: VideoKeys, ports: VideoPorts, tokens: VideoPlaybackTokens, storageProperties: ObjectProvider<StorageProperties>): VideoPlayback =
        StorageVideoPlayback(props, keys, ports.storage, { storageProperties.getIfAvailable { StorageProperties() }.web.`public` }, tokens)

    @Bean
    @ConditionalOnMissingBean
    fun videoPlaylists(tokens: VideoPlaybackTokens, ports: VideoPorts, keys: VideoKeys): VideoPlaylists = VideoPlaylists(tokens, ports.store, keys, ports.storage)

    /**
     * 포스터를 파일 묶음의 이미지 길로 보낸다 - `UploadImages`(`storage-image`)가 있을 때만 일을 한다(없으면 일꾼 포스터 그대로).
     * 올린 이미지의 주인 확인은 `storage` 의 [StorageKeyOwnership] - 빈이 없으면 어떤 이미지도 내 것이 아니다
     */
    @Bean
    @ConditionalOnMissingBean
    fun videoPosters(
        ports: VideoPorts, keys: VideoKeys, policy: VideoAccessPolicy, ownership: ObjectProvider<StorageKeyOwnership>, images: ObjectProvider<UploadImages>, time: TimeProvider,
    ): VideoPosters = VideoPosters(
        ports.store, ports.storage, keys, policy, ownership.getIfAvailable { StorageKeyOwnership { _, _ -> false } }, { images.getIfAvailable() }, ports.followUps, ports.tx, time,
    )

    @Bean
    @ConditionalOnMissingBean
    fun videoLibrary(ports: VideoPorts, policy: VideoAccessPolicy, playback: VideoPlayback, time: TimeProvider, posters: VideoPosters): VideoLibrary =
        VideoLibrary(ports.store, policy, playback, ports.followUps, ports.tx, time, posters)

    /** 호환 · 사다리 일꾼이 읽을 원본 단 재생 목록 주소 — 공개 영상은 공개 주소, 비공개 영상은 일꾼 작업 수명의 출입증 경로 */
    @Bean
    @ConditionalOnMissingBean(VideoWorkerInputs::class)
    fun videoWorkerInputs(keys: VideoKeys, tokens: VideoPlaybackTokens, storageProperties: ObjectProvider<StorageProperties>): VideoWorkerInputs =
        StorageVideoWorkerInputs(keys, tokens, { storageProperties.getIfAvailable { StorageProperties() }.web.`public` })

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
    fun videoJobs(
        props: VideoProperties, ports: VideoPorts, keys: VideoKeys, inputs: VideoWorkerInputs, waker: VideoWaker, time: TimeProvider,
        display: ObjectProvider<VideoDisplayJobs>, posters: VideoPosters,
    ): VideoJobs =
        VideoJobs(props, ports.store, ports.storage, keys, inputs, ports.followUps, ports.tx, waker, time, display.getIfAvailable(), posters)

    @Bean
    @ConditionalOnMissingBean
    fun videoFinalizer(
        ports: VideoPorts,
        keys: VideoKeys,
        events: VideoEvents,
        time: TimeProvider,
        purgers: ObjectProvider<PublicUploadPurger>,
        storageProperties: ObjectProvider<StorageProperties>,
        posters: VideoPosters,
    ): VideoFinalizer =
        VideoFinalizer(ports.store, ports.storage, keys, events, time, purgers.getIfAvailable(), { storageProperties.getIfAvailable { StorageProperties() }.web.`public` }, posters)

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
