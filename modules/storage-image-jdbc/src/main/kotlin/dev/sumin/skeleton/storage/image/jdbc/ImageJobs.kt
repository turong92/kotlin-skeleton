package dev.sumin.skeleton.storage.image.jdbc

import dev.sumin.skeleton.jobqueue.jdbc.Job
import dev.sumin.skeleton.jobqueue.jdbc.JobDeadListener
import dev.sumin.skeleton.jobqueue.jdbc.JobHandler
import dev.sumin.skeleton.jobqueue.jdbc.JobQueue
import dev.sumin.skeleton.jobqueue.jdbc.PermanentJobFailureException
import dev.sumin.skeleton.common.upload.UploadImageState
import dev.sumin.skeleton.storage.image.ImageReasons
import dev.sumin.skeleton.storage.image.ImageStatusStore
import dev.sumin.skeleton.storage.image.ImageVariantProcessor
import dev.sumin.skeleton.storage.image.ImageVariantTasks
import org.slf4j.LoggerFactory

const val IMAGE_JOB_TYPE = "storage.image.variants"

/** 작업 줄의 내용 - `{"keyHash":"<sha256 hex 64>"}` 하나. 원본 키(길이 · 문자)를 싣지 않고 상태 줄이 알려 준다 */
private val PAYLOAD = Regex("""\{"keyHash":"([0-9a-f]{64})"}""")

/** [ImageVariantTasks] 를 `job-queue-jdbc` 에 싣는다 - [dev.sumin.skeleton.storage.image.ImageStatusStore.register] 의 트랜잭션 안에서 불려 상태 줄과 함께 커밋된다 */
class JdbcImageVariantTasks(private val queue: JobQueue) : ImageVariantTasks {
    override fun enqueue(keyHash: String) {
        require(PAYLOAD.matches("""{"keyHash":"$keyHash"}""")) { "an image key hash must be 64 hex characters" }
        queue.enqueue(IMAGE_JOB_TYPE, """{"keyHash":"$keyHash"}""")
    }
}

/** `storage.image.variants` 작업 하나 = 이미지 하나. 처리기는 호출 때마다 읽는다(큐 워커가 처리기를 모으는 시점의 빈 순환을 피한다) */
class ImageVariantJobHandler(private val processor: () -> ImageVariantProcessor) : JobHandler {
    override val type: String = IMAGE_JOB_TYPE

    override fun handle(job: Job) {
        val hash = PAYLOAD.matchEntire(job.payloadJson)?.groupValues?.get(1)
            ?: throw PermanentJobFailureException("image job ${job.id} has an unreadable payload")
        processor().process(hash)   // 저장소 · 일시 오류는 던져 큐의 재시도 · 백오프에 맡긴다
    }
}

/** 작업이 DEAD 가 되는 모든 길에서 부른다 - 우리 작업이면 아직 `PENDING` 인 줄을 `FAILED` 로 적는다(원본은 그대로). 던지지 않는다 */
class ImageVariantDeadListener(private val store: () -> ImageStatusStore) : JobDeadListener {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun onDead(job: Job, reason: String) {
        if (job.type != IMAGE_JOB_TYPE) return
        val hash = PAYLOAD.matchEntire(job.payloadJson)?.groupValues?.get(1) ?: return
        try {
            store().markTerminal(hash, UploadImageState.FAILED, ImageReasons.RETRIES_EXHAUSTED)
        } catch (e: Exception) {
            log.warn("image dead-job hook failed job={} exception={}", job.id, e.javaClass.simpleName)
        }
    }
}
