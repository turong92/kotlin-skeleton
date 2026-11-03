package dev.sumin.skeleton.video

import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 5분 청소 — 임대 만료는 시도 +1 로 다시 줄 세우고 3 회를 넘으면 실패, 트랙 실패는 트랙만. 원본 단은 어느 경우에도 지우지 않는다 */
class VideoMaintenanceTest {
    private val t0 = Instant.parse("2026-11-04T00:00:00Z")
    private var clock = t0
    private val store = FakeVideoStore()
    private val storage = FakeStorage()
    private val followUps = FakeFollowUps()
    private var waker: VideoWaker = NoopWaker
    private var props = VideoProperties(enabled = true)

    private fun maintenance() = VideoMaintenance(
        props, VideoUploads(props, store, storage, OwnerVideoAccessPolicy, VideoQuota { _, _, _ -> true }, TimeProvider { clock }),
        store, followUps, FakeTransactions(), waker, TimeProvider { clock },
    )

    private fun leased(id: String, status: VideoStatus, lane: VideoLane, attempts: Int, track: TrackStatus = TrackStatus.NONE) = Video(
        id = id, ownerAccountId = "acc_a", purpose = "note", visibility = VideoVisibility.PRIVATE, status = status, uploadKey = "videos/u/$id", uploadBytes = 1000,
        origKey = "videos/p/$id/o-aaaaaaaaaaaaaaaa/index.m3u8".takeIf { status == VideoStatus.PLAYABLE }, srcWidth = 1920, srcHeight = 1080,
        compatStatus = if (lane == VideoLane.COMPAT) track else TrackStatus.SKIPPED, ladderStatus = if (lane == VideoLane.LADDER) track else TrackStatus.SKIPPED,
        attempts = attempts, leaseOwner = "w1", leaseUntil = t0.minusSeconds(1), lane = lane, lastUploadActivityAt = t0, createdAt = t0, updatedAt = t0,
    ).also { store.rows[id] = it }

    @Test
    fun `an expired remux lease goes back in line with the attempt kept, and the third failure ends it`() {
        leased("a", VideoStatus.REMUXING, VideoLane.REMUX, attempts = 1)
        leased("b", VideoStatus.REMUXING, VideoLane.REMUX, attempts = 3)

        val result = maintenance().run()

        assertEquals(2, result.leasesExpired)
        store.rows.getValue("a").also { assertEquals(VideoStatus.QUEUED, it.status); assertEquals(1, it.attempts); assertEquals(null, it.leaseOwner) }
        store.rows.getValue("b").also { assertEquals(VideoStatus.FAILED, it.status); assertEquals(VideoFailCode.LEASE_EXPIRED, it.failCode) }
        assertEquals(listOf(VideoFinalizeEvent.FAILED, VideoFinalizeEvent.SWEEP), followUps.events("b"))
        assertTrue(followUps.events("a").isEmpty())
    }

    @Test
    fun `an expired track lease fails only the track and never touches the original tier or the uploaded file`() {
        leased("c", VideoStatus.PLAYABLE, VideoLane.COMPAT, attempts = 3, track = TrackStatus.RUNNING)
        leased("d", VideoStatus.PLAYABLE, VideoLane.LADDER, attempts = 3, track = TrackStatus.RUNNING)
        leased("e", VideoStatus.PLAYABLE, VideoLane.LADDER, attempts = 1, track = TrackStatus.RUNNING)

        maintenance().run()

        store.rows.getValue("c").also {
            assertEquals(VideoStatus.PLAYABLE, it.status)
            assertEquals(TrackStatus.FAILED, it.compatStatus)
            assertEquals("videos/p/c/o-aaaaaaaaaaaaaaaa/index.m3u8", it.origKey)
        }
        assertEquals(TrackStatus.FAILED, store.rows.getValue("d").ladderStatus)
        assertEquals(TrackStatus.QUEUED, store.rows.getValue("e").ladderStatus)
        assertEquals(listOf(VideoFinalizeEvent.TRACK_FAILED, VideoFinalizeEvent.SWEEP), followUps.events("c"))
        assertTrue(storage.deleted.isEmpty())
    }

    @Test
    fun `a lease that has not run out is left alone`() {
        leased("f", VideoStatus.REMUXING, VideoLane.REMUX, attempts = 3).also { store.rows["f"] = it.copy(leaseUntil = t0.plusSeconds(60)) }
        assertEquals(0, maintenance().run().leasesExpired)
        assertEquals(VideoStatus.REMUXING, store.rows.getValue("f").status)
    }

    @Test
    fun `a wake is asked for only when a requeued track needs a worker and the waker is on`() {
        leased("g", VideoStatus.PLAYABLE, VideoLane.COMPAT, attempts = 1, track = TrackStatus.RUNNING)
        maintenance().run()
        assertEquals(0, followUps.wakes)

        leased("h", VideoStatus.PLAYABLE, VideoLane.COMPAT, attempts = 1, track = TrackStatus.RUNNING)
        waker = object : VideoWaker { override val enabled = true; override fun wake() = WakeTicket(null) }
        maintenance().run()
        assertEquals(2, followUps.wakes, "the lease expiry asks and the sweep of waiting tracks asks - the waker's own cap folds them")
    }

    @Test
    fun `a track waiting for a worker is woken for every sweep - a wake the cap skipped earlier must not leave the work standing for good`() {
        waker = object : VideoWaker { override val enabled = true; override fun wake() = WakeTicket(null) }
        maintenance().run()
        assertEquals(0, followUps.wakes, "nothing waits")

        store.rows["w"] = leased("w", VideoStatus.PLAYABLE, VideoLane.COMPAT, attempts = 0, track = TrackStatus.QUEUED).copy(leaseOwner = null, leaseUntil = null, lane = null)
        maintenance().run()
        assertEquals(1, followUps.wakes)

        waker = NoopWaker
        maintenance().run()
        assertEquals(1, followUps.wakes, "no waker, no ask")
    }

    @Test
    fun `when the ladder switch is turned on later, ladders left at NONE are queued`() {
        props = VideoProperties(enabled = true, ladder = false)
        store.rows["x"] = leased("x", VideoStatus.PLAYABLE, VideoLane.REMUX, 0).copy(ladderStatus = TrackStatus.NONE, leaseOwner = null, leaseUntil = null, lane = null)
        assertEquals(0, maintenance().run().laddersQueued)

        props = VideoProperties(enabled = true)
        assertEquals(1, maintenance().run().laddersQueued)
        assertEquals(TrackStatus.QUEUED, store.rows.getValue("x").ladderStatus)
    }

    @Test
    fun `the abandoned-upload sweep still runs inside the same maintenance`() {
        store.rows["u"] = Video(
            id = "u", ownerAccountId = "acc_a", purpose = "note", visibility = VideoVisibility.PRIVATE, status = VideoStatus.UPLOADING, uploadKey = "videos/u/u", uploadId = "up-1",
            uploadBytes = 10, lastUploadActivityAt = t0, createdAt = t0, updatedAt = t0,
        )
        clock = t0.plus(Duration.ofHours(2))
        assertEquals(1, maintenance().run().staleUploads)
        assertEquals("UPLOAD_ABANDONED", store.rows.getValue("u").failCode)
    }
}
