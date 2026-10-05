package dev.sumin.skeleton.alert

import dev.sumin.skeleton.common.logging.LogMasker
import dev.sumin.skeleton.common.time.TimeProvider
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationFailedEvent
import org.springframework.context.ApplicationListener
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 5xx 몰림 — 창(기본 1분) 안에 5xx 가 문턱(기본 20건) 이상이면 경보를 내고 창을 비운다(다시 문턱까지 쌓여야 또 낸다).
 * 메모리다 — 서버를 다시 켜면 비어 있다. 5xx 가 날 때만 부르므로 정상 요청에는 비용이 없다.
 */
class ServerErrorSurge(
    private val alerts: OwnerAlerts,
    private val settings: AlertProperties.Surge,
    private val time: TimeProvider,
    /** 경보 기록은 요청 스레드가 아니라 여기서 — 기록이 DB 를 기다려도 5xx 를 내는 요청이 더 늦어지지 않는다 */
    private val handoff: (Runnable) -> Unit = { Thread.ofVirtual().name("alert-surge").start(it) },
) {
    private val hits = ArrayDeque<Instant>()

    fun record() {
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
        val detail = "$count server errors (5xx) within ${settings.window.seconds}s - follow the traceId in the server log"
        handoff(Runnable { alerts.emit(BuiltInAlertKind.SERVER_ERROR_SURGE, detail = detail, immediate = true) })
    }
}

/** 응답이 5xx 이거나 예외가 밖으로 나가면 [ServerErrorSurge] 에 센다 — 던진 예외는 그대로 다시 던진다 */
class ServerErrorSurgeFilter(private val surge: ServerErrorSurge) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        try {
            chain.doFilter(request, response)
        } catch (e: Throwable) {
            safeRecord()
            throw e
        }
        if (response.status >= 500) safeRecord()
    }

    private fun safeRecord() = try {
        surge.record()
    } catch (e: Exception) {
        LoggerFactory.getLogger(javaClass).warn("5xx surge count failed: {}", e.javaClass.simpleName)
    }
}

/**
 * 컨텍스트가 못 떴을 때의 **마지막 시도** — 앱이 안 떴으니 DB · 큐 · 메일이 없다. 그래서 웹훅으로 **직접** 한 번(3초 제한) + ERROR 로그.
 * 던지지 않는다: 기동 실패의 원래 오류를 가리지 않게. 메시지는 `LogMasker` 를 거친다.
 */
object StartupFailureAlert {
    private val log = LoggerFactory.getLogger(StartupFailureAlert::class.java)

    /** 보냈으면 true. [skipProfiles] 중 하나가 활성이거나 웹훅 주소가 없으면 false */
    fun attempt(webhookUrl: String, profiles: List<String>, skipProfiles: List<String>, message: String?): Boolean {
        if (profiles.any { it in skipProfiles }) return false
        val text = LogMasker.current.mask(message.orEmpty()).replace(Regex("\\s+"), " ").trim().take(1500)
        val env = profiles.firstOrNull() ?: "default"
        log.error("STARTUP FAILED [{}] - the server did not start: {}", env, text)
        val uri = AlertProperties(webhookUrl = webhookUrl).webhookUri
        if (uri == null) {
            log.error("No channel for the startup failure (skeleton.alert.webhook-url is empty or not an http(s) address) - only this line remains")
            return false
        }
        val kind = BuiltInAlertKind.STARTUP_FAILED
        return DirectWebhook.post(uri, "[$env][${kind.severity}] ${kind.title}", text, kind.severity, "startup failure alert")
    }
}

/** `META-INF/spring.factories` 로 등록한다 — 컨텍스트가 없어도 이 시점의 환경(`skeleton.alert.*`)을 읽는다 */
class StartupFailureListener : ApplicationListener<ApplicationFailedEvent> {
    override fun onApplicationEvent(event: ApplicationFailedEvent) {
        try {
            val env = event.applicationContext?.environment ?: return
            val props = org.springframework.boot.context.properties.bind.Binder.get(env)
                .bind("skeleton.alert", AlertProperties::class.java).orElse(null) ?: return
            if (!props.enabled || !props.startupFailure.enabled) return
            StartupFailureAlert.attempt(props.webhookUrl, env.activeProfiles.toList(), props.startupFailure.skipProfiles, event.exception.message)
        } catch (e: Exception) {
            LoggerFactory.getLogger(javaClass).warn("startup failure alert failed: {}", e.javaClass.simpleName)
        }
    }
}
