package dev.sumin.skeleton.video

import dev.sumin.skeleton.storage.StorageWebProperties
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `video.finalize` 가 하는 일 — 이벤트 전달, 올린 원 파일 삭제(원본 단이 생긴 뒤만), 이긴 결과만 남기는 쓸기. 지우는 쪽이라 틀리면 데이터를 잃는다 */
class VideoFinalizerTest {
    private val t0 = Instant.parse("2026-11-04T00:00:00Z")
    private val store = FakeVideoStore()
    private val storage = FakeStorage()
    private val events = RecordingEvents()
    private val props = VideoProperties(enabled = true)
    private val finalizer = VideoFinalizer(store, storage, VideoKeys(props, StorageWebProperties()), events)

    private fun playable(id: String = "v1", extra: (Video) -> Video = { it }): Video = Video(
        id = id, ownerAccountId = "acc_a", purpose = "note", visibility = VideoVisibility.PRIVATE, status = VideoStatus.PLAYABLE,
        uploadKey = "videos/u/$id", uploadBytes = 1000, origKey = "videos/p/$id/o-aaaaaaaaaaaaaaaa/index.m3u8", origBytes = 330,
        compatStatus = TrackStatus.READY, compatKey = "videos/p/$id/c-bbbbbbbbbbbbbbbb/index.m3u8", posterKey = "videos/p/$id/p-aaaaaaaaaaaaaaaa.jpg",
        lastUploadActivityAt = t0, createdAt = t0, updatedAt = t0,
    ).let(extra).also { store.rows[id] = it; storage.objects[it.uploadKey ?: "videos/u/$id"] = 1000 }

    @Test
    fun `the uploaded file is deleted only once the video is playable with its original tier`() {
        playable()
        finalizer.handle("v1", VideoFinalizeEvent.UPLOAD_CLEANUP)

        assertEquals(listOf("videos/u/v1"), storage.deleted)
        assertEquals(null, store.rows.getValue("v1").uploadKey)
    }

    @Test
    fun `the uploaded file survives while the original tier is not verified - still queued, remuxing, failed or without an original key`() {
        listOf(
            playable("q") { it.copy(status = VideoStatus.QUEUED, origKey = null) },
            playable("r") { it.copy(status = VideoStatus.REMUXING, origKey = null) },
            playable("f") { it.copy(status = VideoStatus.FAILED, origKey = null) },
            playable("n") { it.copy(origKey = null) },
        ).forEach { finalizer.handle(it.id, VideoFinalizeEvent.UPLOAD_CLEANUP) }

        assertTrue(storage.deleted.isEmpty(), storage.deleted.toString())
        assertTrue(store.rows.values.all { it.uploadKey != null })
    }

    @Test
    fun `events reach the app once per kind - playable, track ready, failed - and a deleted video is skipped`() {
        playable()
        finalizer.handle("v1", VideoFinalizeEvent.PLAYABLE)
        finalizer.handle("v1", VideoFinalizeEvent.COMPAT_READY)
        finalizer.handle("v1", VideoFinalizeEvent.LADDER_READY)
        playable("bad") { it.copy(status = VideoStatus.FAILED) }
        finalizer.handle("bad", VideoFinalizeEvent.FAILED)
        playable("gone") { it.copy(status = VideoStatus.DELETED) }
        finalizer.handle("gone", VideoFinalizeEvent.PLAYABLE)
        finalizer.handle("unknown", VideoFinalizeEvent.PLAYABLE)

        assertEquals(listOf("playable:v1", "track:COMPAT:v1", "track:LADDER:v1", "failed:bad"), events.calls)
    }

    @Test
    fun `sweep keeps the winners and removes the losers' folders inside this video's folder only`() {
        val v = playable { it.copy(ladderStatus = TrackStatus.READY, ladderBuild = "c".repeat(32)) }
        val keep = listOf(
            "videos/p/v1/o-aaaaaaaaaaaaaaaa/index.m3u8", "videos/p/v1/o-aaaaaaaaaaaaaaaa/init.mp4", "videos/p/v1/o-aaaaaaaaaaaaaaaa/s00000.m4s",
            "videos/p/v1/c-bbbbbbbbbbbbbbbb/index.m3u8", "videos/p/v1/c-bbbbbbbbbbbbbbbb/s00000.m4s", "videos/p/v1/p-aaaaaaaaaaaaaaaa.jpg",
            "videos/p/v1/h-${"c".repeat(32)}/master.m3u8", "videos/p/v1/h-${"c".repeat(32)}/v0_000.m4s",
            "videos/p/v2/o-1111111111111111/index.m3u8",   // 다른 영상의 폴더 — 건드리지 않는다
            "videos/u/v1",                                  // 올린 원 파일은 쓸기의 몫이 아니다
        )
        val lose = listOf(
            "videos/p/v1/o-dddddddddddddddd/index.m3u8", "videos/p/v1/o-dddddddddddddddd/s00000.m4s", "videos/p/v1/c-eeeeeeeeeeeeeeee/index.m3u8",
            "videos/p/v1/h-${"d".repeat(32)}/master.m3u8", "videos/p/v1/p-dddddddddddddddd.jpg",
        )
        (keep + lose).forEach { storage.objects[it] = 1 }

        finalizer.handle(v.id, VideoFinalizeEvent.SWEEP)

        assertEquals(lose.sorted(), storage.deleted.sorted())
        assertTrue(keep.all { it in storage.objects })
    }

    @Test
    fun `sweep leaves a folder alone while a worker still holds the lease, and clears a failed video's folder entirely`() {
        playable { it.copy(leaseOwner = "w1", leaseUntil = t0.plusSeconds(60), lane = VideoLane.COMPAT) }
        storage.objects["videos/p/v1/c-eeeeeeeeeeeeeeee/index.m3u8"] = 1
        finalizer.handle("v1", VideoFinalizeEvent.SWEEP)
        assertTrue(storage.deleted.isEmpty())

        playable("bad") { it.copy(status = VideoStatus.FAILED, origKey = null, compatKey = null, posterKey = null, compatStatus = TrackStatus.NONE) }
        storage.objects["videos/p/bad/o-aaaaaaaaaaaaaaaa/index.m3u8"] = 1
        finalizer.handle("bad", VideoFinalizeEvent.SWEEP)
        assertEquals(listOf("videos/p/bad/o-aaaaaaaaaaaaaaaa/index.m3u8"), storage.deleted)
    }
}
