package dev.sumin.skeleton.video

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployGuard
import dev.sumin.skeleton.common.time.TimeProvider
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner

/** 일꾼 한 줄 — 토큰은 해시만 저장하므로 여기엔 없다. [lanes] 는 이 토큰이 맡을 수 있는 차선 */
data class VideoWorker(
    val id: Long,
    val name: String,
    val lanes: Set<VideoLane>,
    val createdAt: Instant,
    val lastSeenAt: Instant?,
    val revokedAt: Instant?,
)

/** 발급 응답 — 평문 [token] 은 이때 한 번만 나온다 */
data class IssuedVideoWorker(val worker: VideoWorker, val token: String)

/** 일꾼이 `fail` 로 보낼 수 있는 사유 — 화면 문구 표가 이 이름으로 문구를 고른다 */
object VideoFailCode {
    const val UNSUPPORTED_FORMAT = "UNSUPPORTED_FORMAT"
    const val CORRUPT = "CORRUPT"
    const val LIMITS_EXCEEDED = "LIMITS_EXCEEDED"
    const val TOO_BIG_FOR_WORKER = "TOO_BIG_FOR_WORKER"
    const val PROCESSING_FAILED = "PROCESSING_FAILED"

    /** 서버가 임대 만료 끝에 붙이는 사유 — 일꾼은 못 보낸다 */
    const val LEASE_EXPIRED = "LEASE_EXPIRED"

    val WORKER_REPORTABLE: Set<String> = setOf(UNSUPPORTED_FORMAT, CORRUPT, LIMITS_EXCEEDED, TOO_BIG_FOR_WORKER, PROCESSING_FAILED)
}

/**
 * 일꾼 토큰 발급 · 확인 · 폐기. 토큰은 256비트 무작위라 느린 해시가 필요 없다 — SHA-256 만 저장하고, 평문은 [issue] 의 반환에만 있다.
 * 로그 · 예외 메시지에 토큰을 넣지 않는다
 */
class VideoWorkerService(private val store: VideoWorkerStore, private val time: TimeProvider) {
    private val random = SecureRandom()

    fun issue(name: String, lanes: Set<VideoLane>): IssuedVideoWorker {
        if (!NAME.matches(name)) throw VideoWorkerInvalidException("name")
        if (lanes.isEmpty()) throw VideoWorkerInvalidException("lanes")
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        return IssuedVideoWorker(store.insert(name, lanes, sha256Hex(token), time.now()), token)
    }

    /** 맞는 · 폐기되지 않은 토큰이면 일꾼(마지막 본 시각을 적는다), 아니면 null */
    fun authenticate(token: String): VideoWorker? {
        if (token.isBlank() || token.length > MAX_TOKEN) return null
        val worker = store.findActiveByTokenSha256(sha256Hex(token)) ?: return null
        store.touch(worker.id, time.now())
        return worker
    }

    fun revoke(id: Long): Boolean = store.revoke(id, time.now())

    fun list(): List<VideoWorker> = store.list()

    internal companion object {
        val NAME = Regex("^[A-Za-z0-9._-]{1,64}$")
        const val MAX_TOKEN = 200

        fun sha256Hex(s: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)))
    }
}

/**
 * 설정(`skeleton.video.worker-token-sha256`)에 해시가 있으면 기동 때 그 일꾼을 세 차선으로 한 번 등록한다 — 평문은 서버에 안 온다.
 * 로컬 compose 가 운영자 호출 없이 일꾼을 띄우는 길이다(stage · prod 에서는 [VideoDeployGuard] 가 막는다). 이미 있으면(폐기한 것 포함) 아무것도 안 한다 — 폐기를 되살리지 않는다
 */
class VideoWorkerBootstrap(private val props: VideoProperties, private val store: VideoWorkerStore, private val time: TimeProvider) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) = register()

    fun register() {
        val sha = props.workerTokenSha256.trim().lowercase()
        if (sha.isEmpty() || store.existsByTokenSha256(sha)) return
        store.insert(NAME, VideoLane.entries.toSet(), sha, time.now())
        log.info("video worker registered from skeleton.video.worker-token-sha256 name={}", NAME)
    }

    private companion object {
        const val NAME = "bootstrap"
    }
}

/** 로컬 전용 일꾼 해시(`worker-token-sha256`)가 보호 환경(stage · prod)에 있으면 기동 실패 — 운영의 일꾼은 운영자가 발급한 토큰이다. 메시지에는 속성 이름만 싣는다 */
class VideoDeployGuard(private val props: VideoProperties) : DeployGuard {
    override val name: String = "video"

    override fun problems(context: DeployContext): List<String> =
        if (context.protectedEnv && props.workerTokenSha256.isNotBlank()) {
            listOf("skeleton.video.worker-token-sha256 (<P>_VIDEO_WORKER_TOKEN_SHA256) is a local-only worker registration: clear it and issue the worker a token through the operator API")
        } else {
            emptyList()
        }
}
