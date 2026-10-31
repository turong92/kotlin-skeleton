package dev.sumin.skeleton.notification.webpush

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.notification.push.PushDevice
import dev.sumin.skeleton.notification.push.PushMessage
import dev.sumin.skeleton.notification.push.PushPlatforms
import dev.sumin.skeleton.notification.push.PushRegistration
import dev.sumin.skeleton.notification.push.PushResult
import dev.sumin.skeleton.notification.push.PushUrgency
import java.net.URI
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat

class WebPushSenderTest {
    private val now = Instant.parse("2026-10-31T09:00:00Z")
    private val vapid = TestKeys()
    private val subscription = TestSubscription()
    private val service = FakePushService()

    @AfterTest
    fun stop() = service.close()

    private fun properties(allowed: List<String> = listOf(service.origin), subject: String = "mailto:ops@example.com") =
        WebPushProperties(enabled = true, publicKey = vapid.vapidPublic, privateKey = vapid.vapidPrivate, subject = subject, allowedHosts = allowed)

    private fun sender(
        props: WebPushProperties = properties(),
        http: WebPushHttp = JdkWebPushHttp(),
        resolver: (String) -> List<java.net.InetAddress> = { error("not expected") },
    ) = WebPushSender(props, WebPushConfigCheck.keysOf(props)!!, WebPushEndpointPolicy(props.allowedHosts, resolver), http, { now })

    private fun device(endpoint: String = "${service.origin}/push/abc") = PushDevice(
        id = "dev1", accountId = "acc1", installationId = "inst-0123456789abcdef", sessionId = null, kind = "WEBPUSH", platform = PushPlatforms.WEB,
        address = endpoint, p256dh = subscription.p256dh, authSecret = subscription.auth, keyId = null, label = null,
        createdAt = now, lastSeenAt = now, lastSuccessAt = null,
    )

    private fun message(urgency: PushUrgency = PushUrgency.NORMAL, tag: String? = "comment:42") = PushMessage(
        eventId = "evt1", type = "comment.created", title = "새 댓글", body = "안녕", link = "/notes/1", tag = tag,
        urgency = urgency, createdAt = now, expiresAt = now.plus(Duration.ofHours(1)),
    )

    @Test
    fun `the push service receives a body only the subscription can read, with TTL Topic Urgency and a valid VAPID token`() {
        val result = sender().send(device(), message(PushUrgency.HIGH))

        assertThat(result).isEqualTo(PushResult.Delivered)
        val request = service.requests.single()
        assertThat(request.path).isEqualTo("/push/abc")
        assertThat(request.headers["content-encoding"]).isEqualTo("aes128gcm")
        assertThat(request.headers["ttl"]).isEqualTo("3600")
        assertThat(request.headers["urgency"]).isEqualTo("high")
        val topic = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest("comment:42".toByteArray())).take(32)
        assertThat(request.headers["topic"]).isEqualTo(topic)

        val payload = jsonOf(subscription.decrypt(request.body))
        assertThat(payload["v"].asInt()).isEqualTo(1)
        assertThat(payload["id"].asString()).isEqualTo("evt1")
        assertThat(payload["type"].asString()).isEqualTo("comment.created")
        assertThat(payload["title"].asString()).isEqualTo("새 댓글")
        assertThat(payload["body"].asString()).isEqualTo("안녕")
        assertThat(payload["link"].asString()).isEqualTo("/notes/1")
        assertThat(payload["tag"].asString()).isEqualTo("comment:42")

        val claims = verifyVapid(request.headers.getValue("authorization"), vapid.vapidPublic)
        assertThat(claims["aud"].asString()).isEqualTo(service.origin)
        assertThat(claims["sub"].asString()).isEqualTo("mailto:ops@example.com")
        val exp = claims["exp"].asLong()
        assertThat(exp).isGreaterThan(now.epochSecond).isLessThanOrEqualTo(now.epochSecond + 24 * 3600)
    }

    @Test
    fun `without a tag there is no Topic and NORMAL urgency is sent as normal`() {
        sender().send(device(), message(tag = null))
        val headers = service.requests.single().headers
        assertThat(headers).doesNotContainKey("topic")
        assertThat(headers["urgency"]).isEqualTo("normal")
    }

    @Test
    fun `404 and 410 remove the device, nothing else does`() {
        listOf(404, 410).forEach { service.status = it; assertThat(sender().send(device(), message())).describedAs("$it").isEqualTo(PushResult.Gone) }
        // 우리 VAPID 설정이 틀렸을 때 구독이 전부 지워지면 안 된다
        listOf(400, 401, 403, 413, 418).forEach {
            service.status = it
            assertThat(sender().send(device(), message())).describedAs("$it").isInstanceOf(PushResult.Failed::class.java)
        }
    }

    @Test
    fun `429 and 5xx are retried, honouring Retry-After`() {
        service.status = 429
        service.extraHeaders = mapOf("Retry-After" to "120")
        assertThat(sender().send(device(), message())).isEqualTo(PushResult.Retry(Duration.ofSeconds(120)))
        service.extraHeaders = emptyMap()
        listOf(500, 502, 503).forEach { service.status = it; assertThat(sender().send(device(), message())).isEqualTo(PushResult.Retry()) }
    }

    @Test
    fun `a redirect is not followed and the target gets no request`() {
        FakePushService().use { target ->
            service.status = 307
            service.extraHeaders = mapOf("Location" to "${target.origin}/internal")
            val result = sender(properties(listOf(service.origin, target.origin))).send(device(), message())
            assertThat(result).isInstanceOf(PushResult.Failed::class.java)
            assertThat(target.requests).isEmpty()
            assertThat(service.requests).hasSize(1)
        }
    }

    @Test
    fun `a connection that cannot be made is retried`() {
        val dead = FakePushService().also { it.close() }
        val props = properties(listOf(dead.origin))
        assertThat(sender(props).send(device("${dead.origin}/push/abc"), message())).isEqualTo(PushResult.Retry())
    }

    @Test
    fun `an endpoint outside the allow-list or resolving to a private address never leaves the server`() {
        val sent = mutableListOf<URI>()
        val recording = WebPushHttp { url, _, _, _ -> sent += url; WebPushHttpResponse(201, null) }
        val defaults = properties(WebPushProperties.DEFAULT_ALLOWED_HOSTS)

        assertThat(sender(defaults, recording).send(device("https://evil.example/push"), message())).isInstanceOf(PushResult.Failed::class.java)
        assertThat(sender(defaults, recording).send(device("http://fcm.googleapis.com/push"), message())).isInstanceOf(PushResult.Failed::class.java)
        val rebinding = sender(defaults, recording) { listOf(java.net.InetAddress.getByName("169.254.169.254")) }
        assertThat(rebinding.send(device("https://fcm.googleapis.com/fcm/send/abc"), message())).isInstanceOf(PushResult.Failed::class.java)
        assertThat(sent).isEmpty()
    }

    @Test
    fun `validate refuses a bad endpoint or bad keys with a reason that carries no secret`() {
        val s = sender()
        val ok = PushRegistration("WEBPUSH", PushPlatforms.WEB, "${service.origin}/push/abc", subscription.p256dh, subscription.auth)
        assertThat(s.validate(ok)).isNull()
        assertThat(s.validate(ok.copy(address = "https://evil.example/push"))).isNotNull().doesNotContain("evil.example")
        assertThat(s.validate(ok.copy(p256dh = null))).isNotNull()
        assertThat(s.validate(ok.copy(p256dh = "AAAA"))).isNotNull()
        assertThat(s.validate(ok.copy(p256dh = WebPushKeys.encode(ByteArray(65) { if (it == 0) 4 else 1 })))).isNotNull()   // 곡선 위의 점이 아니다
        assertThat(s.validate(ok.copy(authSecret = WebPushKeys.encode(ByteArray(15))))).isNotNull()
        assertThat(s.validate(ok.copy(authSecret = null))).isNotNull()
    }

    @Test
    fun `no failure reason, log text or toString carries the private key, the endpoint or the auth secret`() {
        val props = properties()
        val s = sender(props)
        service.status = 403
        val failed = s.send(device(), message()) as PushResult.Failed
        val texts = listOf(failed.reason, s.toString(), props.toString(), device().toString()) +
            listOf(s.validate(PushRegistration("WEBPUSH", PushPlatforms.WEB, "https://evil.example/${vapid.vapidPrivate}", "x", "y"))!!)
        texts.forEach {
            assertThat(it).doesNotContain(vapid.vapidPrivate).doesNotContain(subscription.auth).doesNotContain("/push/abc")
        }
    }

    @Test
    fun `an oversized payload is a permanent failure, not a retry`() {
        val big = message().copy(body = "가".repeat(2000))   // 6000바이트 > 한 레코드
        assertThat(sender().send(device(), big)).isInstanceOf(PushResult.Failed::class.java)
        assertThat(service.requests).isEmpty()
    }

    @Test
    fun `an already expired message is sent with TTL 0, never a negative number`() {
        sender().send(device(), message().copy(expiresAt = now.minusSeconds(5)))
        assertThat(service.requests.single().headers["ttl"]).isEqualTo("0")
    }

    @Test
    fun `the public key and kind are what the screen subscribes with`() {
        val s = sender()
        assertThat(s.kind).isEqualTo("WEBPUSH")
        assertThat(s.publicKey).isEqualTo(vapid.vapidPublic)
        assertThat(TimeProvider { now }.now()).isEqualTo(now)
    }
}
