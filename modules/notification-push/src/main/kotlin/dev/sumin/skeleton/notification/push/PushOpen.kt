package dev.sumin.skeleton.notification.push

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.notification.NotificationDeliveryHooks
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 채널 이름 - `NotificationEvent.channels` · 전달 고리 · 채널 선언이 쓰는 값 */
object PushChannel {
    const val KEY = "push"
}

/** 「이 사람이 이 알림을 눌러 열었다」 기록 - 구현은 `notification-push-jdbc`. (이벤트, 받는 사람) 쌍당 한 줄 */
interface PushOpenStore {
    /** 처음 기록했으면 true, 이미 있으면 false - 동시에 와도 하나만 true */
    fun recordFirst(eventId: String, recipientId: String, at: java.time.Instant): Boolean
}

/**
 * 푸시 클릭 보고용 서명 토큰 `<기기 id>.<mac>`. mac = `base64url(HMAC-SHA256(비밀, 이벤트 id \0 기기 id \0 받는 사람 id))` 앞 16바이트.
 * 받는 사람 id 는 토큰에 없다 - 검증하는 쪽이 기기 줄의 **지금 주인**으로 다시 계산해 맞춘다. 그래서 위조 · 다른 이벤트 id 로 바꿔 쓰기 · 기기 주인이 바뀐 뒤의 옛 토큰이 모두 거절된다.
 */
class PushOpenTokens(secret: String) {
    private val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC)

    fun issue(eventId: String, device: PushDevice): String = "${device.id}.${mac(eventId, device.id, device.accountId)}"

    /** 토큰의 기기 id (모양이 틀리면 null) */
    fun deviceIdOf(token: String): String? = token.lastIndexOf('.').takeIf { it > 0 }?.let { token.substring(0, it) }

    fun matches(eventId: String, token: String, device: PushDevice): Boolean {
        val given = token.substringAfterLast('.', "")
        return MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), mac(eventId, device.id, device.accountId).toByteArray(Charsets.UTF_8))
    }

    private fun mac(eventId: String, deviceId: String, recipientId: String): String {
        val hmac = Mac.getInstance(HMAC).apply { init(key) }
        val bytes = hmac.doFinal("$eventId\u0000$deviceId\u0000$recipientId".toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.copyOf(MAC_BYTES))
    }

    private companion object {
        const val HMAC = "HmacSHA256"
        const val MAC_BYTES = 16
    }
}

/**
 * 클릭 보고 한 건을 받는다: 토큰이 이 이벤트 · 이 기기의 지금 주인과 맞는지 보고, **처음**이면 기록하고 전달 고리 `onOpened` 를 부른다.
 * 기기가 이미 없으면 조용히 버린다(보고가 늦었다). 서명이 틀리면 [PushException] (`PUSH.INVALID_OPEN_TOKEN`).
 */
class PushOpenReporter(
    private val tokens: PushOpenTokens,
    private val devices: PushDeviceStore,
    private val store: PushOpenStore,
    private val hooks: NotificationDeliveryHooks,
    private val time: TimeProvider,
) {
    fun report(eventId: String, token: String) {
        val deviceId = tokens.deviceIdOf(token) ?: throw invalid()
        val device = devices.find(deviceId) ?: return
        if (!tokens.matches(eventId, token, device)) throw invalid()
        if (store.recordFirst(eventId, device.accountId, time.now())) hooks.onOpened(eventId, PushChannel.KEY)
    }

    private fun invalid() = PushException(PushErrorCode.INVALID_OPEN_TOKEN, "Invalid push open token")
}
