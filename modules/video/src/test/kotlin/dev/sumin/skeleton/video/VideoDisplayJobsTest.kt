package dev.sumin.skeleton.video

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.storage.StorageWebProperties
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 움직이는 이미지 표시본 변환 차선 - 일꾼이 사본 대신 MP4 · WebP 를 올린다.
 * 사고 나는 것만: 허용 이름 밖은 서명하지 않는다 · 사본 파일을 덮어쓰지 않는다 · 임대를 잃은 일꾼의 결과와 크기가 안 맞는 결과는 표시본을 바꾸지 않는다 ·
 * 실패하면 표시본은 사본으로 남는다 · 다른 차선 토큰은 이 차선에 못 간다
 */
class VideoDisplayJobsTest {
    private val t0 = Instant.parse("2026-11-04T00:00:00Z")
    private var clock = t0
    private val hash = "ab".repeat(32)
    private val objectKey = "uploads/7/g/x.gif"
    private val folder = "variants/${hash.take(32)}/"

    private val videoStore = FakeVideoStore()
    private val displayStore = FakeDisplayJobStore()
    private val target = FakeDisplayTarget(folder)
    private val storage = FakeStorage()
    private val props = VideoProperties(enabled = true)

    private val animated = VideoWorker(1, "anim", setOf(VideoLane.ANIMATED), t0, null, null)
    private val videoOnly = animated.copy(id = 2, name = "video-only", lanes = setOf(VideoLane.REMUX, VideoLane.COMPAT, VideoLane.LADDER))

    private val display = VideoDisplayJobs(props, displayStore, target, storage, TimeProvider { clock })
    private val jobs = VideoJobs(
        props, videoStore, storage, VideoKeys(props, StorageWebProperties()), VideoWorkerInputs { _, _ -> "unused" }, FakeFollowUps(), FakeTransactions(), NoopWaker, TimeProvider { clock }, display,
    )

    private fun queued() {
        storage.objects[objectKey] = 5000
        target.copyExt[hash] = "gif"
        displayStore.enqueue(hash, objectKey, t0)
    }

    private fun claimed(workerId: String = "w1"): VideoJob = jobs.claim(animated, workerId, null)!!

    @Test
    fun `claim hands out the original image as a signed read address - the job id is the image's key hash`() {
        queued()

        val job = claimed()

        assertEquals(hash, job.jobId)
        assertEquals(VideoLane.ANIMATED, job.lane)
        assertEquals(5000, job.inputBytes)
        assertEquals(1, job.attempt)
        assertTrue(job.inputUrl.startsWith("http://s3.test/$objectKey"), job.inputUrl)
        assertFalse(job.verifyDecode)
        assertNull(jobs.claim(animated, "w2", null), "한 일은 한 일꾼에게만")
    }

    @Test
    fun `workers without the animated lane never take or touch the job - a video-only token is forbidden`() {
        queued()

        assertNull(jobs.claim(videoOnly, "w1", null), "다른 차선 토큰은 이 일을 집지 않는다")
        claimed()
        assertFailsWith<VideoWorkerForbiddenException> { jobs.uploadUrls(videoOnly, "w1", hash, listOf("display.mp4")) }
        assertFailsWith<VideoWorkerForbiddenException> { jobs.heartbeat(videoOnly, "w1", hash, 10) }
        assertFailsWith<VideoWorkerForbiddenException> { jobs.complete(videoOnly, "w1", hash, VideoJobResult(display = ResultFile("display.mp4", 10))) }
        assertTrue(storage.signedUploads.isEmpty())
    }

    @Test
    fun `upload addresses are signed only for display mp4 or webp under the image's variants folder - anything else signs nothing`() {
        queued()
        claimed()

        val signed = jobs.uploadUrls(animated, "w1", hash, listOf("display.mp4"))

        assertEquals("display.mp4", signed.single().name)
        assertEquals("$folder" + "display.mp4", storage.signedUploads.single().key.value)
        assertEquals("video/mp4", storage.signedUploads.single().contentType)
        storage.signedUploads.clear()
        listOf(listOf("display.gif"), listOf("display.mp4", "../x.mp4"), listOf("o-0123456789abcdef/index.m3u8"), listOf("thumb.jpg"), emptyList()).forEach { names ->
            assertFailsWith<ApplicationException>("names=$names") { jobs.uploadUrls(animated, "w1", hash, names) }
        }
        assertTrue(storage.signedUploads.isEmpty(), "하나라도 어긋나면 하나도 서명하지 않는다")
    }

    @Test
    fun `the copy's own file is never overwritten - a webp original cannot get display webp signed`() {
        queued()
        target.copyExt[hash] = "webp"
        claimed()

        assertFailsWith<VideoPathNotAllowedException> { jobs.uploadUrls(animated, "w1", hash, listOf("display.webp")) }
        assertEquals("display.mp4", jobs.uploadUrls(animated, "w1", hash, listOf("display.mp4")).single().name)
        assertFailsWith<VideoPathNotAllowedException> { jobs.complete(animated, "w1", hash, VideoJobResult(display = ResultFile("display.webp", 10))) }
        assertTrue(target.used.isEmpty())
    }

    @Test
    fun `complete switches the display only when the uploaded file is there with the reported size - and the same call twice changes nothing more`() {
        queued()
        claimed()
        storage.objects["${folder}display.mp4"] = 900

        assertFailsWith<VideoResultMismatchException> { jobs.complete(animated, "w1", hash, VideoJobResult(display = ResultFile("display.mp4", 901))) }
        assertFailsWith<VideoResultMismatchException> { jobs.complete(animated, "w1", hash, VideoJobResult(display = ResultFile("display.webp", 900))) }
        assertTrue(target.used.isEmpty(), "맞지 않는 결과로는 표시본을 바꾸지 않는다")

        jobs.complete(animated, "w1", hash, VideoJobResult(display = ResultFile("display.mp4", 900)))
        jobs.complete(animated, "w1", hash, VideoJobResult(display = ResultFile("display.mp4", 900)))

        assertEquals(listOf(hash to "mp4"), target.used)
        assertEquals(DisplayState.DONE, displayStore.find(hash)!!.state)
    }

    @Test
    fun `a worker that lost its lease cannot switch the display`() {
        queued()
        claimed("w1")
        storage.objects["${folder}display.mp4"] = 900

        assertFailsWith<VideoLeaseLostException> { jobs.complete(animated, "intruder", hash, VideoJobResult(display = ResultFile("display.mp4", 900))) }
        clock = clock.plus(props.worker.leaseDuration).plusSeconds(1)
        val again = jobs.claim(animated, "w2", null)
        assertNotNull(again, "임대가 끝난 일은 다음 claim 이 거둬 다시 준다")
        assertEquals(2, again.attempt)
        assertFailsWith<VideoLeaseLostException> { jobs.complete(animated, "w1", hash, VideoJobResult(display = ResultFile("display.mp4", 900))) }
        assertTrue(target.used.isEmpty())
    }

    @Test
    fun `a failure leaves the display as the copy - retried until the attempts run out then FAILED and never switched`() {
        queued()
        claimed()
        jobs.fail(animated, "w1", hash, "PROCESSING_FAILED", "ffmpeg", retry = true)
        assertEquals(DisplayState.QUEUED, displayStore.find(hash)!!.state)
        claimed()
        jobs.fail(animated, "w1", hash, "PROCESSING_FAILED", "ffmpeg", retry = true)
        claimed()
        jobs.fail(animated, "w1", hash, "PROCESSING_FAILED", "ffmpeg", retry = true)   // 세 번째 시도 - 더는 안 준다

        assertEquals(DisplayState.FAILED, displayStore.find(hash)!!.state)
        assertNull(jobs.claim(animated, "w1", null))
        assertTrue(target.used.isEmpty())
        assertFailsWith<VideoFailCodeInvalidException> { jobs.fail(animated, "w1", hash, "SOMETHING_ELSE", null, retry = false) }
    }

    @Test
    fun `a job whose image is gone or no longer an animated image is dropped at claim - the worker never downloads it`() {
        queued()
        target.copyExt.remove(hash)   // 상태 줄이 지워졌다(원본 회수)

        assertNull(jobs.claim(animated, "w1", null))
        assertEquals(DisplayState.FAILED, displayStore.find(hash)!!.state)
    }

    @Test
    fun `the video lanes are untouched when the display feature is not wired`() {
        val plain = VideoJobs(props, videoStore, storage, VideoKeys(props, StorageWebProperties()), VideoWorkerInputs { _, _ -> "unused" }, FakeFollowUps(), FakeTransactions(), NoopWaker, TimeProvider { clock })
        queued()

        assertNull(plain.claim(animated, "w1", null))
        assertFailsWith<VideoNotFoundException> { plain.uploadUrls(animated, "w1", hash, listOf("display.mp4")) }
        assertEquals(DisplayState.QUEUED, displayStore.find(hash)!!.state)
    }
}
