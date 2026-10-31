package dev.sumin.skeleton.notification.fcm

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.notification.push.PushDevice
import dev.sumin.skeleton.notification.push.PushKinds
import dev.sumin.skeleton.notification.push.PushMessage
import dev.sumin.skeleton.notification.push.PushPlatforms
import dev.sumin.skeleton.notification.push.PushRegistration
import dev.sumin.skeleton.notification.push.PushResult
import dev.sumin.skeleton.notification.push.PushSender
import dev.sumin.skeleton.notification.push.PushUrgency
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import tools.jackson.databind.json.JsonMapper

/**
 * FCM HTTP v1 센더. 판정 표: 200 전달 / `UNREGISTERED` · 토큰(`message.token`)을 가리킨 `INVALID_ARGUMENT` 지움 /
 * `QUOTA_EXCEEDED` · `UNAVAILABLE` · `INTERNAL` · 429 · 5xx · 연결 실패 재시도 / **그 밖 - 토큰을 가리키지 않는 400 · `SENDER_ID_MISMATCH`(다른 프로젝트의 서비스 계정을 넣으면 모든 토큰이 받는다) · `THIRD_PARTY_AUTH_ERROR` · 401 · 403 · 404 - 은 실패로 남긴다
 * (우리 자격 · 프로젝트 · 요청 실수가 기기 줄 대량 삭제로 번지지 않게)**. 실패 이유 · [toString] 에는 서비스 계정 · 접근 토큰 · FCM 토큰 · 구글이 보낸 설명 문장을 싣지 않는다.
 * 요청 주소는 `fcm.googleapis.com` 으로 고정이고 프로젝트 id 는 검사를 통과한 서비스 계정에서만 온다.
 */
class FcmSender(
    private val properties: FcmProperties,
    private val credentials: FcmCredentials,
    private val http: FcmHttp = JdkFcmHttp(),
    private val time: TimeProvider = TimeProvider.systemUtc(),
) : PushSender {
    override val kind: String = PushKinds.FCM

    private val tokens = FcmAccessTokens(credentials, http, time, properties.timeout)
    private val sendUrl: URI = URI.create("https://fcm.googleapis.com/v1/projects/${credentials.projectId}/messages:send")

    override fun validate(registration: PushRegistration): String? {
        if (registration.platform != PushPlatforms.ANDROID && registration.platform != PushPlatforms.IOS) return "platform must be android or ios for an FCM token"
        val token = registration.address
        if (token.isEmpty() || token.length > MAX_TOKEN_CHARS) return "FCM token must be 1 to $MAX_TOKEN_CHARS characters"
        if (token.any { it.isWhitespace() || it.isISOControl() }) return "FCM token must not contain whitespace"
        return null
    }

    override fun send(device: PushDevice, message: PushMessage): PushResult {
        val accessToken = try {
            tokens.get()
        } catch (e: FcmTokenException) {
            return if (e.retry) PushResult.Retry() else PushResult.Failed(e.message ?: "could not get an access token")
        }
        val body = JSON.writeValueAsBytes(linkedMapOf("message" to payload(device.address, message)))
        val headers = mapOf("Authorization" to "Bearer $accessToken", "Content-Type" to "application/json; charset=UTF-8")
        val response = try {
            http.post(sendUrl, headers, body, properties.timeout)
        } catch (_: IOException) {
            return PushResult.Retry()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return PushResult.Retry()
        }
        if (response.status in 200..299) return PushResult.Delivered
        if (response.status == 401) tokens.invalidate()
        return judge(response)
    }

    override fun toString(): String = "FcmSender"

    private fun judge(response: FcmHttpResponse): PushResult {
        val error = runCatching { JSON.readTree(response.body.orEmpty()) }.getOrNull()?.path("error")
        val status = error?.path("status")?.asString().orEmpty()
        val details = error?.path("details")?.toList().orEmpty()
        val errorCode = details.firstOrNull { it.path("errorCode").isString }?.path("errorCode")?.asString() ?: status
        val pointsAtToken = details.any { d -> d.path("fieldViolations").any { it.path("field").asString() == TOKEN_FIELD } }
        return when {
            errorCode == "UNREGISTERED" -> PushResult.Gone
            errorCode == "INVALID_ARGUMENT" && pointsAtToken -> PushResult.Gone
            errorCode in RETRYABLE || response.status == 429 || response.status in 500..599 ->
                PushResult.Retry(response.retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let(Duration::ofSeconds))
            else -> PushResult.Failed("FCM answered HTTP ${response.status}" + errorCode.takeIf { CODE_SHAPE.matches(it) }?.let { " ($it)" }.orEmpty())
        }
    }

    private fun payload(token: String, message: PushMessage): Map<String, Any> {
        val now = time.now()
        val ttl = (message.expiresAt?.let { Duration.between(now, it).seconds } ?: DEFAULT_TTL_SECONDS).coerceAtLeast(0)
        val high = message.urgency == PushUrgency.HIGH
        val data = linkedMapOf("eventId" to message.eventId, "type" to message.type)
        message.link?.let { data["link"] = it }
        val notification = linkedMapOf("title" to message.title)
        message.body?.let { notification["body"] = it }

        val android = linkedMapOf<String, Any>("ttl" to "${ttl}s", "priority" to if (high) "HIGH" else "NORMAL")
        val apnsHeaders = linkedMapOf("apns-expiration" to (now.epochSecond + ttl).toString(), "apns-priority" to if (high) "10" else "5")
        message.tag?.let {
            android["collapse_key"] = it
            android["notification"] = mapOf("tag" to it)
            apnsHeaders["apns-collapse-id"] = collapseId(it)   // APNs 는 64바이트까지
        }
        return linkedMapOf("token" to token, "notification" to notification, "data" to data, "android" to android, "apns" to mapOf("headers" to apnsHeaders))
    }

    private fun collapseId(tag: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(tag.toByteArray(StandardCharsets.UTF_8)))

    private companion object {
        val JSON: JsonMapper = JsonMapper.builder().build()
        const val MAX_TOKEN_CHARS = 4096
        const val DEFAULT_TTL_SECONDS = 24L * 3600
        const val TOKEN_FIELD = "message.token"
        val RETRYABLE = setOf("QUOTA_EXCEEDED", "UNAVAILABLE", "INTERNAL")
        val CODE_SHAPE = Regex("^[A-Z_]{1,40}$")
    }
}
