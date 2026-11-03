package dev.sumin.skeleton.video

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.storage.UploadedPart
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.util.unit.DataSize

/** 올리기 흐름 — 서버는 파일을 지나가지 않는다: 시작 · 조각 주소 · 조각 목록 · 마침(크기 확인) · 지우기 · 버려진 올리기 청소 */
class VideoUploadsTest {
    private val start = Instant.parse("2026-11-04T00:00:00Z")
    private var clock = start
    private val store = FakeVideoStore()
    private val storage = FakeStorage()
    private var canUpload = true
    private var quotaOk = true
    private val policy = object : VideoAccessPolicy by OwnerVideoAccessPolicy {
        override fun canUpload(caller: String, purpose: String, visibility: VideoVisibility) = canUpload
    }
    private val quota = VideoQuota { _, _, _ -> quotaOk }
    private val uploads = VideoUploads(VideoProperties(enabled = true), store, storage, policy, quota, TimeProvider { clock })

    private fun begin(caller: String = "acc_a", bytes: Long? = 40L * 1024 * 1024, type: String? = "video/mp4", purpose: String? = "note", visibility: String? = "PRIVATE") =
        uploads.start(caller, bytes, type, purpose, visibility)

    @Test
    fun `start opens a multipart upload under the video prefix and records an UPLOADING row`() {
        val started = begin()

        assertEquals(22, started.videoId.length)
        assertEquals(16L * 1024 * 1024, started.partSize)
        assertEquals(3, started.partCount)
        val row = store.rows.getValue(started.videoId)
        assertEquals("videos/u/${started.videoId}", row.uploadKey)
        assertEquals(storage.started.single().key.value, row.uploadKey)
        assertEquals(VideoStatus.UPLOADING, row.status)
        assertEquals("acc_a", row.ownerAccountId)
        assertEquals(VideoVisibility.PRIVATE, row.visibility)
        assertEquals("note", row.purpose)
    }

    @Test
    fun `start refuses a file over 4GB before anything is opened in the store`() {
        assertFailsWith<VideoTooLargeException> { begin(bytes = 4L * 1024 * 1024 * 1024 + 1) }
        assertTrue(storage.started.isEmpty() && store.rows.isEmpty())
        assertEquals(256, begin(bytes = 4L * 1024 * 1024 * 1024).partCount)
    }

    @Test
    fun `start refuses what is not an mp4 or mov, and inputs that are missing or malformed`() {
        assertFailsWith<VideoUnsupportedException> { begin(type = "video/x-matroska") }
        assertFailsWith<VideoInvalidException> { begin(bytes = null) }
        assertFailsWith<VideoInvalidException> { begin(bytes = 0) }
        assertFailsWith<VideoInvalidException> { begin(purpose = "Bad Purpose!") }
        assertFailsWith<VideoInvalidException> { begin(purpose = null) }
        assertFailsWith<VideoInvalidException> { begin(visibility = "SECRET") }
        assertTrue(storage.started.isEmpty())
    }

    @Test
    fun `start asks the access policy and the quota, and a no stops it before the store is touched`() {
        canUpload = false
        assertFailsWith<VideoForbiddenException> { begin() }
        canUpload = true
        quotaOk = false
        assertFailsWith<VideoQuotaException> { begin() }
        assertTrue(storage.started.isEmpty() && store.rows.isEmpty())
    }

    @Test
    fun `start refuses a third upload in flight for the same account but not for another one`() {
        begin(); begin()
        assertFailsWith<VideoTooManyUploadsException> { begin() }
        begin(caller = "acc_b")
    }

    @Test
    fun `start closes the opened multipart upload when the row cannot be saved`() {
        store.failInsert = true
        assertFailsWith<IllegalStateException> { begin() }
        assertEquals(1, storage.aborted.size)
        assertEquals(storage.started.single().key, storage.aborted.single().key)
    }

    @Test
    fun `part urls are signed only for numbers inside 1 to partCount and count as activity`() {
        val id = begin().videoId
        clock = start.plusSeconds(600)

        val urls = uploads.partUrls("acc_a", id, listOf(1, 3))

        assertEquals(listOf(1, 3), urls.map { it.partNumber })
        assertEquals(clock, store.rows.getValue(id).lastUploadActivityAt)
        assertFailsWith<VideoPartRangeException> { uploads.partUrls("acc_a", id, listOf(4)) }
        assertFailsWith<VideoPartRangeException> { uploads.partUrls("acc_a", id, listOf(1, 1)) }
        assertFailsWith<VideoPartRangeException> { uploads.partUrls("acc_a", id, emptyList()) }
    }

    @Test
    fun `the uploaded parts come from the store of objects so a reload can send only the missing ones`() {
        val id = begin().videoId
        storage.parts = listOf(UploadedPart(1, "\"e1\"", 16), UploadedPart(2, "\"e2\"", 16))

        assertEquals(storage.parts, uploads.uploadedParts("acc_a", id))
        assertEquals("up-1", storage.listed.single().uploadId)
        assertEquals("videos/u/$id", storage.listed.single().key.value)
    }

    @Test
    fun `someone else's video answers not found for parts, listing and completing - and nothing is touched`() {
        val id = begin().videoId
        assertFailsWith<VideoNotFoundException> { uploads.partUrls("acc_b", id, listOf(1)) }
        assertFailsWith<VideoNotFoundException> { uploads.uploadedParts("acc_b", id) }
        assertFailsWith<VideoNotFoundException> { uploads.complete("acc_b", id, listOf(1 to "e", 2 to "e", 3 to "e")) }
        assertFailsWith<VideoNotFoundException> { uploads.get("acc_b", id) }
        assertFailsWith<VideoNotFoundException> { uploads.uploadedParts("acc_a", "nope") }
        assertTrue(storage.listed.isEmpty() && storage.completed.isEmpty() && storage.aborted.isEmpty() && storage.deleted.isEmpty())
        assertEquals(VideoStatus.UPLOADING, store.rows.getValue(id).status)
    }

    @Test
    fun `complete checks the stored size and queues the video, and a second complete changes nothing`() {
        val id = begin().videoId
        storage.objects["videos/u/$id"] = 40L * 1024 * 1024

        val done = uploads.complete("acc_a", id, listOf(3 to "c", 1 to "a", 2 to "b"))

        assertEquals(VideoStatus.QUEUED, done.status)
        assertEquals(listOf(1, 2, 3), storage.completed.single().parts.map { it.partNumber })
        val again = uploads.complete("acc_a", id, listOf(1 to "a", 2 to "b", 3 to "c"))
        assertEquals(VideoStatus.QUEUED, again.status)
        assertEquals(1, storage.completed.size, "the store of objects is not asked twice")
    }

    @Test
    fun `complete throws away the object and the row when the size is not what was announced`() {
        val id = begin().videoId
        storage.objects["videos/u/$id"] = 39L * 1024 * 1024

        assertFailsWith<VideoSizeMismatchException> { uploads.complete("acc_a", id, listOf(1 to "a", 2 to "b", 3 to "c")) }

        assertEquals(VideoStatus.DELETED, store.rows.getValue(id).status)
        assertEquals(listOf("videos/u/$id"), storage.deleted)
    }

    @Test
    fun `complete refuses a parts list that does not cover every part`() {
        val id = begin().videoId
        assertFailsWith<VideoPartsInvalidException> { uploads.complete("acc_a", id, listOf(1 to "a", 2 to "b")) }
        assertFailsWith<VideoPartsInvalidException> { uploads.complete("acc_a", id, listOf(1 to "a", 2 to "b", 3 to " ")) }
        assertFailsWith<VideoPartsInvalidException> { uploads.complete("acc_a", id, null) }
        assertTrue(storage.completed.isEmpty())
    }

    @Test
    fun `complete of an abandoned video is a bad state, not a second try`() {
        val id = begin().videoId
        store.markUploadAbandoned(id, clock)
        assertFailsWith<VideoBadStateException> { uploads.complete("acc_a", id, listOf(1 to "a", 2 to "b", 3 to "c")) }
    }

    @Test
    fun `cleanup fails uploads idle for an hour with UPLOAD_ABANDONED and frees their storage, fresh ones stay`() {
        val stale = begin().videoId
        clock = start.plus(Duration.ofMinutes(50))
        val fresh = begin().videoId
        clock = start.plus(Duration.ofMinutes(70))

        assertEquals(1, uploads.cleanupStaleUploads())

        assertEquals(VideoStatus.FAILED, store.rows.getValue(stale).status)
        assertEquals("UPLOAD_ABANDONED", store.rows.getValue(stale).failCode)
        assertEquals(listOf("videos/u/$stale"), storage.deleted)
        assertEquals("up-1", storage.aborted.single().uploadId)
        assertEquals(VideoStatus.UPLOADING, store.rows.getValue(fresh).status)
    }

    @Test
    fun `list shows only my videos, optionally of one purpose, never deleted ones`() {
        val a = begin(purpose = "note").videoId
        begin(purpose = "avatar")
        begin(caller = "acc_b")
        store.markDeleted(a, clock)

        assertEquals(listOf("avatar"), uploads.list("acc_a", null).map { it.purpose })
        assertEquals(emptyList(), uploads.list("acc_a", "note"))
    }

    @Test
    fun `the default limits are the documented ones`() {
        val p = VideoProperties()
        assertEquals(false, p.enabled)
        assertEquals(DataSize.ofGigabytes(4), p.maxBytes)
        assertEquals(DataSize.ofMegabytes(16), p.partSize)
        assertEquals("videos", p.prefix)
    }
}
