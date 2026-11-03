package dev.sumin.skeleton.video

import dev.sumin.skeleton.alert.AlertKind
import dev.sumin.skeleton.alert.OwnerAlerts
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.video.alert.VideoAlertKind
import dev.sumin.skeleton.video.alert.VideoMonitor
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 주인 경보 — 일꾼이 안 보이는데 대기가 쌓였을 때 · 실패가 몰릴 때 · 호환 단이 오래 대기할 때. 비밀 없이 수 · 시각만 */
class VideoMonitorTest {
    private val t0 = Instant.parse("2026-11-04T12:00:00Z")
    private val store = FakeVideoStore()
    private val workers = FakeVideoWorkerStore()
    private val emitted = mutableListOf<Pair<AlertKind, String>>()
    private val alerts = object : OwnerAlerts {
        override fun emit(kind: AlertKind, key: String, detail: String, immediate: Boolean) { emitted += kind to detail }
    }
    private val monitor = VideoMonitor(VideoProperties(enabled = true), store, workers, NoopWaker, alerts, TimeProvider { t0 })

    private fun video(id: String, status: VideoStatus = VideoStatus.QUEUED, updated: Instant = t0, compat: TrackStatus = TrackStatus.NONE, ladder: TrackStatus = TrackStatus.NONE) {
        store.rows[id] = Video(
            id = id, ownerAccountId = "a", purpose = "note", visibility = VideoVisibility.PRIVATE, status = status, compatStatus = compat, ladderStatus = ladder,
            lastUploadActivityAt = t0, createdAt = updated, updatedAt = updated,
        )
    }

    @Test
    fun `a queue with no remux worker seen recently raises VIDEO_QUEUE_STALE, a worker seen a minute ago does not`() {
        video("q")
        monitor.check()
        assertEquals(listOf<AlertKind>(VideoAlertKind.VIDEO_QUEUE_STALE), emitted.map { it.first })

        emitted.clear()
        val w = workers.insert("home", VideoLane.entries.toSet(), "h".repeat(64), t0)
        workers.touch(w.id, t0.minusSeconds(60))
        monitor.check()
        assertTrue(emitted.isEmpty())

        workers.touch(w.id, t0.minus(Duration.ofMinutes(20)))
        monitor.check()
        assertEquals(listOf<AlertKind>(VideoAlertKind.VIDEO_QUEUE_STALE), emitted.map { it.first })
    }

    @Test
    fun `an empty queue never alerts even when no worker was ever seen`() {
        monitor.check()
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `five failures in the window raise VIDEO_FAILED_SURGE, four do not`() {
        repeat(4) { video("f$it", VideoStatus.FAILED, t0.minusSeconds(60)) }
        monitor.check()
        assertTrue(emitted.none { it.first == VideoAlertKind.VIDEO_FAILED_SURGE })

        video("f4", status = VideoStatus.PLAYABLE, updated = t0.minusSeconds(60), compat = TrackStatus.FAILED)
        monitor.check()
        assertTrue(emitted.any { it.first == VideoAlertKind.VIDEO_FAILED_SURGE })
        assertTrue(emitted.single { it.first == VideoAlertKind.VIDEO_FAILED_SURGE }.second.startsWith("5 video failures"))
    }

    @Test
    fun `a compat tier waiting over two hours raises VIDEO_COMPAT_STALE`() {
        video("c", VideoStatus.PLAYABLE, updated = t0.minus(Duration.ofHours(3)), compat = TrackStatus.QUEUED)
        monitor.check()
        assertTrue(emitted.any { it.first == VideoAlertKind.VIDEO_COMPAT_STALE })
    }
}
