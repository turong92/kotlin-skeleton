package dev.sumin.skeleton.storage.image.ffmpeg

import dev.sumin.skeleton.jobqueue.jdbc.Job
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.jobqueue.jdbc.JobStatus
import dev.sumin.skeleton.jobqueue.jdbc.PermanentJobFailureException
import dev.sumin.skeleton.storage.image.AnimatedDisplays
import dev.sumin.skeleton.storage.image.ImageProperties
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FfmpegJobsTest {
    private val hash = "b".repeat(64)

    private class RecordingQueue : JobQueue {
        val enqueued = mutableListOf<Triple<String, String, Int?>>()
        override fun enqueue(type: String, payloadJson: String, runAt: Instant?, maxAttempts: Int?): Long {
            enqueued += Triple(type, payloadJson, maxAttempts)
            return enqueued.size.toLong()
        }
    }

    private fun job(payload: String) = Job(1, FFMPEG_JOB_TYPE, payload, JobStatus.RUNNING, 1, 3, Instant.EPOCH, null, null, null, Instant.EPOCH, Instant.EPOCH)

    private fun handler(props: FfmpegProperties = FfmpegProperties(path = "/no/such/ffmpeg"), prepare: (MemoryStorage, MemoryImageStore) -> Unit = { _, _ -> }): FfmpegAnimatedJobHandler {
        val storage = MemoryStorage()
        val store = MemoryImageStore()
        prepare(storage, store)
        return FfmpegAnimatedJobHandler(AnimatedConversion(storage, store, AnimatedDisplays(store, ImageProperties()), props))
    }

    @Test
    fun `변환 요청은 상태 줄 열쇠만 싣고 시도 횟수는 설정 값으로 큐에 한 줄 넣는다`() {
        val queue = RecordingQueue()

        FfmpegAnimatedDisplayConverter(queue, FfmpegProperties(maxAttempts = 2)).requestConversion(hash, "uploads/pic.gif")

        assertEquals(listOf(Triple<String, String, Int?>(FFMPEG_JOB_TYPE, """{"keyHash":"$hash"}""", 2)), queue.enqueued)
    }

    @Test
    fun `열쇠 모양이 아니면 큐에 넣지 않는다`() {
        val queue = RecordingQueue()

        assertFailsWith<IllegalArgumentException> { FfmpegAnimatedDisplayConverter(queue, FfmpegProperties()).requestConversion("""x"}""", "k") }

        assertEquals(0, queue.enqueued.size)
    }

    @Test
    fun `변환할 것이 없다고 끝난 일은 던지지 않는다 - 다시 하거나 DEAD 로 남기지 않는다`() {
        handler().handle(job("""{"keyHash":"$hash"}"""))   // 상태 줄이 없다
    }

    @Test
    fun `ffmpeg 가 실패하면 던진다 - 큐가 다시 하고 소진되면 DEAD 로 끝난다`() {
        val handler = handler { storage, store ->
            storage.objects["uploads/pic"] = "GIF89a".toByteArray().copyOf(2000)
            store.readyAnimated(hash, "uploads/pic", "gif")
        }

        assertFailsWith<FfmpegFailedException> { handler.handle(job("""{"keyHash":"$hash"}""")) }
    }

    @Test
    fun `읽을 수 없는 작업 내용은 다시 하지 않는 실패다`() {
        assertFailsWith<PermanentJobFailureException> { handler().handle(job("""{"keyHash":"nope"}""")) }
    }
}
