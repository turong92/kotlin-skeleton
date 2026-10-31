package dev.sumin.skeleton.notification.fcm

import dev.sumin.skeleton.notification.push.PushDevice
import dev.sumin.skeleton.notification.push.PushMessage
import dev.sumin.skeleton.notification.push.PushRegistration
import dev.sumin.skeleton.notification.push.PushResult
import dev.sumin.skeleton.notification.push.PushUrgency
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat

class FcmSenderTest {
    private val now = Instant.parse("2026-10-31T09:00:00Z")
    private val account = TestServiceAccount()
    private val google = FakeGoogle(account)
    private var clock = now
    private val properties = FcmProperties(enabled = true, credentials = account.base64())
    private val token = "fcm-token-SECRET_0123456789:abcdef"

    private fun sender() = FcmSender(properties, FcmConfigCheck.credentialsOf(properties)!!, google) { clock }

    private fun device(token: String = this.token) = PushDevice(
        id = "dev1", accountId = "acc1", installationId = "inst-0123456789abcdef", sessionId = null, kind = "FCM", platform = "android",
        address = token, p256dh = null, authSecret = null, keyId = null, label = null, createdAt = now, lastSeenAt = now, lastSuccessAt = null,
    )

    private fun message(urgency: PushUrgency = PushUrgency.NORMAL, tag: String? = "comment:42") = PushMessage(
        eventId = "evt1", type = "comment.created", title = "새 댓글", body = "안녕", link = "/notes/1", tag = tag,
        urgency = urgency, createdAt = now, expiresAt = now.plus(Duration.ofHours(1)),
    )

    // ---- 보내기: 요청 모양

    @Test
    fun `delivers one HTTP v1 request to the fixed address with the project id of the credentials and a bearer token`() {
        val result = sender().send(device(), message(PushUrgency.HIGH))

        assertThat(result).isEqualTo(PushResult.Delivered)
        val sent = google.sends.single()
        assertThat(sent.url.toString()).isEqualTo("https://fcm.googleapis.com/v1/projects/demo-project-1/messages:send")
        assertThat(sent.headers["authorization"]).isEqualTo("Bearer ya29.token-1")
        val m = jsonOf(sent.body)["message"]
        assertThat(m["token"].asString()).isEqualTo(token)
        assertThat(m["notification"]["title"].asString()).isEqualTo("새 댓글")
        assertThat(m["notification"]["body"].asString()).isEqualTo("안녕")
        assertThat(m["data"]["eventId"].asString()).isEqualTo("evt1")
        assertThat(m["data"]["type"].asString()).isEqualTo("comment.created")
        assertThat(m["data"]["link"].asString()).isEqualTo("/notes/1")
        assertThat(m["android"]["collapse_key"].asString()).isEqualTo("comment:42")
        assertThat(m["android"]["ttl"].asString()).isEqualTo("3600s")
        assertThat(m["android"]["priority"].asString()).isEqualTo("HIGH")
        assertThat(m["android"]["notification"]["tag"].asString()).isEqualTo("comment:42")
        val apns = m["apns"]["headers"]
        assertThat(apns["apns-collapse-id"].asString().toByteArray().size).isLessThanOrEqualTo(64)
        assertThat(apns["apns-collapse-id"].asString()).doesNotContain("comment")
        assertThat(apns["apns-expiration"].asString()).isEqualTo((now.epochSecond + 3600).toString())
        assertThat(apns["apns-priority"].asString()).isEqualTo("10")
    }

    @Test
    fun `without a body, link or tag the optional parts are left out`() {
        val bare = message(tag = null).copy(body = null, link = null)
        sender().send(device(), bare)

        val m = jsonOf(google.sends.single().body)["message"]
        assertThat(m["notification"].has("body")).isFalse()
        assertThat(m["data"].has("link")).isFalse()
        assertThat(m["android"].has("collapse_key")).isFalse()
        assertThat(m["apns"]["headers"].has("apns-collapse-id")).isFalse()
        assertThat(m["android"]["priority"].asString()).isEqualTo("NORMAL")
    }

    @Test
    fun `the token request is signed by the service account and goes to the fixed Google address, never the token_uri in the JSON`() {
        sender().send(device(), message())

        val request = google.tokenRequests.single()
        assertThat(request.url.toString()).isEqualTo("https://oauth2.googleapis.com/token")
        val assertion = request.body.substringAfter("assertion=").let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
        val claims = account.verifyAssertion(assertion)
        assertThat(claims["iss"].asString()).isEqualTo(account.clientEmail)
        assertThat(claims["scope"].asString()).isEqualTo("https://www.googleapis.com/auth/firebase.messaging")
        assertThat(claims["aud"].asString()).isEqualTo("https://oauth2.googleapis.com/token")
        assertThat(claims["exp"].asLong() - claims["iat"].asLong()).isLessThanOrEqualTo(3600)
        assertThat(google.requests.map { it.url.host }.toSet()).containsExactlyInAnyOrder("oauth2.googleapis.com", "fcm.googleapis.com")
    }

    @Test
    fun `the access token is reused until shortly before it expires, then asked again`() {
        val sender = sender()
        sender.send(device(), message())
        sender.send(device(), message())
        assertThat(google.tokenRequests).hasSize(1)

        clock = now.plusSeconds(3600 - 60)   // 만료 1분 전 — 여유 안
        sender.send(device(), message())
        assertThat(google.tokenRequests).hasSize(2)
        assertThat(google.sends.last().headers["authorization"]).isEqualTo("Bearer ya29.token-2")
    }

    // ---- 판정 표

    @Test
    fun `UNREGISTERED and an INVALID_ARGUMENT that points at the token delete the device`() {
        listOf(
            404 to fcmError(404, "NOT_FOUND", errorCode = "UNREGISTERED"),
            400 to fcmError(400, "INVALID_ARGUMENT", errorCode = "INVALID_ARGUMENT", violationField = "message.token"),
        ).forEach { (status, body) ->
            google.sendStatus = status
            google.sendBody = body
            assertThat(sender().send(device(), message())).`as`("HTTP $status").isEqualTo(PushResult.Gone)
        }
    }

    @Test
    fun `an INVALID_ARGUMENT that does not point at the token never deletes the device`() {
        google.sendStatus = 400
        google.sendBody = fcmError(400, "INVALID_ARGUMENT", errorCode = "INVALID_ARGUMENT", violationField = "message.android.ttl")
        assertThat(sender().send(device(), message())).isInstanceOf(PushResult.Failed::class.java)

        google.sendBody = fcmError(400, "INVALID_ARGUMENT")   // 이유 없는 400
        assertThat(sender().send(device(), message())).isInstanceOf(PushResult.Failed::class.java)

        google.sendBody = "not json at all"
        assertThat(sender().send(device(), message())).isInstanceOf(PushResult.Failed::class.java)
    }

    @Test
    fun `our own credential problems never delete the device`() {
        listOf(
            400 to fcmError(400, "FAILED_PRECONDITION", errorCode = "THIRD_PARTY_AUTH_ERROR"),
            401 to fcmError(401, "UNAUTHENTICATED"),
            403 to fcmError(403, "PERMISSION_DENIED"),
            403 to fcmError(403, "PERMISSION_DENIED", errorCode = "SENDER_ID_MISMATCH"),   // 다른 프로젝트의 서비스 계정을 넣은 설정 실수 - 모든 토큰이 이 답을 받는다
            404 to fcmError(404, "NOT_FOUND"),   // 프로젝트가 틀려도 404 — UNREGISTERED 가 아니면 줄을 지우지 않는다
        ).forEach { (status, body) ->
            google.sendStatus = status
            google.sendBody = body
            assertThat(sender().send(device(), message())).`as`("HTTP $status").isInstanceOf(PushResult.Failed::class.java)
        }
    }

    @Test
    fun `quota, unavailable, internal errors and network trouble are retried, honouring Retry-After`() {
        listOf(
            429 to fcmError(429, "RESOURCE_EXHAUSTED", errorCode = "QUOTA_EXCEEDED"),
            503 to fcmError(503, "UNAVAILABLE", errorCode = "UNAVAILABLE"),
            500 to fcmError(500, "INTERNAL", errorCode = "INTERNAL"),
        ).forEach { (status, body) ->
            google.sendStatus = status
            google.sendBody = body
            assertThat(sender().send(device(), message())).`as`("HTTP $status").isInstanceOf(PushResult.Retry::class.java)
        }
        google.sendStatus = 429
        google.sendRetryAfter = "30"
        assertThat(sender().send(device(), message())).isEqualTo(PushResult.Retry(Duration.ofSeconds(30)))

        google.failWith = HttpTimeoutException("timed out")
        assertThat(sender().send(device(), message())).isInstanceOf(PushResult.Retry::class.java)
    }

    @Test
    fun `a rejected 401 drops the cached access token so the next push asks for a fresh one`() {
        val sender = sender()
        sender.send(device(), message())
        google.sendStatus = 401
        google.sendBody = fcmError(401, "UNAUTHENTICATED")
        sender.send(device(), message())
        google.sendStatus = 200
        sender.send(device(), message())
        assertThat(google.tokenRequests).hasSize(2)
    }

    // ---- 토큰 발급 실패

    @Test
    fun `when Google refuses our credentials the push fails without deleting the device, when it is down the push is retried`() {
        google.tokenStatus = 400
        google.tokenBody = """{"error":"invalid_grant","error_description":"Invalid JWT Signature."}"""
        assertThat(sender().send(device(), message())).isInstanceOf(PushResult.Failed::class.java)

        google.tokenStatus = 503
        assertThat(sender().send(device(), message())).isInstanceOf(PushResult.Retry::class.java)

        google.tokenStatus = 200
        google.tokenBody = "garbage"
        assertThat(sender().send(device(), message())).isInstanceOf(PushResult.Failed::class.java)

        assertThat(google.sends).isEmpty()
    }

    // ---- 비밀

    @Test
    fun `no secret ends up in a failure reason, a toString or the registration answer`() {
        val secrets = listOf(token, "ya29.token-1", account.privatePem.lines()[1], account.base64(), "secret-token-value")
        val reasons = mutableListOf<String>()
        listOf(
            400 to fcmError(400, "INVALID_ARGUMENT", errorCode = "INVALID_ARGUMENT", violationField = "message.android.ttl"),
            403 to fcmError(403, "PERMISSION_DENIED"),
            400 to fcmError(400, "FAILED_PRECONDITION", errorCode = "THIRD_PARTY_AUTH_ERROR"),
        ).forEach { (status, body) ->
            google.sendStatus = status
            google.sendBody = body
            (sender().send(device(), message()) as PushResult.Failed).reason.let(reasons::add)
        }
        google.tokenStatus = 400
        google.tokenBody = """{"error":"invalid_grant","error_description":"bad ${account.clientEmail}"}"""
        val fresh = sender()
        (fresh.send(device(), message()) as PushResult.Failed).reason.let(reasons::add)

        val everything = reasons + sender().toString() + properties.toString() + FcmConfigCheck.credentialsOf(properties).toString()
        secrets.forEach { secret -> assertThat(everything).noneMatch { it.contains(secret) } }
        assertThat(reasons).allMatch { it.length < 200 }
    }

    // ---- 등록 검사

    @Test
    fun `registration accepts android and ios tokens of 1 to 4096 chars and refuses the rest`() {
        val sender = sender()
        fun check(token: String, platform: String) = sender.validate(PushRegistration("FCM", platform, token))
        assertThat(check("a", "android")).isNull()
        assertThat(check("t".repeat(4096), "ios")).isNull()
        assertThat(check("", "android")).isNotNull()
        assertThat(check("t".repeat(4097), "android")).isNotNull()
        assertThat(check("tok en", "android")).isNotNull()
        assertThat(check("token", "web")).isNotNull()
        assertThat(check("t".repeat(4097), "android")).doesNotContain("tttt")
    }

    @Test
    fun `the sender is the FCM kind and has no public key`() {
        assertThat(sender().kind).isEqualTo("FCM")
        assertThat(sender().publicKey).isNull()
    }

}
