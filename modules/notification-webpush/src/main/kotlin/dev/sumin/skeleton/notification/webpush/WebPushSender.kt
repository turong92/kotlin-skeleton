package dev.sumin.skeleton.notification.webpush

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.notification.push.PushDevice
import dev.sumin.skeleton.notification.push.PushKinds
import dev.sumin.skeleton.notification.push.PushMessage
import dev.sumin.skeleton.notification.push.PushRegistration
import dev.sumin.skeleton.notification.push.PushResult
import dev.sumin.skeleton.notification.push.PushSender
import dev.sumin.skeleton.notification.push.PushUrgency
import java.io.IOException
import java.net.URI
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.Signature
import java.time.Duration
import tools.jackson.databind.json.JsonMapper

/**
 * 웹 푸시(RFC 8030 전달 · 8291 암호화 · 8292 VAPID) 센더. 판정 표: 201(2xx) 전달 · 404 · 410 지움 · 429 · 5xx · 연결 실패 재시도 ·
 * **400 · 401 · 403 · 413 과 그 밖은 실패로 남긴다(지우지 않는다 - 우리 VAPID 설정 실수가 구독 대량 삭제로 번지지 않게)**.
 * 실패 이유 · [toString] 에는 개인 키 · 끝점 · 구독 열쇠를 싣지 않는다.
 */
class WebPushSender(
    private val properties: WebPushProperties,
    private val vapid: VapidKeys,
    private val policy: WebPushEndpointPolicy,
    private val http: WebPushHttp = JdkWebPushHttp(),
    private val time: TimeProvider = TimeProvider.systemUtc(),
) : PushSender {
    override val kind: String = PushKinds.WEBPUSH
    override val publicKey: String = vapid.publicKey

    override fun validate(registration: PushRegistration): String? {
        policy.check(registration.address)?.let { return it }
        if (!isPublicKey(registration.p256dh)) return "keys.p256dh must be a base64url P-256 public key (65 bytes)"
        if (!isAuthSecret(registration.authSecret)) return "keys.auth must be base64url (16 bytes)"
        return null
    }

    override fun send(device: PushDevice, message: PushMessage): PushResult {
        val uaPublic = decode(device.p256dh)?.takeIf { runCatching { WebPushKeys.publicKey(it) }.isSuccess }
        val authSecret = decode(device.authSecret)?.takeIf { it.size == AUTH_BYTES }
        if (uaPublic == null || authSecret == null) return PushResult.Failed("the stored subscription keys are malformed")

        try {
            policy.checkForSend(device.address)?.let { return PushResult.Failed(it) }
        } catch (_: UnknownHostException) {
            return PushResult.Retry()   // 이름 풀이 일시 장애일 수 있다
        }

        val plaintext = payload(message)
        if (plaintext.size > WebPushCrypto.MAX_PLAINTEXT_BYTES) return PushResult.Failed("the payload does not fit one push record")
        val endpoint = URI(device.address)
        val now = time.now()
        val headers = buildMap {
            put("Authorization", "vapid t=${vapidToken(endpoint, now.epochSecond)}, k=${vapid.publicKey}")
            put("Content-Encoding", "aes128gcm")
            put("Content-Type", "application/octet-stream")
            put("TTL", (message.expiresAt?.let { Duration.between(now, it).seconds } ?: DEFAULT_TTL_SECONDS).coerceAtLeast(0).toString())
            put("Urgency", if (message.urgency == PushUrgency.HIGH) "high" else "normal")
            message.tag?.let { put("Topic", topic(it)) }
        }
        val body = WebPushCrypto.encrypt(plaintext, uaPublic, authSecret)

        val response = try {
            http.post(endpoint, headers, body, properties.timeout)
        } catch (_: IOException) {
            return PushResult.Retry()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return PushResult.Retry()
        }
        return when (response.status) {
            in 200..299 -> PushResult.Delivered
            404, 410 -> PushResult.Gone
            429 -> PushResult.Retry(response.retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let(Duration::ofSeconds))
            in 500..599 -> PushResult.Retry()
            else -> PushResult.Failed("push service answered HTTP ${response.status}")
        }
    }

    override fun toString(): String = "WebPushSender"

    private fun payload(message: PushMessage): ByteArray {
        val content = linkedMapOf<String, Any>("v" to 1, "id" to message.eventId, "type" to message.type, "title" to message.title)
        message.body?.let { content["body"] = it }
        message.link?.let { content["link"] = it }
        message.tag?.let { content["tag"] = it }
        message.openToken?.let { content["open"] = it }
        return JSON.writeValueAsBytes(content)
    }

    /** RFC 8292 - `aud` 는 끝점의 출처, 하루를 넘지 않는 `exp` */
    private fun vapidToken(endpoint: URI, nowEpochSecond: Long): String {
        val audience = "${endpoint.scheme}://${endpoint.host}" + if (endpoint.port != -1 && endpoint.port != 443) ":${endpoint.port}" else ""
        val header = WebPushKeys.encode(JSON.writeValueAsBytes(linkedMapOf("typ" to "JWT", "alg" to "ES256")))
        val claims = WebPushKeys.encode(
            JSON.writeValueAsBytes(linkedMapOf("aud" to audience, "exp" to nowEpochSecond + TOKEN_LIFETIME_SECONDS, "sub" to properties.subject.trim())),
        )
        val signing = "$header.$claims"
        val signature = Signature.getInstance(WebPushConfigCheck.ES256).run {
            initSign(vapid.privateKey)
            update(signing.toByteArray(StandardCharsets.US_ASCII))
            sign()
        }
        return "$signing.${WebPushKeys.encode(signature)}"
    }

    /** `Topic` 은 최대 32자의 URL 안전 base64 - 묶기 열쇠의 해시 앞 32자 */
    private fun topic(tag: String): String =
        WebPushKeys.encode(MessageDigest.getInstance("SHA-256").digest(tag.toByteArray(StandardCharsets.UTF_8))).take(32)

    private fun decode(value: String?): ByteArray? = value?.let { runCatching { WebPushKeys.decode(it) }.getOrNull() }

    private fun isPublicKey(value: String?): Boolean =
        decode(value)?.let { runCatching { WebPushKeys.publicKey(it) }.isSuccess } == true

    private fun isAuthSecret(value: String?): Boolean = decode(value)?.size == AUTH_BYTES

    private companion object {
        val JSON: JsonMapper = JsonMapper.builder().build()
        const val AUTH_BYTES = 16
        const val DEFAULT_TTL_SECONDS = 24L * 3600
        const val TOKEN_LIFETIME_SECONDS = 12L * 3600
    }
}
