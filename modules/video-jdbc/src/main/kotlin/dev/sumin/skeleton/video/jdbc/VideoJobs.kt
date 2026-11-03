package dev.sumin.skeleton.video.jdbc

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.jobqueue.jdbc.Job
import dev.sumin.skeleton.jobqueue.jdbc.JobHandler
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.jobqueue.jdbc.PermanentJobFailureException
import dev.sumin.skeleton.video.VideoFinalizeEvent
import dev.sumin.skeleton.video.VideoFinalizer
import dev.sumin.skeleton.video.VideoFollowUps
import dev.sumin.skeleton.video.VideoTransactions
import dev.sumin.skeleton.video.VideoWakeRunner
import java.time.Duration
import org.springframework.transaction.support.TransactionTemplate

const val VIDEO_FINALIZE_JOB_TYPE = "video.finalize"
const val VIDEO_WAKE_JOB_TYPE = "video.wake"

private val FINALIZE_PAYLOAD = Regex("""\{"videoId":"([A-Za-z0-9_-]{1,32})","event":"([A-Z_]+)"}""")

/** [VideoFollowUps] 를 `job-queue-jdbc` 에 싣는다 — 부르는 쪽 트랜잭션 안에서 넣으니 영상 줄이 커밋되면 작업도 있고 되돌아가면 함께 사라진다 */
class JobQueueVideoFollowUps(private val queue: JobQueue, private val time: TimeProvider) : VideoFollowUps {
    override fun finalize(videoId: String, event: VideoFinalizeEvent, delay: Duration) {
        require(Regex("[A-Za-z0-9_-]{1,32}").matches(videoId)) { "a video id is a plain identifier" }
        queue.enqueue(VIDEO_FINALIZE_JOB_TYPE, """{"videoId":"$videoId","event":"${event.name}"}""", runAt = if (delay.isZero) null else time.now().plus(delay))
    }

    override fun wake() {
        queue.enqueue(VIDEO_WAKE_JOB_TYPE, "{}")
    }
}

/** `video.finalize` 작업 하나 = 사건 하나. 처리기는 멱등이라 재시도돼도 같은 결과다. [finalizer] 는 호출 때마다 읽는다(빈 순환을 피한다) */
class VideoFinalizeJobHandler(private val finalizer: () -> VideoFinalizer) : JobHandler {
    override val type: String = VIDEO_FINALIZE_JOB_TYPE

    override fun handle(job: Job) {
        val (videoId, event) = FINALIZE_PAYLOAD.matchEntire(job.payloadJson)?.destructured ?: throw PermanentJobFailureException("video.finalize job ${job.id} has an unreadable payload")
        val parsed = VideoFinalizeEvent.entries.firstOrNull { it.name == event } ?: throw PermanentJobFailureException("video.finalize job ${job.id} has an unknown event")
        finalizer().handle(videoId, parsed)
    }
}

/** `video.wake` — 집을 호환 · 사다리 일이 있고 깨우기가 켜졌을 때만 깨운다. 깨우기가 던지면 큐의 재시도 · 백오프에 맡긴다 */
class VideoWakeJobHandler(private val runner: () -> VideoWakeRunner) : JobHandler {
    override val type: String = VIDEO_WAKE_JOB_TYPE

    override fun handle(job: Job) = runner().run()
}

/** [VideoTransactions] 를 스프링 트랜잭션으로 — 이미 트랜잭션 안이면 합류한다 */
class JdbcVideoTransactions(private val template: TransactionTemplate) : VideoTransactions {
    override fun <T> run(block: () -> T): T {
        @Suppress("UNCHECKED_CAST")
        return template.execute { block() } as T
    }
}
