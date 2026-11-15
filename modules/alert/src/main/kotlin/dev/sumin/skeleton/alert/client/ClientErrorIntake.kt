package dev.sumin.skeleton.alert.client

import dev.sumin.skeleton.alert.AlertProperties
import dev.sumin.skeleton.alert.BuiltInAlertKind
import dev.sumin.skeleton.alert.OwnerAlerts
import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.web.RateLimitStore
import java.io.InputStream
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * 클라이언트 오류 받기: **한도 → 크기 → 모양 → 로그 → 몰림 세기** 순서. 한도를 맨 앞에 두어 쓰레기 본문도 호출자의 한 번을 쓴다(공짜로 다시 보낼 수 없다).
 * 저장 표는 없다 — 남는 것은 구조화 로그와, 몰렸을 때의 경보 하나다.
 */
class ClientErrorIntake(
    private val settings: AlertProperties.ClientErrors,
    private val limits: RateLimitStore,
    private val time: TimeProvider,
    private val surge: ClientErrorSurge,
    private val sink: (ClientErrorReport) -> Unit = ClientErrorLog::write,
) {
    /** [declaredLength] 는 `Content-Length`(없으면 -1). 선언이 없거나 거짓이어도 [InputStream] 은 상한 +1 바이트까지만 읽는다 */
    fun receive(clientKey: String, userAgent: String?, declaredLength: Long, body: InputStream) {
        val now = time.now()
        val decision = limits.consume("client-error:$clientKey", settings.perClient, settings.window.toMillis(), now)
        if (!decision.allowed) {
            throw ClientErrorException(ClientErrorCode.TOO_MANY, mapOf("retryAfterSeconds" to Duration.between(now, decision.resetAt).seconds.coerceAtLeast(1)))
        }
        if (declaredLength > settings.maxBodyBytes) throw ClientErrorException(ClientErrorCode.TOO_LARGE)
        val bytes = body.readNBytes(settings.maxBodyBytes + 1)
        if (bytes.size > settings.maxBodyBytes) throw ClientErrorException(ClientErrorCode.TOO_LARGE)
        val report = ClientErrorSanitizer.parse(bytes, userAgent)
        sink(report)
        surge.record(report)
    }
}

/** 받은 오류 한 건 = 로그 한 줄. 필드는 key-value 로 달려(구조화 로그 `ecs` · `logstash` 에 그대로 실린다) 줄 모양 로그에는 요약만 보인다 */
object ClientErrorLog {
    private val log = LoggerFactory.getLogger("skeleton.client-error")

    fun write(report: ClientErrorReport) {
        var event = log.atWarn()
        fun put(key: String, value: Any?) { if (value != null) event = event.addKeyValue(key, value) }
        put("clientPlatform", report.platform)
        put("clientSource", report.source)
        put("clientName", report.name)
        put("clientRoute", report.route)
        put("clientRelease", report.release)
        put("clientTraceId", report.clientTraceId)
        put("clientCount", report.count)
        put("clientUserAgent", report.userAgent)
        put("clientStack", report.stack)
        put("clientComponentStack", report.componentStack)
        event.log("client error [{}] {}", report.platform ?: "unknown", report.message)
    }
}

/**
 * 몰림 — 창 안에 [AlertProperties.Surge.threshold] 건 이상 받으면 `CLIENT_ERROR_SURGE` 경보를 내고 창을 비운다. 건마다 경보를 내지 않는다:
 * 공개 경로라 아무나 다른 글을 보낼 수 있고, 한 건짜리는 로그에 있으면 된다. 같은 종류의 경보는 `OwnerAlerts` 가 최소 간격 안에서 다시 접는다. 메모리(다시 켜면 비어 있다).
 */
class ClientErrorSurge(
    private val alerts: OwnerAlerts,
    private val settings: AlertProperties.Surge,
    private val time: TimeProvider,
    private val handoff: (Runnable) -> Unit = { Thread.ofVirtual().name("alert-client-surge").start(ProjectContext.carry(it)) },
) {
    private val hitsByProject = ConcurrentHashMap<String, ArrayDeque<Instant>>()

    fun record(latest: ClientErrorReport) {
        val hits = hitsByProject.computeIfAbsent(ProjectContext.scopedKey("")) { ArrayDeque() }
        val count: Int
        synchronized(hits) {
            val now = time.now()
            val from = now.minus(settings.window)
            while (hits.isNotEmpty() && hits.first() <= from) hits.removeFirst()
            hits.addLast(now)
            if (hits.size < settings.threshold) return
            count = hits.size
            hits.clear()
        }
        val where = listOfNotNull(latest.platform, latest.release?.let { "release $it" }, latest.route).joinToString(" ")
        val trace = latest.clientTraceId?.let { " (last request traceId=$it)" }.orEmpty()
        val detail = "$count client errors within ${settings.window.seconds}s. Latest: [$where] ${latest.message}$trace - search the server log for \"client error\""
        handoff(Runnable { alerts.emit(BuiltInAlertKind.CLIENT_ERROR_SURGE, detail = detail, immediate = true) })
    }
}
