package dev.sumin.skeleton.video

import dev.sumin.skeleton.common.time.TimeProvider
import org.slf4j.LoggerFactory

data class VideoMaintenanceResult(val staleUploads: Int, val leasesExpired: Int, val laddersQueued: Int)

/**
 * 영상 청소 — 5분마다. ① 한 시간 넘게 움직임 없는 올리기를 치운다 ② 임대가 끝난 일을 거둔다(다시 줄 서거나 3회 뒤 끝내 실패 — 트랙은 트랙만, 원본 단은 그대로)
 * ③ 사다리 스위치가 꺼진 동안 재생 가능해진 영상을 스위치가 켜졌을 때 줄 세운다. 각 단계는 따로 실패해도 다른 단계를 막지 않는다.
 * 올린 원 파일 · 중복 결과 폴더는 사건마다 지연 작업(`video.finalize`)이 맡는다
 */
class VideoMaintenance(
    private val props: VideoProperties,
    private val uploads: VideoUploads,
    private val store: VideoStore,
    private val followUps: VideoFollowUps,
    private val tx: VideoTransactions,
    private val waker: VideoWaker,
    private val time: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(): VideoMaintenanceResult = VideoMaintenanceResult(
        staleUploads = step("abandoned uploads") { uploads.cleanupStaleUploads() },
        leasesExpired = step("lease expiry") { tx.run { time.now().let { VideoLeases.expire(store, followUps, waker, props, it, it).total } } },
        laddersQueued = step("deferred ladders") { queueDeferredLadders() },
    ).also { step("stranded tracks") { wakeStrandedTracks() } }

    /** 상한 · 간격에 걸려 조용히 넘어간 깨우기가 일을 영영 세워 두지 않게 - 집을 트랙이 남아 있으면 이 청소가 다시 깨운다(상한은 깨우기 구현이 지킨다) */
    private fun wakeStrandedTracks(): Int {
        if (!waker.enabled || store.countClaimableTracks() == 0) return 0
        tx.run { followUps.wake() }
        return 1
    }

    private fun queueDeferredLadders(): Int {
        if (!props.ladder) return 0
        return tx.run {
            store.queueDeferredLadders(VideoJobs.LADDER_FLOOR, time.now()).also { if (it > 0 && waker.enabled) followUps.wake() }
        }
    }

    private fun step(what: String, block: () -> Int): Int = try {
        block()
    } catch (e: Exception) {
        log.warn("video maintenance step failed: {} - {}", what, e.javaClass.simpleName)
        0
    }
}
