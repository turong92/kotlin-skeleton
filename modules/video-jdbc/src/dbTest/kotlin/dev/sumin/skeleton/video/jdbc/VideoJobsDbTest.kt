package dev.sumin.skeleton.video.jdbc

import dev.sumin.skeleton.video.ExpiredFailure
import dev.sumin.skeleton.video.LeaseEnd
import dev.sumin.skeleton.video.NewVideo
import dev.sumin.skeleton.video.PlayableResult
import dev.sumin.skeleton.video.TrackStatus
import dev.sumin.skeleton.video.VideoFinalizeEvent
import dev.sumin.skeleton.video.VideoFollowUps
import dev.sumin.skeleton.video.VideoLane
import dev.sumin.skeleton.video.VideoStatus
import dev.sumin.skeleton.video.VideoStore
import dev.sumin.skeleton.video.VideoTransactions
import dev.sumin.skeleton.video.VideoVisibility
import dev.sumin.skeleton.video.VideoWorkerStore
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

/** 일꾼 쪽 SQL 이 두 방언에서 같은 뜻인지 — 같은 줄을 둘이 못 집는다(SKIP LOCKED) · 임대 · 늦은 갱신 무시 · 임대 만료 · 후속 작업이 상태와 함께 커밋/롤백 */
@Import(DbTestcontainers::class)
@SpringBootTest(classes = [VideoJdbcTestApplication::class])
class VideoJobsDbTest {
    @Autowired lateinit var store: VideoStore
    @Autowired lateinit var workers: VideoWorkerStore
    @Autowired lateinit var followUps: VideoFollowUps
    @Autowired lateinit var tx: VideoTransactions
    @Autowired lateinit var jdbc: JdbcTemplate

    private val t0 = Instant.parse("2026-11-04T00:00:00.123456Z")

    @BeforeTest
    fun clean() {
        jdbc.update("delete from videos")
        jdbc.update("delete from jobs")
    }

    private fun queued(created: Instant = t0): String {
        val id = UUID.randomUUID().toString().replace("-", "").take(22)
        store.insert(NewVideo(id, "acc_a", "note", VideoVisibility.PRIVATE, "videos/u/$id", "up-$id", 1000, created))
        store.advance(id, VideoStatus.UPLOADING, VideoStatus.UPLOADED, created)
        store.advance(id, VideoStatus.UPLOADED, VideoStatus.QUEUED, created)
        return id
    }

    private val result = PlayableResult(
        origKey = "videos/p/x/o-0123456789abcdef/index.m3u8", origBytes = 330, origSha256 = "0".repeat(64), srcContainer = "mov,mp4", srcCodec = "avc1", srcCodecString = "avc1.640028",
        srcWidth = 1080, srcHeight = 1920, srcRotation = 90, srcDurationMs = 180_000, srcFps = 29.97, srcBitrate = 17_000_000, srcAudioCodec = "aac",
        posterKey = "videos/p/x/p-0123456789abcdef.jpg", posterWidth = 720, posterHeight = 1280, compat = TrackStatus.QUEUED, ladder = TrackStatus.QUEUED,
    )

    /** 새로 올린 줄을 집어 재생 가능으로 만든다 — 다른 대기 줄이 없을 때 쓴다(집기는 가장 오래된 줄부터) */
    private fun playable(id: String = queued(), owner: String = "w1"): String {
        store.claimNext(VideoLane.REMUX, owner, t0.plusSeconds(600), "b".repeat(32), t0)!!
        assertTrue(store.markPlayable(id, owner, result, t0))
        return id
    }

    @Test
    fun `many workers claiming at once never take the same video twice`() {
        val ids = (1..6).map { queued(t0.plusSeconds(it.toLong())) }.toSet()
        val taken = ConcurrentHashMap.newKeySet<String>()
        val duplicates = ConcurrentHashMap.newKeySet<String>()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..8).map { n ->
                pool.submit {
                    start.await()
                    while (true) {
                        val v = store.claimNext(VideoLane.REMUX, "w$n", t0.plusSeconds(600), "b".repeat(32), t0) ?: break
                        if (!taken.add(v.id)) duplicates += v.id
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        assertEquals(emptySet(), duplicates)
        assertEquals(ids, taken)
        store.find(ids.first())!!.also {
            assertEquals(VideoStatus.REMUXING, it.status)
            assertEquals(1, it.attempts)
            assertEquals(VideoLane.REMUX, it.lane)
            assertNotNull(it.leaseOwner)
        }
    }

    @Test
    fun `claim takes the oldest first, tracks only after the video is playable and unleased, and a ladder gets its build`() {
        val newer = queued(t0.plusSeconds(10))
        val older = queued(t0)
        assertEquals(older, store.claimNext(VideoLane.REMUX, "w1", t0.plusSeconds(600), "b".repeat(32), t0)!!.id)
        assertNull(store.claimNext(VideoLane.COMPAT, "w1", t0.plusSeconds(600), "b".repeat(32), t0), "a remuxing video has no track to take")
        assertTrue(store.markPlayable(older, "w1", result, t0))

        val compat = store.claimNext(VideoLane.COMPAT, "w2", t0.plusSeconds(600), "b".repeat(32), t0)!!
        assertEquals(TrackStatus.RUNNING, compat.compatStatus)
        assertNull(store.claimNext(VideoLane.LADDER, "w3", t0.plusSeconds(600), "c".repeat(32), t0), "one lane at a time per video")
        assertTrue(store.markCompatReady(older, "w2", "videos/p/x/c-0123456789abcdef/index.m3u8", 100, t0))
        val ladder = store.claimNext(VideoLane.LADDER, "w3", t0.plusSeconds(600), "c".repeat(32), t0)!!
        assertEquals("c".repeat(32), ladder.ladderBuild)
        assertEquals(newer, store.claimNext(VideoLane.REMUX, "w4", t0.plusSeconds(600), "b".repeat(32), t0)!!.id)
    }

    @Test
    fun `markPlayable is one-shot and only for the lease owner, and writes every probe field back`() {
        val id = queued()
        store.claimNext(VideoLane.REMUX, "w1", t0.plusSeconds(600), "b".repeat(32), t0)
        assertFalse(store.markPlayable(id, "w2", result, t0), "not the owner")
        assertTrue(store.markPlayable(id, "w1", result, t0.plusSeconds(5)))
        assertFalse(store.markPlayable(id, "w1", result, t0.plusSeconds(6)), "already playable")

        store.find(id)!!.also {
            assertEquals(VideoStatus.PLAYABLE, it.status)
            assertEquals(result.origKey, it.origKey)
            assertEquals(330, it.origBytes)
            assertEquals(1080, it.srcWidth)
            assertEquals(29.97, it.srcFps)
            assertEquals("avc1.640028", it.srcCodecString)
            assertEquals(result.posterKey, it.posterKey)
            assertEquals(TrackStatus.QUEUED, it.compatStatus)
            assertEquals(0, it.attempts)
            assertNull(it.leaseOwner)
            assertNull(it.lane)
            assertEquals("videos/u/$id", it.uploadKey, "the uploaded file key stays until the cleanup deletes the file")
        }
    }

    @Test
    fun `a compat-skipped video uses the original tier as its compat key`() {
        val id = queued()
        store.claimNext(VideoLane.REMUX, "w1", t0.plusSeconds(600), "b".repeat(32), t0)
        assertTrue(store.markPlayable(id, "w1", result.copy(compat = TrackStatus.SKIPPED), t0))
        assertEquals(result.origKey, store.find(id)!!.compatKey)
    }

    @Test
    fun `heartbeat extends only the owner's lease and keeps the last progress when none is sent`() {
        val id = queued()
        store.claimNext(VideoLane.REMUX, "w1", t0.plusSeconds(600), "b".repeat(32), t0)
        assertFalse(store.extendLease(id, "w2", t0.plusSeconds(1200), 10, t0))
        assertTrue(store.extendLease(id, "w1", t0.plusSeconds(1200), 40, t0))
        assertTrue(store.extendLease(id, "w1", t0.plusSeconds(1800), null, t0))
        store.find(id)!!.also {
            assertEquals(40, it.progress)
            assertEquals(t0.plusSeconds(1800), it.leaseUntil)
        }
    }

    @Test
    fun `failLeased requeues while attempts remain, ends the video for a remux failure and only the track for a track failure`() {
        val id = queued()
        store.claimNext(VideoLane.REMUX, "w1", t0.plusSeconds(600), "b".repeat(32), t0)
        assertNull(store.failLeased(id, "w2", "CORRUPT", null, false, t0), "not the owner")
        assertEquals(LeaseEnd.REQUEUED, store.failLeased(id, "w1", "PROCESSING_FAILED", null, true, t0))
        assertEquals(VideoStatus.QUEUED, store.find(id)!!.status)

        store.claimNext(VideoLane.REMUX, "w1", t0.plusSeconds(600), "b".repeat(32), t0)
        assertEquals(LeaseEnd.FAILED, store.failLeased(id, "w1", "CORRUPT", "bad", false, t0))
        store.find(id)!!.also { assertEquals(VideoStatus.FAILED, it.status); assertEquals("CORRUPT", it.failCode); assertNull(it.leaseOwner) }

        val track = playable()
        store.claimNext(VideoLane.COMPAT, "w2", t0.plusSeconds(600), "b".repeat(32), t0)
        assertEquals(LeaseEnd.FAILED, store.failLeased(track, "w2", "PROCESSING_FAILED", null, false, t0))
        store.find(track)!!.also {
            assertEquals(VideoStatus.PLAYABLE, it.status)
            assertEquals(TrackStatus.FAILED, it.compatStatus)
            assertEquals(result.origKey, it.origKey)
        }
    }

    @Test
    fun `expired leases are requeued with the attempt kept, ended after three, and a track only fails the track`() {
        val track = playable()   // 집기는 가장 오래된 줄부터라 먼저 재생 가능으로 만들어 둔다
        val retry = queued()
        val last = queued()
        store.claimNext(VideoLane.REMUX, "w1", t0.minusSeconds(1), "b".repeat(32), t0)
        store.claimNext(VideoLane.REMUX, "w2", t0.minusSeconds(1), "b".repeat(32), t0)
        jdbc.update("update videos set attempts = 3 where id = ?", last)
        store.claimNext(VideoLane.COMPAT, "w3", t0.minusSeconds(1), "b".repeat(32), t0)
        jdbc.update("update videos set attempts = 3 where id = ?", track)

        val expiry = store.expireLeases(t0, t0.plusSeconds(1))

        assertEquals(1, expiry.requeued)
        assertEquals(setOf(ExpiredFailure(last, true), ExpiredFailure(track, false)), expiry.failed.toSet())
        store.find(retry)!!.also { assertEquals(VideoStatus.QUEUED, it.status); assertEquals(1, it.attempts); assertNull(it.leaseOwner) }
        store.find(last)!!.also { assertEquals(VideoStatus.FAILED, it.status); assertEquals("LEASE_EXPIRED", it.failCode) }
        store.find(track)!!.also {
            assertEquals(VideoStatus.PLAYABLE, it.status)
            assertEquals(TrackStatus.FAILED, it.compatStatus)
            assertEquals(result.origKey, it.origKey)
            assertEquals(0, it.attempts)
        }
        assertEquals(0, store.expireLeases(t0, t0.plusSeconds(2)).total, "nothing is counted twice")
    }

    @Test
    fun `counts for the owner alerts and the waker see only what they should`() {
        val id = playable()
        queued()
        assertEquals(1, store.countQueuedRemux())
        assertEquals(1, store.countClaimableTracks())
        assertEquals(1, store.countCompatQueuedBefore(t0.plusSeconds(60)))
        assertEquals(0, store.countCompatQueuedBefore(t0.minusSeconds(60)))
        store.claimNext(VideoLane.COMPAT, "w1", t0.plusSeconds(600), "b".repeat(32), t0)
        store.failLeased(id, "w1", "PROCESSING_FAILED", null, false, t0.plusSeconds(1))
        assertEquals(1, store.countFailedSince(t0))
        assertEquals(0, store.countFailedSince(t0.plusSeconds(60)))
        assertEquals(1, store.countClaimableTracks(), "the ladder is still waiting")
    }

    @Test
    fun `deferred ladders are queued only for playable videos taller than the floor, and the upload key is cleared once`() {
        val big = playable()
        val small = playable(queued())
        jdbc.update("update videos set ladder_status = 'NONE' where id in (?, ?)", big, small)
        jdbc.update("update videos set src_width = 640, src_height = 360 where id = ?", small)

        assertEquals(1, store.queueDeferredLadders(480, t0))
        assertEquals(TrackStatus.QUEUED, store.find(big)!!.ladderStatus)
        assertEquals(TrackStatus.NONE, store.find(small)!!.ladderStatus)

        assertTrue(store.clearUploadKey(big, t0))
        assertFalse(store.clearUploadKey(big, t0))
        assertNull(store.find(big)!!.uploadKey)
        assertFalse(store.clearUploadKey(queued(), t0), "a video that is not playable keeps its file")
    }

    // ---------- 일꾼 토큰 표 ----------

    @Test
    fun `worker tokens - found by hash while active, revoked once, last seen drives the absence check`() {
        val sha = UUID.randomUUID().toString().replace("-", "").repeat(2)
        val worker = workers.insert("home", setOf(VideoLane.REMUX, VideoLane.COMPAT), sha, t0)

        assertEquals(setOf(VideoLane.REMUX, VideoLane.COMPAT), workers.findActiveByTokenSha256(sha)!!.lanes)
        assertTrue(workers.existsByTokenSha256(sha))
        assertNull(workers.lastSeenRemux().takeIf { jdbc.queryForObject("select count(*) from video_workers where last_seen_at is not null", Int::class.java) == 0 })
        workers.touch(worker.id, t0.plusSeconds(30))
        assertEquals(t0.plusSeconds(30), workers.lastSeenRemux())

        assertTrue(workers.revoke(worker.id, t0.plusSeconds(60)))
        assertFalse(workers.revoke(worker.id, t0.plusSeconds(61)))
        assertNull(workers.findActiveByTokenSha256(sha))
        assertTrue(workers.existsByTokenSha256(sha), "a revoked token still exists - the bootstrap does not revive it")
        assertTrue(workers.list().any { it.id == worker.id && it.revokedAt != null })
        assertFailsWith<Exception> { workers.insert("again", setOf(VideoLane.REMUX), sha, t0) }
    }

    // ---------- 후속 작업 ----------

    @Test
    fun `a follow-up is a job row with the event, delayed ones run later - and it rolls back with the state change`() {
        val id = queued()
        followUps.finalize(id, VideoFinalizeEvent.PLAYABLE)
        followUps.finalize(id, VideoFinalizeEvent.UPLOAD_CLEANUP, Duration.ofHours(1))
        followUps.wake()

        val rows = jdbc.queryForList("select job_type, payload_json, next_run_at from jobs order by id")
        assertEquals(listOf("video.finalize", "video.finalize", "video.wake"), rows.map { it["job_type"] })
        assertEquals("""{"videoId":"$id","event":"PLAYABLE"}""", rows[0]["payload_json"])

        jdbc.update("delete from jobs")
        assertFailsWith<IllegalStateException> {
            tx.run {
                store.claimNext(VideoLane.REMUX, "w1", t0.plusSeconds(600), "b".repeat(32), t0)
                followUps.finalize(id, VideoFinalizeEvent.PLAYABLE)
                error("boom")
            }
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from jobs", Int::class.java), "the job rolled back with the transaction")
        assertEquals(VideoStatus.QUEUED, store.find(id)!!.status, "so did the claim")
    }
}
