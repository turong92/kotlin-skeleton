package dev.sumin.skeleton.storage.image.ffmpeg

import dev.sumin.skeleton.jobqueue.jdbc.Job
import dev.sumin.skeleton.jobqueue.jdbc.JobHandler
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.jobqueue.jdbc.PermanentJobFailureException
import dev.sumin.skeleton.storage.image.AnimatedDisplayConverter

const val FFMPEG_JOB_TYPE = "storage.image.ffmpeg.animated"

/** 작업 줄의 내용 - `{"keyHash":"<sha256 hex 64>"}` 하나. 원본 키를 싣지 않고 상태 줄이 알려 준다(`storage-image-jdbc` 의 변환 작업과 같다) */
private val PAYLOAD = Regex("""\{"keyHash":"([0-9a-f]{64})"}""")

/** [AnimatedDisplayConverter] 를 `job-queue-jdbc` 에 싣는다 - 요청은 줄 하나를 넣을 뿐이고 변환은 큐의 일꾼이 한다(서버가 여럿이어도 한 줄은 한 서버가 집는다) */
class FfmpegAnimatedDisplayConverter(private val queue: JobQueue, private val props: FfmpegProperties) : AnimatedDisplayConverter {
    override fun requestConversion(keyHash: String, objectKey: String) {
        val payload = """{"keyHash":"$keyHash"}"""
        require(PAYLOAD.matches(payload)) { "an image key hash must be 64 hex characters" }
        queue.enqueue(FFMPEG_JOB_TYPE, payload, maxAttempts = props.maxAttempts)
    }
}

/** `storage.image.ffmpeg.animated` 작업 하나 = 이미지 하나. 던지는 것은 다시 해 볼 만한 실패뿐이고, 소진되면 큐가 DEAD 로 끝낸다(사본은 그대로) */
class FfmpegAnimatedJobHandler(private val conversion: AnimatedConversion) : JobHandler {
    override val type: String = FFMPEG_JOB_TYPE

    override fun handle(job: Job) {
        val hash = PAYLOAD.matchEntire(job.payloadJson)?.groupValues?.get(1)
            ?: throw PermanentJobFailureException("ffmpeg animated job ${job.id} has an unreadable payload")
        conversion.convert(hash)
    }
}
