package dev.sumin.skeleton.alert

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper

/** 경보가 나가는 길 하나. 실패하면 던진다 — 재시도 · 다른 채널과의 격리는 호출하는 쪽이 한다 */
interface AlertChannel {
    val name: String
    fun send(message: AlertMessage)
}

/** 글 꾸미기 — 웹훅 · 메일이 같은 줄을 쓴다 */
object AlertText {
    fun headline(m: AlertMessage) = "[${m.environment}][${m.severity}] ${m.title}"

    fun body(m: AlertMessage): String = buildList {
        if (m.key.isNotEmpty()) add("Subject: ${m.key}")
        if (m.detail.isNotEmpty()) add(m.detail)
        if (m.suppressed > 0) add("${m.suppressed} more of the same alert were folded since the last one.")
        add("At: ${m.occurredAt}")
    }.joinToString("\n")

    fun color(severity: AlertSeverity) = when (severity) {
        AlertSeverity.INFO -> 0x3498DB
        AlertSeverity.WARN -> 0xF1C40F
        AlertSeverity.ERROR -> 0xE67E22
        AlertSeverity.CRITICAL -> 0xE74C3C
    }
}

/**
 * Discord 호환 웹훅(`content` + `embeds`). 주소는 **설정에서만** 온다(SSRF — 요청 값이 주소가 되지 않는다).
 * JDK HTTP 클라이언트, 리다이렉트는 따라가지 않는다. 실패 예외에는 상태만 있고 주소는 없다.
 */
class WebhookAlertChannel(private val uri: URI, private val timeout: Duration) : AlertChannel {
    override val name = "webhook"

    override fun send(message: AlertMessage) {
        WebhookHttp.post(uri, AlertText.headline(message), AlertText.body(message), message.severity, timeout)
    }
}

internal object WebhookHttp {
    private val json = JsonMapper.builder().build()
    private const val CONTENT_MAX = 2000
    private const val TITLE_MAX = 256
    private const val DESCRIPTION_MAX = 4000

    /** 2xx 가 아니거나 닿지 않으면 [AlertDeliveryException] — 주소는 싣지 않는다 */
    fun post(uri: URI, headline: String, description: String, severity: AlertSeverity, timeout: Duration) {
        val body = json.writeValueAsString(
            mapOf(
                "content" to headline.take(CONTENT_MAX),
                "embeds" to listOf(
                    mapOf("title" to headline.take(TITLE_MAX), "description" to description.take(DESCRIPTION_MAX), "color" to AlertText.color(severity)),
                ),
            ),
        )
        val status = try {
            val client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build()
            val request = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build()
            client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            throw AlertDeliveryException("webhook failed: ${e.javaClass.simpleName}")
        }
        if (status !in 200..299) throw AlertDeliveryException("webhook failed: HTTP $status")
    }
}

/**
 * 큐 · DB 없이 웹훅으로 **직접** 한 번 — 앱이 못 뜬 기동 실패와 기록이 막힌 5xx 몰림이 쓴다. 던지지 않는다. 2xx 면 true.
 */
internal object DirectWebhook {
    private val log = LoggerFactory.getLogger(DirectWebhook::class.java)
    private val TIMEOUT: Duration = Duration.ofSeconds(3)

    fun post(uri: URI, headline: String, description: String, severity: AlertSeverity, label: String): Boolean = try {
        WebhookHttp.post(uri, headline, description, severity, TIMEOUT)
        log.error("{} was sent straight to the webhook", label)
        true
    } catch (e: Exception) {
        log.error("{} could not reach the webhook: {}", label, e.message ?: e.javaClass.simpleName)
        false
    }
}
