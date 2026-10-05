package dev.sumin.skeleton.alert

import java.net.URI
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 주인 경보 설정 (`skeleton.alert.*`). **웹훅 주소가 없으면 꺼진다** — `OwnerAlerts` 는 있되 로그만 남기고,
 * 5xx 몰림 필터 · 죽은 작업 고리 · 기동 실패 시도는 등록되지 않는다.
 *
 * - [webhookUrl] Discord 호환 웹훅 주소. **주소가 곧 비밀**(경로에 토큰)이라 환경변수로만 받고 어디에도 찍지 않는다
 * - [mailTo] 받는 주소(쉼표로 여럿) — `notification-mail` 의 `MailSender` 가 있을 때만 두 번째 채널이 된다
 */
@ConfigurationProperties("skeleton.alert")
data class AlertProperties(
    val enabled: Boolean = true,
    val webhookUrl: String = "",
    val mailTo: String = "",
    /** 글머리 `[환경]` 에 쓸 이름. 비우면 첫 활성 프로필 */
    val environment: String = "",
    val webhookTimeout: Duration = Duration.ofSeconds(5),
    /** 종류별 최소 간격 덮어쓰기 — 키는 [AlertKind.name] (`job-dead` · `JOB_DEAD` 모두). 기본은 [AlertKind.minInterval] */
    val intervals: Map<String, Duration> = emptyMap(),
    val surge: Surge = Surge(),
    val startupFailure: StartupFailure = StartupFailure(),
    val delivery: Delivery = Delivery(),
) {
    /** 5xx 가 [window] 안에 [threshold] 건 이상이면 경보 — 메모리 창(서버를 다시 켜면 비어 있다) */
    data class Surge(val threshold: Int = 20, val window: Duration = Duration.ofMinutes(1))

    /** 컨텍스트가 못 떴을 때 웹훅으로 직접 알린다. [skipProfiles] 중 하나가 활성이면 알리지 않는다(개발 중 막힘은 경보가 아니다) */
    data class StartupFailure(val enabled: Boolean = true, val skipProfiles: List<String> = listOf("local"))

    /** 채널 하나가 실패했을 때 — 시도 횟수와 시도 사이 대기(곱절로 늘어난다). 채널끼리는 서로 막지 않는다 */
    data class Delivery(val attempts: Int = 3, val backoff: Duration = Duration.ofSeconds(2))

    /** 웹훅 주소 — http(s) + 호스트 + 사용자 정보 없음일 때만. 틀리면 null(쓰지 않는다 — 주소는 로그에 싣지 않는다) */
    val webhookUri: URI?
        get() {
            val raw = webhookUrl.trim().ifEmpty { return null }
            val uri = runCatching { URI(raw) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            if (uri.host.isNullOrBlank() || uri.userInfo != null) return null
            return uri
        }

    /** 주소가 들어 있는데 못 쓰는 모양인가 — 기동 경고용 */
    val webhookInvalid: Boolean get() = webhookUrl.isNotBlank() && webhookUri == null

    val mailRecipients: List<String>
        get() = mailTo.split(',', ';').map { it.trim() }.filter { it.contains('@') }

    fun interval(kind: AlertKind): Duration {
        val wanted = normalize(kind.name)
        return intervals.entries.firstOrNull { normalize(it.key) == wanted }?.value ?: kind.minInterval
    }

    private fun normalize(s: String) = s.filter { it.isLetterOrDigit() }.lowercase()

    // data class 기본 toString() 은 웹훅 주소(= 비밀)를 그대로 찍는다 — 가린다
    override fun toString(): String =
        "AlertProperties(enabled=$enabled, webhookUrl=${if (webhookUrl.isBlank()) "" else "***"}, mailTo=${if (mailTo.isBlank()) "" else "***"}, " +
            "environment=$environment, webhookTimeout=$webhookTimeout, intervals=$intervals, surge=$surge, startupFailure=$startupFailure, delivery=$delivery)"
}
