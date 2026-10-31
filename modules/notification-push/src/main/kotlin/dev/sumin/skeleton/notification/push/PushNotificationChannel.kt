package dev.sumin.skeleton.notification.push

import dev.sumin.skeleton.notification.NotificationEvent
import dev.sumin.skeleton.notification.NotificationSubscriber
import dev.sumin.skeleton.notification.NotificationSubscription
import dev.sumin.skeleton.notification.NotificationSubscriptionRegistry
import org.slf4j.LoggerFactory

/**
 * 알림이 받는 사람의 기기로도 간다. 슬랙 전달기와 같은 꼴로 브로커에 **서버 전체 리스너**로 붙는다 - 끄기(브로커가 받는 사람에서 뺌) · 커밋 뒤 전달 · 프로젝트 문맥이 따라온다.
 *
 * 건너뜀: 받는 사람이 없는 전체 공지 · `push.types` 밖의 종류(광고 종류 포함 - 동의 게이트는 이 모듈 밖이다) · 그 종류의 푸시를 끈 사람 · factory 가 null.
 * 받는 사람의 기기마다 작업 하나를 넣는다. **모든 예외를 잡고 로그만 남긴다** - 브로커는 던진 구독자를 닫아 채널이 영영 떨어지기 때문이다.
 * 커밋과 넣기 사이에 프로세스가 죽으면 그 푸시는 잃는다(푸시는 최선 노력, 받은편지함이 원본).
 */
class PushNotificationChannel(
    registry: NotificationSubscriptionRegistry,
    private val properties: PushProperties,
    private val factory: PushMessageFactory,
    private val devices: PushDeviceStore,
    private val mutes: PushTypeMuteStore,
    private val tasks: PushTasks,
    private val senders: () -> List<PushSender>,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val types = properties.pushTypes.toSet()

    private val subscription: NotificationSubscription =
        registry.subscribe(subscriber = NotificationSubscriber { event -> onNotification(event) })

    fun onNotification(event: NotificationEvent) {
        try {
            push(event)
        } catch (e: Exception) {
            log.warn("push channel failed for event={} type={} exception={}", event.id, event.type, e.javaClass.simpleName)
        }
    }

    private fun push(event: NotificationEvent) {
        if (event.recipientIds.isEmpty() || event.type !in types) return
        val targets = event.recipientIds - mutes.mutedAmong(event.type, event.recipientIds)
        if (targets.isEmpty()) return
        val message = factory.create(event) ?: return
        val prepared = message.copy(
            body = message.body?.let { PushText.truncateUtf8(it, MAX_BODY_BYTES) },
            expiresAt = message.createdAt.plus(properties.ttl),
        )
        val bySender = senders().associateBy { it.kind }
        devices.listByAccounts(targets).forEach { device ->
            val sender = bySender[device.kind] ?: return@forEach
            if (!matchesCurrentKey(device, sender)) return@forEach
            tasks.enqueue(PushJob(device.id, device.accountId, prepared))
        }
    }

    /** 구독할 때의 서버 키가 지금 키와 다르면(키를 바꿨다) 보내 봐야 거절된다 - 화면이 다음 방문에 다시 구독한다 */
    private fun matchesCurrentKey(device: PushDevice, sender: PushSender): Boolean {
        val current = sender.publicKey?.let(PushHashes::keyId)
        return current == null || device.keyId == null || device.keyId == current
    }

    override fun close() {
        subscription.close()
    }

    private companion object {
        /** 웹 푸시 레코드 4096바이트 · FCM 4KB 한도 안에 머물도록 */
        const val MAX_BODY_BYTES = 3000
    }
}

internal object PushText {
    /** UTF-8 바이트 수가 [maxBytes] 를 넘지 않게, 글자(코드포인트) 중간에서 자르지 않고 자른다 */
    fun truncateUtf8(text: String, maxBytes: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val cp = text.codePointAt(end)
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > maxBytes) break
            bytes += size
            end += Character.charCount(cp)
        }
        return text.substring(0, end)
    }
}
