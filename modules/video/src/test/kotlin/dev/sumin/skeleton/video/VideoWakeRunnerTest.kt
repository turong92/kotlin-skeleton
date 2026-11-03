package dev.sumin.skeleton.video

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** 깨우기는 돈이다 — 집을 호환 · 사다리 일이 있고 깨우기가 켜졌을 때만 한 번 깨운다 */
class VideoWakeRunnerTest {
    private val t0 = Instant.parse("2026-11-04T00:00:00Z")
    private val store = FakeVideoStore()
    private var wakes = 0
    private fun waker(on: Boolean) = object : VideoWaker {
        override val enabled = on
        override fun wake(): WakeTicket { wakes++; return WakeTicket("exec") }
    }

    private fun queuedTrack() {
        store.rows["v"] = Video(
            id = "v", ownerAccountId = "a", purpose = "note", visibility = VideoVisibility.PUBLIC, status = VideoStatus.PLAYABLE, compatStatus = TrackStatus.QUEUED,
            lastUploadActivityAt = t0, createdAt = t0, updatedAt = t0,
        )
    }

    @Test
    fun `nothing to pick up means no wake, and a waker that is off is never called`() {
        VideoWakeRunner(waker(true), store).run()
        queuedTrack()
        VideoWakeRunner(waker(false), store).run()
        assertEquals(0, wakes)
    }

    @Test
    fun `a queued track with the waker on wakes once`() {
        queuedTrack()
        VideoWakeRunner(waker(true), store).run()
        assertEquals(1, wakes)
    }
}
