package dev.sumin.skeleton.video

import dev.sumin.skeleton.scheduler.SkeletonScheduled

/** 5분마다 영상 청소([VideoMaintenance])를 돈다. `scheduler` 모듈이 있을 때 돈다(없으면 표시가 무시된다 — 요청 구동 모드는 OnRequestTask 가 깨운다) */
class VideoCleanupJob(private val maintenance: VideoMaintenance) {
    @SkeletonScheduled(id = "video.cleanup", fixedDelay = 300_000, initialDelay = 300_000, lockKey = "video.cleanup")
    fun run() {
        maintenance.run()
    }
}
