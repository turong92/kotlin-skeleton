package dev.sumin.skeleton.notification.push.legal

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.legal.ConsentAction
import dev.sumin.skeleton.legal.ConsentChangeListener
import dev.sumin.skeleton.legal.ConsentEvent
import dev.sumin.skeleton.legal.ConsentStore
import dev.sumin.skeleton.legal.RECONFIRM_SOURCE
import dev.sumin.skeleton.legal.Subject
import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationPublisher
import dev.sumin.skeleton.notification.advertising.AdvertisingGate
import dev.sumin.skeleton.notification.advertising.AdvertisingNotificationTypes
import dev.sumin.skeleton.notification.push.PushAdvertisingNoticeStore
import dev.sumin.skeleton.notification.advertising.AdvertisingProperties
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import org.slf4j.LoggerFactory

private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private const val TOPIC = "advertising"

private fun scopeLabel(night: Boolean, advertising: AdvertisingProperties) =
    if (night) "야간(${advertising.nightStart}~${advertising.nightEnd}) 광고성 정보 수신" else "광고성 정보 수신"

private fun zoneOf(gate: AdvertisingGate, accountId: String): ZoneId = runCatching { gate.zoneOf(accountId) }.getOrDefault(ZoneOffset.UTC)

/**
 * 동의 · 철회 · 재확인이 저장되면 곧바로 정보성 알림 `advertising.consent.changed` 를 보낸다(처리 결과 통지) - 어느 경로(`legal` HTTP · 가입 · 앱 코드)로 와도 같다.
 * 정보성이다(광고 게이트 밖) - 광고 동의가 없는 사람에게도 간다. 전송자 명칭 · 처리 일시 · 무엇을 · 철회 방법을 싣는다. 문구는 한국어 기본이고 payload 에 구조화된 값이 같이 실린다(문구를 바꾸려면 이 빈을 교체한다). 참조가 붙은 동의 · 광고와 상관없는 문서 · 계정이 아닌 주체는 무시한다.
 */
class AdvertisingConsentNotifier(
    private val publisher: NotificationPublisher,
    private val advertising: AdvertisingProperties,
    private val gate: AdvertisingGate,
) : ConsentChangeListener {
    override fun onChange(event: ConsentEvent) {
        if (event.subject.type != Subject.ACCOUNT || event.referenceId != null) return
        val night = when (event.type) {
            advertising.consentType -> false
            advertising.nightConsentType -> true
            else -> return
        }
        val account = event.subject.id
        val action = when {
            event.action == ConsentAction.WITHDRAWN -> "철회"
            event.source == RECONFIRM_SOURCE -> "동의 확인(재확인)"
            else -> "동의"
        }
        val at = STAMP.format(event.at.atZone(zoneOf(gate, account)))
        publisher.publish(
            NotificationEvent(
                topic = TOPIC, type = AdvertisingNotificationTypes.CONSENT_CHANGED, recipientIds = setOf(account),
                title = "광고성 정보 수신 동의 처리 결과",
                message = "[${advertising.senderName}] ${scopeLabel(night, advertising)} $action 이(가) $at 에 처리되었습니다. 철회는 설정 > 알림에서 언제든 할 수 있습니다.",
                payload = mapOf(
                    "scope" to if (night) "night" else "advertising", "type" to event.type, "action" to event.action.name, "source" to event.source,
                    "senderName" to advertising.senderName, "at" to event.at.toString(),
                ),
                createdAt = event.at, link = advertising.reconfirmLink,
            ),
        )
    }
}

/**
 * 동의가 만료되기 [AdvertisingProperties.reconfirmNoticeBefore] 전에 재확인 안내 `advertising.consent.reconfirm` 을 **동의 주기당 한 번** 보낸다(정보성 - 동의가 이미 만료됐거나 없는 사람에게도 간다).
 * 각 동의 종류마다 `latestAgreementsBefore` 로 「가장 최근 사건이 동의이고 `만료일 − 안내 기간` 보다 오래된」 사람을 쪽 단위로 읽고, 표시가 없으면 발행 → 표시 순서로 처리한다
 * (사이에서 죽으면 다음 스캔이 같은 id 로 한 번 더 보내고 받은편지함은 한 줄로 합친다). 한 사람이 실패해도 나머지는 계속한다.
 * 동의 주기를 연 사건의 시각이 열쇠라서 재확인하면 새 주기 = 새 안내다.
 */
class AdvertisingReconfirmScanner(
    private val store: ConsentStore,
    private val notices: PushAdvertisingNoticeStore,
    private val publisher: NotificationPublisher,
    private val gate: AdvertisingGate,
    private val advertising: AdvertisingProperties,
    private val time: TimeProvider,
    private val pageSize: Int = 500,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 보낸 안내 수 */
    fun run(): Int {
        val now = time.now()
        val lead = advertising.reconfirmAfter.minus(advertising.reconfirmNoticeBefore).let { if (it.isNegative) java.time.Duration.ZERO else it }
        val threshold = now.minus(lead)
        var sent = 0
        for ((type, night) in listOf(advertising.consentType to false, advertising.nightConsentType to true)) {
            var after: String? = null
            while (true) {
                val page = store.latestAgreementsBefore(type, threshold, after, pageSize)
                page.forEach { event -> if (notice(event, night, now)) sent++ }
                if (page.size < pageSize) break
                after = page.last().subject.id
            }
        }
        return sent
    }

    private fun notice(event: ConsentEvent, night: Boolean, now: Instant): Boolean {
        val account = event.subject.id
        return try {
            if (notices.isNoticed(account, event.type, event.at)) return false
            val expires = event.at.plus(advertising.reconfirmAfter)
            val zone = zoneOf(gate, account)
            val verb = if (expires.isAfter(now)) "만료됩니다" else "만료되었습니다"
            publisher.publish(
                NotificationEvent(
                    topic = TOPIC, type = AdvertisingNotificationTypes.CONSENT_RECONFIRM, id = reminderId(account, event.type, event.at), recipientIds = setOf(account),
                    title = "광고성 정보 수신 동의 확인 안내",
                    message = "[${advertising.senderName}] ${scopeLabel(night, advertising)} 동의(${DAY.format(event.at.atZone(zone))} 동의)가 ${DAY.format(expires.atZone(zone))} 에 $verb. " +
                        "계속 받으시려면 설정에서 확인해 주세요. 철회는 설정 > 알림에서 언제든 할 수 있습니다.",
                    payload = mapOf(
                        "scope" to if (night) "night" else "advertising", "type" to event.type, "senderName" to advertising.senderName,
                        "agreedAt" to event.at.toString(), "expiresAt" to expires.toString(),
                    ),
                    createdAt = now, link = advertising.reconfirmLink,
                ),
            )
            notices.markNoticed(account, event.type, event.at, now)
            true
        } catch (e: Exception) {
            log.warn("advertising reconfirm notice failed for account={} type={} exception={}", account, event.type, e.javaClass.simpleName)
            false
        }
    }

    /** 같은 동의 주기의 안내는 늘 같은 id - 표시를 잃고 다시 보내도 받은편지함이 한 줄로 합친다 */
    private fun reminderId(account: String, type: String, agreedAt: Instant): String =
        "evt_" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("reconfirm\n$account\n$type\n$agreedAt".toByteArray(Charsets.UTF_8))).take(32)
}
