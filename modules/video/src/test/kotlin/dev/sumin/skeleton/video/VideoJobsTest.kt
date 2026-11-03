package dev.sumin.skeleton.video

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.storage.StoragePublicProperties
import dev.sumin.skeleton.storage.StorageWebProperties
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 일꾼이 집는 일 — claim · heartbeat · upload-urls · complete · fail. 돈 · 데이터에 닿는 것: 두 일꾼이 같은 일을 안 집고, 늦은 결과는 무시되고, 원 파일은 원본 단 검증 전에 안 지운다 */
class VideoJobsTest {
    private val t0 = Instant.parse("2026-11-04T00:00:00Z")
    private var clock = t0
    private val store = FakeVideoStore()
    private val storage = FakeStorage()
    private val followUps = FakeFollowUps()
    private val tx = FakeTransactions()
    private var waker: VideoWaker = NoopWaker
    private var props = VideoProperties(enabled = true)
    private val hash = "0123456789abcdef"
    private val sha = hash + "f".repeat(48)

    private val all = VideoWorker(1, "home", VideoLane.entries.toSet(), t0, null, null)
    private val remuxOnly = all.copy(id = 2, name = "remux-only", lanes = setOf(VideoLane.REMUX))
    private val compatOnly = all.copy(id = 3, name = "compat-only", lanes = setOf(VideoLane.COMPAT))

    private fun jobs(inputs: VideoWorkerInputs = VideoWorkerInputs { v, _ -> "https://cdn.example.com/${v.origKey}" }) = VideoJobs(
        props, store, storage, VideoKeys(props, StorageWebProperties()), inputs, followUps, tx, waker, TimeProvider { clock },
    )

    private fun video(id: String = "v1", status: VideoStatus = VideoStatus.QUEUED, visibility: VideoVisibility = VideoVisibility.PRIVATE, bytes: Long = 1000, created: Instant = t0) =
        Video(
            id = id, ownerAccountId = "acc_a", purpose = "note", visibility = visibility, status = status, uploadKey = "videos/u/$id", uploadBytes = bytes,
            lastUploadActivityAt = t0, createdAt = created, updatedAt = created,
        ).also { store.rows[id] = it }

    private fun folderOf(v: Video) = if (v.visibility == VideoVisibility.PUBLIC) "public/v/${v.id}/" else "videos/p/${v.id}/"

    /** 저장소에 조각 폴더를 둔다 — 목록 10 + init 20 + 조각 둘 100 · 200 = 330바이트 */
    private fun putFolder(v: Video, name: String): Long {
        val base = folderOf(v) + name
        storage.objects["$base/index.m3u8"] = 10
        storage.objects["$base/init.mp4"] = 20
        storage.objects["$base/s00000.m4s"] = 100
        storage.objects["$base/s00001.m4s"] = 200
        return 330
    }

    private val probe = ProbeResult("mov,mp4,m4a,3gp,3g2,mj2", "avc1.640028", 1080, 1920, 90, 180_000, 30.0, 17_000_000, "aac")

    private fun remuxResult(bytes: Long, name: String = "o-$hash/index.m3u8", sha256: String = sha, compatible: Boolean = false, p: ProbeResult = probe) =
        VideoJobResult(orig = ResultFile(name, bytes, sha256), probe = p, compatible = compatible)

    /** 한 영상을 REMUX 로 집어 임대를 쥔 상태까지 */
    private fun claimed(v: Video = video(), worker: VideoWorker = all, workerId: String = "w1"): VideoJob = jobs().claim(worker, workerId, null)!!.also { assertEquals(v.id, it.jobId) }

    private fun playable(v: Video = video()): Video {
        claimed(v)
        val bytes = putFolder(v, "o-$hash")
        jobs().complete(all, "w1", v.id, remuxResult(bytes))
        return store.rows.getValue(v.id)
    }

    // ---------- claim ----------

    @Test
    fun `claim leases the oldest queued video to the worker and hands out a signed read address of the uploaded file`() {
        video("older", created = t0.minusSeconds(60))
        video("newer")
        val job = claimed(store.rows.getValue("older"))

        assertEquals(VideoLane.REMUX, job.lane)
        assertEquals(1, job.attempt)
        assertEquals(t0.plus(Duration.ofMinutes(10)), job.leaseUntil)
        assertTrue(job.inputUrl.startsWith("http://s3.test/videos/u/older"), job.inputUrl)
        assertEquals(Duration.ofHours(2), storage.signedDownloads.single().expiresIn)
        assertEquals(1000, job.inputBytes)
        val row = store.rows.getValue("older")
        assertEquals(VideoStatus.REMUXING, row.status)
        assertEquals("w1", row.leaseOwner)
        assertEquals(VideoLane.REMUX, row.lane)
        assertEquals(VideoStatus.QUEUED, store.rows.getValue("newer").status)
    }

    @Test
    fun `claim asks for the verifying decode only on a public video, and only while the switch is on`() {
        video("pub", visibility = VideoVisibility.PUBLIC)
        assertTrue(claimed(store.rows.getValue("pub")).verifyDecode)

        video("priv")
        assertFalse(claimed(store.rows.getValue("priv"), workerId = "w2").verifyDecode)

        props = VideoProperties(enabled = true, verifyPublic = false)
        video("pub2", visibility = VideoVisibility.PUBLIC)
        assertFalse(claimed(store.rows.getValue("pub2"), workerId = "w3").verifyDecode)
    }

    @Test
    fun `claim only hands out lanes the token allows and the request names`() {
        video()
        assertNull(jobs().claim(compatOnly, "w1", null), "a compat-only token cannot take a remux job")
        assertNull(jobs().claim(all, "w1", setOf(VideoLane.LADDER)), "the request narrows the token")
        assertEquals(VideoStatus.QUEUED, store.rows.getValue("v1").status)
        assertNotNull(jobs().claim(remuxOnly, "w1", setOf(VideoLane.REMUX, VideoLane.COMPAT)))
    }

    @Test
    fun `two workers never take the same job - the second finds nothing`() {
        video()
        assertNotNull(jobs().claim(all, "w1", null))
        assertNull(jobs().claim(all, "w2", null))
        assertEquals("w1", store.rows.getValue("v1").leaseOwner)
    }

    @Test
    fun `claim first reaps expired leases so a dead worker's job goes to the next one with the attempt counted`() {
        video()
        claimed()
        clock = t0.plus(Duration.ofMinutes(11))

        val again = jobs().claim(all, "w2", null)!!

        assertEquals(2, again.attempt)
        assertEquals("w2", store.rows.getValue("v1").leaseOwner)
    }

    @Test
    fun `the claim and every other write run inside a transaction`() {
        video()
        claimed()
        assertTrue(tx.runs >= 1)
    }

    // ---------- heartbeat ----------

    @Test
    fun `heartbeat extends only the owner's lease and records the progress`() {
        claimed(video())
        clock = t0.plusSeconds(60)

        val until = jobs().heartbeat(all, "w1", "v1", 42)

        assertEquals(clock.plus(Duration.ofMinutes(10)), until)
        assertEquals(42, store.rows.getValue("v1").progress)
        assertFailsWith<VideoLeaseLostException> { jobs().heartbeat(all, "w2", "v1", 50) }
        assertEquals(42, store.rows.getValue("v1").progress)
    }

    @Test
    fun `heartbeat on a lease that was reaped is a lost lease`() {
        claimed(video())
        clock = t0.plus(Duration.ofMinutes(11))
        jobs().claim(all, "w2", null)   // 거둬서 w2 가 집는다
        assertFailsWith<VideoLeaseLostException> { jobs().heartbeat(all, "w1", "v1", 1) }
    }

    // ---------- upload-urls ----------

    @Test
    fun `upload-urls signs only the lane's own names under this video's folder, with the immutable cache header`() {
        claimed(video())
        val urls = jobs().uploadUrls(all, "w1", "v1", listOf("o-$hash/index.m3u8", "o-$hash/init.mp4", "o-$hash/s00000.m4s", "p-$hash.jpg"))

        assertEquals(4, urls.size)
        assertEquals(
            listOf("videos/p/v1/o-$hash/index.m3u8", "videos/p/v1/o-$hash/init.mp4", "videos/p/v1/o-$hash/s00000.m4s", "videos/p/v1/p-$hash.jpg"),
            storage.signedUploads.map { it.key.value },
        )
        assertTrue(storage.signedUploads.all { it.cacheControl == "private, max-age=31536000, immutable" && it.expiresIn == Duration.ofHours(2) })
        assertEquals("application/vnd.apple.mpegurl", storage.signedUploads[0].contentType)
        assertEquals("video/mp4", storage.signedUploads[2].contentType)
        assertEquals("image/jpeg", storage.signedUploads[3].contentType)
    }

    @Test
    fun `a public video's results are signed under the public area with a public cache header`() {
        claimed(video(visibility = VideoVisibility.PUBLIC))
        jobs().uploadUrls(all, "w1", "v1", listOf("o-$hash/index.m3u8"))
        assertEquals("public/v/v1/o-$hash/index.m3u8", storage.signedUploads.single().key.value)
        assertEquals("public, max-age=31536000, immutable", storage.signedUploads.single().cacheControl)
    }

    @Test
    fun `upload-urls signs nothing when any name is outside the allowed shape - traversal, a whole mp4, another video's folder, another lane`() {
        claimed(video())
        listOf(
            "../v2/o-$hash/index.m3u8", "o-$hash/../../u/v1", "movie.mp4", "o-$hash.mp4", "o-$hash/video.mp4", "v2/o-$hash/index.m3u8", "/o-$hash/index.m3u8",
            "c-$hash/index.m3u8",   // 호환 단은 호환 차선 몫
            "h-${"a".repeat(32)}/master.m3u8",   // 사다리는 사다리 차선 몫
        ).forEach { bad ->
            assertFailsWith<VideoPathNotAllowedException>(bad) { jobs().uploadUrls(all, "w1", "v1", listOf("o-$hash/index.m3u8", bad)) }
        }
        assertTrue(storage.signedUploads.isEmpty(), "one bad name refuses the whole request - nothing is signed")
        assertFailsWith<VideoWorkerInvalidException> { jobs().uploadUrls(all, "w1", "v1", emptyList()) }
    }

    @Test
    fun `upload-urls needs the lease and the lane`() {
        claimed(video())
        assertFailsWith<VideoLeaseLostException> { jobs().uploadUrls(all, "w2", "v1", listOf("o-$hash/index.m3u8")) }
        assertFailsWith<VideoWorkerForbiddenException> { jobs().uploadUrls(compatOnly, "w1", "v1", listOf("o-$hash/index.m3u8")) }
        assertTrue(storage.signedUploads.isEmpty())
    }

    // ---------- complete: remux ----------

    @Test
    fun `complete verifies the folder, makes the video playable, queues the tracks and keeps the uploaded file for an hour`() {
        val v = video()
        claimed(v)
        val bytes = putFolder(v, "o-$hash")
        storage.objects["videos/p/v1/p-$hash.jpg"] = 50
        val result = remuxResult(bytes).copy(poster = ResultFile("p-$hash.jpg", 50, width = 1280, height = 720))
        clock = t0.plusSeconds(30)

        jobs().complete(all, "w1", "v1", result)

        val row = store.rows.getValue("v1")
        assertEquals(VideoStatus.PLAYABLE, row.status)
        assertEquals("videos/p/v1/o-$hash/index.m3u8", row.origKey)
        assertEquals(330, row.origBytes)
        assertEquals(sha, row.origSha256)
        assertEquals(1080, row.srcWidth)
        assertEquals(180_000, row.srcDurationMs)
        assertEquals("videos/p/v1/p-$hash.jpg", row.posterKey)
        assertEquals(TrackStatus.QUEUED, row.compatStatus)
        assertEquals(TrackStatus.QUEUED, row.ladderStatus)
        assertNull(row.leaseOwner)
        assertEquals(listOf(VideoFinalizeEvent.PLAYABLE, VideoFinalizeEvent.UPLOAD_CLEANUP), followUps.events("v1"))
        assertEquals(Duration.ofHours(1), followUps.finalized.last().third)
        // 데이터 손실 방지: 원 파일은 이 호출에서 지우지 않는다 — 한 시간 뒤 후속이 지운다
        assertTrue(storage.deleted.isEmpty())
        assertEquals("videos/u/v1", row.uploadKey)
    }

    @Test
    fun `tracks are decided when the video becomes playable - compatible, small and ladder-off cases`() {
        val v = video()
        claimed(v)
        jobs().complete(all, "w1", "v1", remuxResult(putFolder(v, "o-$hash"), compatible = true, p = probe.copy(width = 640, height = 480)))
        store.rows.getValue("v1").also {
            assertEquals(TrackStatus.SKIPPED, it.compatStatus)
            assertEquals(it.origKey, it.compatKey, "the original tier is the compat tier")
            assertEquals(TrackStatus.SKIPPED, it.ladderStatus, "short side 480 or less has no lower rung")
        }
        assertEquals(0, followUps.wakes, "nothing queued - nothing to wake")

        props = VideoProperties(enabled = true, ladder = false)
        val w = video("v2")
        claimed(w, workerId = "w2")
        jobs().complete(all, "w2", "v2", remuxResult(putFolder(w, "o-$hash")))
        assertEquals(TrackStatus.NONE, store.rows.getValue("v2").ladderStatus)
        assertEquals(TrackStatus.QUEUED, store.rows.getValue("v2").compatStatus)
    }

    @Test
    fun `a wake is requested only when the waker is on and a track was queued`() {
        waker = object : VideoWaker {
            override val enabled = true
            override fun wake() = WakeTicket("exec-1")
        }
        val v = video()
        claimed(v)
        jobs().complete(all, "w1", "v1", remuxResult(putFolder(v, "o-$hash")))
        assertEquals(1, followUps.wakes)
    }

    @Test
    fun `complete refuses a folder hash that differs from the content hash and leaves the video leased, files untouched`() {
        val v = video()
        claimed(v)
        val bytes = putFolder(v, "o-ffffffffffffffff")

        assertFailsWith<VideoResultMismatchException> { jobs().complete(all, "w1", "v1", remuxResult(bytes, name = "o-ffffffffffffffff/index.m3u8")) }

        assertEquals(VideoStatus.REMUXING, store.rows.getValue("v1").status)
        assertTrue(followUps.finalized.isEmpty() && storage.deleted.isEmpty())
    }

    @Test
    fun `complete refuses a folder that is incomplete or whose size differs from the report`() {
        val v = video()
        claimed(v)
        val bytes = putFolder(v, "o-$hash")
        assertFailsWith<VideoResultMismatchException> { jobs().complete(all, "w1", "v1", remuxResult(bytes + 1)) }
        storage.objects.remove("videos/p/v1/o-$hash/init.mp4")
        assertFailsWith<VideoResultMismatchException> { jobs().complete(all, "w1", "v1", remuxResult(bytes - 20)) }
        assertEquals(VideoStatus.REMUXING, store.rows.getValue("v1").status)
    }

    @Test
    fun `complete refuses names outside the lane and a result of another lane`() {
        val v = video()
        claimed(v)
        val bytes = putFolder(v, "o-$hash")
        assertFailsWith<VideoPathNotAllowedException> { jobs().complete(all, "w1", "v1", remuxResult(bytes, name = "o-$hash/s00000.m4s")) }
        assertFailsWith<VideoWorkerForbiddenException> { jobs().complete(compatOnly, "w1", "v1", remuxResult(bytes)) }
        assertEquals(VideoStatus.REMUXING, store.rows.getValue("v1").status)
    }

    @Test
    fun `the same complete twice gives the same result and follows up once`() {
        val v = video()
        claimed(v)
        val result = remuxResult(putFolder(v, "o-$hash"))
        jobs().complete(all, "w1", "v1", result)
        val after = store.rows.getValue("v1")
        val events = followUps.finalized.toList()

        jobs().complete(all, "w1", "v1", result)

        assertEquals(after, store.rows.getValue("v1"))
        assertEquals(events, followUps.finalized)
    }

    @Test
    fun `a late complete from a worker that lost its lease is ignored and the new owner finishes`() {
        val v = video()
        claimed(v)
        val bytes = putFolder(v, "o-$hash")
        clock = t0.plus(Duration.ofMinutes(11))
        jobs().claim(all, "w2", null)!!   // w1 은 임대를 잃었다

        assertFailsWith<VideoLeaseLostException> { jobs().complete(all, "w1", "v1", remuxResult(bytes)) }
        assertEquals(VideoStatus.REMUXING, store.rows.getValue("v1").status)
        assertEquals("w2", store.rows.getValue("v1").leaseOwner)
        assertTrue(followUps.finalized.isEmpty())

        jobs().complete(all, "w2", "v1", remuxResult(bytes))
        assertEquals(VideoStatus.PLAYABLE, store.rows.getValue("v1").status)
        // 이미 끝난 일에 오는 늦은 결과는 조용히 무시된다
        jobs().complete(all, "w1", "v1", remuxResult(bytes))
        assertEquals(VideoStatus.PLAYABLE, store.rows.getValue("v1").status)
    }

    // ---------- complete: tracks ----------

    @Test
    fun `a private video's compat and ladder jobs can now be claimed - the input is a token-signed playlist address`() {
        val ready = playable()   // PRIVATE · 호환 단 대기
        val tokens = VideoPlaybackTokens("k".repeat(40), TimeProvider { clock })
        val storageInputs = StorageVideoWorkerInputs(VideoKeys(props, StorageWebProperties()), tokens, { StoragePublicProperties() })

        val job = jobs(storageInputs).claim(all, "w9", null)!!

        assertEquals(VideoLane.COMPAT, job.lane)
        val token = job.inputUrl.removePrefix("/api/v1/videos/play/").substringBefore('/')
        assertEquals(ready.id, tokens.verify(token)?.videoId)
        assertTrue(job.inputUrl.endsWith("/o-$hash/index.m3u8"), job.inputUrl)
        assertEquals(clock.plus(props.worker.urlTtl), tokens.verify(token)?.expiresAt)
    }


    @Test
    fun `a compat job reads the original tier playlist and complete marks the compat tier ready`() {
        val ready = playable()
        val job = jobs().claim(all, "w9", null)!!
        assertEquals(VideoLane.COMPAT, job.lane)
        assertEquals("https://cdn.example.com/${ready.origKey}", job.inputUrl)
        assertEquals("o-$hash", job.origFolder)
        val bytes = putFolder(ready, "c-$hash")

        jobs().complete(all, "w9", "v1", VideoJobResult(compat = ResultFile("c-$hash/index.m3u8", bytes)))

        store.rows.getValue("v1").also {
            assertEquals(TrackStatus.READY, it.compatStatus)
            assertEquals("videos/p/v1/c-$hash/index.m3u8", it.compatKey)
            assertEquals(VideoStatus.PLAYABLE, it.status)
        }
        assertTrue(VideoFinalizeEvent.COMPAT_READY in followUps.events("v1") && VideoFinalizeEvent.SWEEP in followUps.events("v1"))
    }

    @Test
    fun `a ladder job gets a server-chosen build and complete needs a master playlist in that folder`() {
        val ready = playable()
        store.rows["v1"] = ready.copy(compatStatus = TrackStatus.SKIPPED)
        val job = jobs().claim(all, "w9", null)!!
        assertEquals(VideoLane.LADDER, job.lane)
        val build = assertNotNull(job.build)
        assertTrue(Regex("^[0-9a-f]{32}$").matches(build))
        val rungs = listOf(LadderRung("720", 720, 2_800_000))

        assertFailsWith<VideoResultMismatchException> { jobs().complete(all, "w9", "v1", VideoJobResult(ladderBuild = build, rungs = rungs)) }   // 아직 목록이 없다
        storage.objects["videos/p/v1/h-$build/master.m3u8"] = 5
        storage.objects["videos/p/v1/h-$build/v0.m3u8"] = 7
        assertFailsWith<VideoResultMismatchException> { jobs().complete(all, "w9", "v1", VideoJobResult(ladderBuild = "f".repeat(32), rungs = rungs)) }

        jobs().complete(all, "w9", "v1", VideoJobResult(ladderBuild = build, rungs = rungs))

        store.rows.getValue("v1").also {
            assertEquals(TrackStatus.READY, it.ladderStatus)
            assertEquals(12, it.ladderBytes)
            assertEquals("""[{"name":"720","h":720,"bw":2800000}]""", it.ladderRungs)
        }
    }

    // ---------- fail ----------

    @Test
    fun `fail with retry puts the job back, without retry the video fails and the owner is told`() {
        claimed(video())
        jobs().fail(all, "w1", "v1", VideoFailCode.PROCESSING_FAILED, "ffmpeg exited 1", retry = true)
        assertEquals(VideoStatus.QUEUED, store.rows.getValue("v1").status)
        assertTrue(followUps.finalized.isEmpty())

        jobs().claim(all, "w1", null)!!
        jobs().fail(all, "w1", "v1", VideoFailCode.CORRUPT, "bad packets", retry = false)
        store.rows.getValue("v1").also {
            assertEquals(VideoStatus.FAILED, it.status)
            assertEquals("CORRUPT", it.failCode)
        }
        assertTrue(VideoFinalizeEvent.FAILED in followUps.events("v1"))
    }

    @Test
    fun `fail refuses codes outside the table, the code a server alone may set, and a worker without the lease`() {
        claimed(video())
        assertFailsWith<VideoFailCodeInvalidException> { jobs().fail(all, "w1", "v1", "WHATEVER", null, false) }
        assertFailsWith<VideoFailCodeInvalidException> { jobs().fail(all, "w1", "v1", VideoFailCode.LEASE_EXPIRED, null, false) }
        assertFailsWith<VideoLeaseLostException> { jobs().fail(all, "w2", "v1", VideoFailCode.CORRUPT, null, false) }
        assertEquals(VideoStatus.REMUXING, store.rows.getValue("v1").status)
    }

    @Test
    fun `a failed track fails only the track - the playable video and its original tier stay`() {
        val ready = playable()
        val job = jobs().claim(all, "w9", null)!!
        assertEquals(VideoLane.COMPAT, job.lane)

        jobs().fail(all, "w9", "v1", VideoFailCode.PROCESSING_FAILED, "x264 died", retry = false)

        store.rows.getValue("v1").also {
            assertEquals(VideoStatus.PLAYABLE, it.status)
            assertEquals(TrackStatus.FAILED, it.compatStatus)
            assertEquals(ready.origKey, it.origKey)
            assertEquals(ready.origBytes, it.origBytes)
        }
        assertTrue(storage.deleted.isEmpty(), "a failing track never deletes anything")
        assertTrue(VideoFinalizeEvent.TRACK_FAILED in followUps.events("v1"))
        assertContentEquals(listOf("videos/p/v1/o-$hash/index.m3u8"), listOf(store.rows.getValue("v1").origKey))
    }

    @Test
    fun `an unknown job id is a 404 for every call`() {
        assertFailsWith<VideoNotFoundException> { jobs().heartbeat(all, "w1", "nope", null) }
        assertFailsWith<VideoNotFoundException> { jobs().uploadUrls(all, "w1", "nope", listOf("p-$hash.jpg")) }
        assertFailsWith<VideoNotFoundException> { jobs().fail(all, "w1", "nope", VideoFailCode.CORRUPT, null, false) }
    }

    @Test
    fun `a worker id with odd characters is refused`() {
        video()
        assertFailsWith<VideoWorkerInvalidException> { jobs().claim(all, "bad id\n", null) }
    }
}
