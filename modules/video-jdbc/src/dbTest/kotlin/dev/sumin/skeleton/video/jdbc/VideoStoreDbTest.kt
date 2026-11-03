package dev.sumin.skeleton.video.jdbc

import dev.sumin.skeleton.video.NewVideo
import dev.sumin.skeleton.video.TrackStatus
import dev.sumin.skeleton.video.VideoStatus
import dev.sumin.skeleton.video.VideoStore
import dev.sumin.skeleton.video.VideoVisibility
import dev.sumin.skeleton.video.VideoWorkerStore
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

/** 모듈 마이그레이션(Flyway)을 깔고 두 방언에서 같은 뜻인지 — 표 셋 · 키의 대소문자 · 조건부 전이(동시에 하나만 이긴다) · 버려진 올리기 */
@Import(DbTestcontainers::class)
@SpringBootTest(classes = [VideoJdbcTestApplication::class])
class VideoStoreDbTest {
    @Autowired lateinit var store: VideoStore
    @Autowired lateinit var workers: VideoWorkerStore
    @Autowired lateinit var jdbc: JdbcTemplate

    private val at = Instant.parse("2026-11-04T00:00:00.123456Z")
    private val owner = "acc_" + UUID.randomUUID().toString().take(8)

    private fun new(id: String = UUID.randomUUID().toString().replace("-", "").take(22), owner: String = this.owner, purpose: String = "note", at: Instant = this.at) =
        NewVideo(id, owner, purpose, VideoVisibility.PRIVATE, "videos/u/$id", "up-$id", 40_000_000, at)

    @Test
    fun `the jdbc stores stand in the app and all three tables exist`() {
        assertIs<JdbcVideoStore>(store)
        assertIs<JdbcVideoWorkerStore>(workers)
        listOf("videos", "video_workers", "video_wake_runs").forEach { assertEquals(0, jdbc.queryForObject("select count(*) from $it where 1 = 0", Int::class.java), it) }
    }

    @Test
    fun `an inserted video reads back with its defaults and microsecond times`() {
        val saved = store.insert(new())

        assertEquals(saved, store.find(saved.id))
        assertEquals(VideoStatus.UPLOADING, saved.status)
        assertEquals(VideoVisibility.PRIVATE, saved.visibility)
        assertEquals(TrackStatus.NONE, saved.compatStatus)
        assertEquals(TrackStatus.NONE, saved.ladderStatus)
        assertEquals(0, saved.attempts)
        assertNull(saved.progress)
        assertEquals(at, saved.createdAt)
        assertEquals(at, saved.lastUploadActivityAt)
        assertNull(store.find("nope"))
    }

    @Test
    fun `ids that differ only by letter case are different videos`() {
        // base64url 은 대소문자를 구분한다 — MySQL 기본 정렬이면 둘이 같은 키가 되어 한 사람이 남의 영상을 덮는다
        val lower = store.insert(new(id = "AbCdEfGhIjKlMnOpQrStUv"))
        val upper = store.insert(new(id = "abcdefghijklmnopqrstuv"))
        assertEquals("AbCdEfGhIjKlMnOpQrStUv", store.find(lower.id)!!.id)
        assertEquals("abcdefghijklmnopqrstuv", store.find(upper.id)!!.id)
        assertNull(store.find("ABCDEFGHIJKLMNOPQRSTUV"))
    }

    @Test
    fun `advance moves a video only from the expected state and clears the multipart id once queued`() {
        val id = store.insert(new()).id

        assertFalse(store.advance(id, VideoStatus.UPLOADED, VideoStatus.QUEUED, at), "wrong from-state")
        assertTrue(store.advance(id, VideoStatus.UPLOADING, VideoStatus.UPLOADED, at.plusSeconds(1)))
        assertNotNull(store.find(id)!!.uploadId)
        assertFalse(store.advance(id, VideoStatus.UPLOADING, VideoStatus.UPLOADED, at.plusSeconds(2)), "already moved")
        assertTrue(store.advance(id, VideoStatus.UPLOADED, VideoStatus.QUEUED, at.plusSeconds(3)))

        val queued = store.find(id)!!
        assertEquals(VideoStatus.QUEUED, queued.status)
        assertNull(queued.uploadId)
        assertEquals(at.plusSeconds(3), queued.updatedAt)
    }

    @Test
    fun `of many calls that advance the same video at once exactly one wins`() {
        val id = store.insert(new()).id
        val wins = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..8).map {
                pool.submit {
                    start.await()
                    if (store.advance(id, VideoStatus.UPLOADING, VideoStatus.UPLOADED, at)) wins.incrementAndGet()
                }
            }
            start.countDown()
            futures.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        assertEquals(1, wins.get())
        assertEquals(VideoStatus.UPLOADED, store.find(id)!!.status)
    }

    @Test
    fun `delete happens once, deleted videos leave the list and the in-flight count`() {
        val kept = store.insert(new(purpose = "note")).id
        val other = store.insert(new(purpose = "avatar")).id
        val gone = store.insert(new(purpose = "note")).id
        store.insert(new(owner = "someone_else"))

        assertEquals(3, store.countInFlight(owner))
        assertTrue(store.markDeleted(gone, at))
        assertFalse(store.markDeleted(gone, at))

        assertEquals(2, store.countInFlight(owner))
        assertEquals(setOf(kept, other), store.listByOwner(owner, null).map { it.id }.toSet())
        assertEquals(listOf(kept), store.listByOwner(owner, "note").map { it.id })
        assertEquals(VideoStatus.DELETED, store.find(gone)!!.status)
    }

    @Test
    fun `abandoned uploads are found oldest first, fresh and touched ones are not, and they fail with UPLOAD_ABANDONED`() {
        val old = store.insert(new(at = at.minusSeconds(7200))).id
        val older = store.insert(new(at = at.minusSeconds(9000))).id
        val touched = store.insert(new(at = at.minusSeconds(7200))).id
        val fresh = store.insert(new(at = at)).id
        store.touchUpload(touched, at)
        val playable = store.insert(new(at = at.minusSeconds(9000))).id
        jdbc.update("update videos set status = 'PLAYABLE' where id = ?", playable)

        val stale = store.staleUploads(at.minusSeconds(3600), 10)

        assertEquals(listOf(older, old), stale.map { it.id }.filter { it in setOf(older, old, touched, fresh, playable) })
        assertTrue(store.markUploadAbandoned(old, at))
        assertFalse(store.markUploadAbandoned(old, at), "FAILED is not abandoned a second time")
        assertFalse(store.markUploadAbandoned(playable, at), "a playable video is never an abandoned upload")
        val failed = store.find(old)!!
        assertEquals(VideoStatus.FAILED, failed.status)
        assertEquals("UPLOAD_ABANDONED", failed.failCode)
        assertEquals(VideoStatus.PLAYABLE, store.find(playable)!!.status)
    }
}
