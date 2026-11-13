package dev.sumin.skeleton.scheduler.web

import dev.sumin.skeleton.scheduler.SkeletonExternalRunResult
import dev.sumin.skeleton.scheduler.SkeletonScheduledTaskRegistrar
import dev.sumin.skeleton.scheduler.SkeletonSchedulerProperties
import java.security.MessageDigest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * 밖의 스케줄러가 작업 하나를 부르는 주소 (mode=EXTERNAL) — `POST {skeleton.scheduler.external.path}/{taskId}`, 그 작업을 요청 안에서 끝까지 돌린다.
 * 인증은 공유 비밀값 헤더 `X-Scheduler-Secret`(상수 시간 비교). EXTERNAL 이 아니거나 비밀값이 비었거나 없는 작업이면 404, 틀리면 401, 작업이 던지면 500(호출자가 재시도).
 * 모드 · 비밀값은 요청마다 읽는다 — 빈 조건으로 걸면 AOT · 네이티브 이미지에서 빌드 때 굳는다 (job-queue 깨우기 주소와 같은 방식).
 */
@RestController
class SchedulerTriggerController(
    private val registrar: SkeletonScheduledTaskRegistrar,
    private val properties: SkeletonSchedulerProperties,
) {
    @PostMapping("\${skeleton.scheduler.external.path:/internal/scheduler/run}/{taskId}")
    fun run(
        @PathVariable taskId: String,
        @RequestHeader(name = SECRET_HEADER, required = false) presented: String?,
    ): ResponseEntity<SchedulerTriggerResponse> {
        val secret = properties.external.secret
        if (properties.mode != SkeletonSchedulerProperties.Mode.EXTERNAL || secret.isBlank()) return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        if (presented == null || !sameSecret(presented, secret)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val started = System.nanoTime()
        val result = try {
            registrar.runExternally(taskId)
        } catch (e: Throwable) {
            // 실패 핸들러는 이미 불렸다(기록됨). 본문에는 예외 종류만 — 메시지에 개인정보가 섞일 수 있다
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(SchedulerTriggerResponse(taskId, false, elapsed(started), e.javaClass.simpleName))
        }
        if (result == SkeletonExternalRunResult.NOT_FOUND) return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        return ResponseEntity.ok(SchedulerTriggerResponse(taskId, result == SkeletonExternalRunResult.RAN, elapsed(started), null))
    }

    private fun elapsed(started: Long) = (System.nanoTime() - started) / 1_000_000

    companion object {
        const val SECRET_HEADER = "X-Scheduler-Secret"

        /** 길이가 달라도 시간이 새지 않게 두 값을 SHA-256 으로 접어 같은 길이로 비교한다 */
        internal fun sameSecret(presented: String, expected: String): Boolean {
            val digest = MessageDigest.getInstance("SHA-256")
            return MessageDigest.isEqual(digest.digest(presented.toByteArray()), digest.digest(expected.toByteArray()))
        }
    }
}

/** ran=false + error=null 이면 락을 못 잡아 건너뛴 것(다른 실행이 도는 중) — 200 이라 재시도하지 않는다 */
data class SchedulerTriggerResponse(val taskId: String, val ran: Boolean, val elapsedMillis: Long, val error: String?)
