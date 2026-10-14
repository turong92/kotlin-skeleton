package dev.sumin.skeleton.jobqueue.jdbc

import dev.sumin.skeleton.common.wake.OnRequestTask
import java.security.MessageDigest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.PostMapping

/**
 * 요청 구동 모드의 깨우기 주소 — Cloud Scheduler 같은 밖의 호출자가 주기로 부르면, 스스로 도는 주기 작업([OnRequestTask])을 한 번씩 깨우고 밀린 일감을 시간 한도 안에서 처리한 뒤 끝낸다.
 * 인증은 공유 비밀값 헤더 `X-Wake-Secret` 하나다(상수 시간 비교). 요청 구동 모드가 아니거나 비밀값이 비어 있으면 주소가 없는 것처럼 404, 틀리면 401 이고 어느 쪽도 일감에 손대지 않는다.
 * 이 빈은 모드와 상관없이 서 있다(서블릿 앱 + 워커가 있으면) — 모드는 요청마다 읽는다. 조건으로 걸면 AOT 이미지에서 빌드 때 굳어 실행 환경변수로 켜도 주소가 없다.
 * 주소는 보안 체인에서 로그인 없이 열린다(호출자는 사람이 아니다) — 막는 것은 이 비밀값뿐이다. 플랫폼 쪽 호출자 인증(Cloud Run 의 IAM)과 겹쳐 써도 된다.
 * 경로는 `skeleton.job-queue.wake.path`(기본 `/internal/jobs/drain`).
 */
@RestController
class JobWakeController(
    private val drainer: JobDrainer,
    private val tasks: List<OnRequestTask>,
    private val properties: JobQueueProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("\${skeleton.job-queue.wake.path:/internal/jobs/drain}")
    // 반환 타입을 구체적으로 적는다(ResponseEntity<Any> 가 아니라) — 네이티브 이미지의 AOT 가 JSON 으로 바뀌는 타입을 반환 타입에서 찾는다 (docs/native-image.md "리플렉션 규칙")
    fun wake(@RequestHeader(name = SECRET_HEADER, required = false) presented: String?): ResponseEntity<WakeResponse> {
        // 모드와 비밀값은 **실행 때** 본다 — 네이티브 · AOT 이미지에서 이 빈이 빌드 때 굳어도, 같은 이미지를 실행 환경변수(mode · secret)로 켜고 끈다 (docs/cloud-run-scale-to-zero.md)
        val secret = properties.wake.secret
        if (properties.mode != JobQueueProperties.Mode.ON_REQUEST || secret.isBlank()) return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        if (presented == null || !sameSecret(presented, secret)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val results = tasks.map { task ->
            val ok = try { task.run(); true } catch (e: Exception) { log.warn("on-request task {} failed: {}", task.name, e.javaClass.simpleName); false }
            WakeTaskResult(task.name, ok)
        }
        val drained = drainer.drain(properties.wake.timeBudget)
        return ResponseEntity.ok(WakeResponse(results, drained.processed, drained.rounds, drained.timedOut, drained.elapsedMillis))
    }

    companion object {
        const val SECRET_HEADER = "X-Wake-Secret"

        /** 길이가 달라도 시간이 새지 않게 두 값을 SHA-256 으로 접어 같은 길이로 비교한다 */
        internal fun sameSecret(presented: String, expected: String): Boolean {
            val digest = MessageDigest.getInstance("SHA-256")
            return MessageDigest.isEqual(digest.digest(presented.toByteArray()), digest.digest(expected.toByteArray()))
        }
    }
}

data class WakeTaskResult(val name: String, val ok: Boolean)

data class WakeResponse(val tasks: List<WakeTaskResult>, val processed: Int, val rounds: Int, val timedOut: Boolean, val elapsedMillis: Long)
