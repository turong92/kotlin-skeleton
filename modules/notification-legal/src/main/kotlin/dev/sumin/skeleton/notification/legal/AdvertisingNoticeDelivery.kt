package dev.sumin.skeleton.notification.legal

import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.notification.advertising.AdvertisingNotificationTypes
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/** 법정 통지를 실제로 보낼 수 있는 매체 하나(메일) - 메일 모듈 타입 없이 「지금 보낼 수 있나」만 묻는다 */
interface NoticeMedium {
    fun ready(): Boolean
}

/**
 * 법정 통지(`advertising.consent.*`)가 닿는 매체가 없으면 그 이유(경고 문구), 있으면 null.
 * 푸시는 채널이 있고 **두 종류가 `push.types` 에 있어야** 닿는다. 메일은 메일러 빈이 있고 `MailSender` · `ContactAddresses` 가 둘 다 있어야 닿는다.
 */
fun noticeMediumProblem(pushDeclared: Boolean, pushTypes: Set<String>, mailReady: Boolean): String? {
    val pushReady = pushDeclared && pushTypes.containsAll(listOf(AdvertisingNotificationTypes.CONSENT_CHANGED, AdvertisingNotificationTypes.CONSENT_RECONFIRM))
    if (pushReady || mailReady) return null
    return "advertising consent notices (advertising.consent.*) have no delivery medium: push does not carry both types (push enabled and both listed in skeleton.notification.push.types) " +
        "and the mail notice is not ready (needs notification-mail, a MailSender and a ContactAddresses bean from account). They only reach the inbox, which is not a transmission - deliver them by your own channel"
}

/**
 * 재확인 스캔을 도는 한 곳 - 프로젝트 모드면 서비스 중인 프로젝트마다 그 문맥 안에서 돈다(프로젝트 하나가 실패해도 나머지는 계속).
 * 타이머는 [run](늘 돈다), 요청 구동 모드의 깨우기는 [runIfDue](프로젝트마다 마지막 스캔 뒤 [interval] 이 안 지났으면 건너뛴다 - 깨우기가 잦아도 `reconfirm-scan-interval` 을 지킨다).
 * 마지막 스캔 시각은 인스턴스 메모리에 둔다: 인스턴스가 여럿이거나 새로 떠도 최악은 스캔이 한 번 더 도는 것이고, 안내 표시와 이벤트 id 가 멱등이라 같은 안내가 두 번 나가지 않는다.
 */
class AdvertisingReconfirmRunner(
    private val scan: () -> Unit,
    private val projects: () -> ProjectRegistry?,
    private val interval: Duration,
    private val time: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(AdvertisingReconfirmScanner::class.java)
    private val lastScan = ConcurrentHashMap<String, Instant>()

    fun run() = all(onlyDue = false)

    fun runIfDue() = all(onlyDue = true)

    private fun all(onlyDue: Boolean) {
        val registry = projects()
        if (registry == null) {
            one("", onlyDue)
        } else {
            registry.forEachProject { project -> one(project.id, onlyDue) }
        }
    }

    private fun one(key: String, onlyDue: Boolean) {
        val now = time.now()
        if (onlyDue && lastScan[key]?.let { now.isBefore(it.plus(interval)) } == true) return
        lastScan[key] = now
        try {
            scan()
        } catch (e: Exception) {
            log.warn("advertising reconfirm scan failed project='{}': {}", key.ifEmpty { "-" }, e.toString())
        }
    }
}
