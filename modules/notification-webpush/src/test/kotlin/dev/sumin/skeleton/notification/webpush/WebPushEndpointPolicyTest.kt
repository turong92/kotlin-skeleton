package dev.sumin.skeleton.notification.webpush

import java.net.InetAddress
import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat

/** SSRF 방어 — 서버가 남이 준 주소로 POST 하므로, 허용된 푸시 서비스가 아니면 요청이 나가기 전에 거절한다 */
class WebPushEndpointPolicyTest {
    private val defaults = WebPushEndpointPolicy(WebPushProperties.DEFAULT_ALLOWED_HOSTS)

    @Test
    fun `known push services are accepted`() {
        listOf(
            "https://fcm.googleapis.com/fcm/send/abc",
            "https://updates.push.services.mozilla.com/wpush/v2/abc",
            "https://web.push.apple.com/QAbc",
            "https://wns2-par02p.notify.windows.com/w/?token=abc",
            "https://fcm.googleapis.com:443/fcm/send/abc",
        ).forEach { assertThat(defaults.check(it)).describedAs(it).isNull() }
    }

    @Test
    fun `anything else is rejected before a request is made`() {
        listOf(
            "http://fcm.googleapis.com/fcm/send/abc",               // https 아님
            "https://fcm.googleapis.com:8443/fcm/send/abc",         // 다른 포트
            "https://example.com/push",                              // 목록 밖
            "https://fcm.googleapis.com.evil.example/x",             // 앞부분만 같은 가짜
            "https://evilpush.apple.com/x",                          // 와일드카드는 점 경계
            "https://push.apple.com/x",                              // *.push.apple.com 은 apex 를 포함하지 않는다
            "https://fcm.googleapis.com@evil.example/x",             // userinfo 로 호스트 속이기
            "https://user:pw@fcm.googleapis.com/x",
            "https://127.0.0.1/x",
            "https://[::1]/x",
            "https://169.254.169.254/latest/meta-data",
            "https://fcm.googleapis.com\\@evil.example/x",
            "ftp://fcm.googleapis.com/x",
            "fcm.googleapis.com/x",
            "",
        ).forEach { assertThat(defaults.check(it)).describedAs(it).isNotNull() }
    }

    @Test
    fun `an http entry in the list opens exactly that origin`() {
        val policy = WebPushEndpointPolicy(listOf("http://127.0.0.1:9100"))
        assertThat(policy.check("http://127.0.0.1:9100/push/abc")).isNull()
        assertThat(policy.check("http://127.0.0.1:9101/push/abc")).isNotNull()
        assertThat(policy.check("https://127.0.0.1:9100/push/abc")).isNotNull()
        assertThat(policy.check("http://localhost:9100/push/abc")).isNotNull()
    }

    @Test
    fun `a host that resolves to a private address is rejected at send time`() {
        listOf("10.0.0.5", "127.0.0.1", "169.254.169.254", "192.168.1.1", "172.16.0.9", "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "fe80::1")
            .forEach { ip ->
                val policy = WebPushEndpointPolicy(WebPushProperties.DEFAULT_ALLOWED_HOSTS) { listOf(InetAddress.getByName(ip)) }
                assertThat(policy.checkForSend("https://fcm.googleapis.com/fcm/send/abc")).describedAs(ip).isNotNull()
            }
    }

    @Test
    fun `one private address among public ones is enough to reject`() {
        val policy = WebPushEndpointPolicy(WebPushProperties.DEFAULT_ALLOWED_HOSTS) {
            listOf(InetAddress.getByName("142.250.0.1"), InetAddress.getByName("10.1.2.3"))
        }
        assertThat(policy.checkForSend("https://fcm.googleapis.com/fcm/send/abc")).isNotNull()
    }

    @Test
    fun `a public address passes and an explicit http origin is not resolved at all`() {
        val publicOnly = WebPushEndpointPolicy(WebPushProperties.DEFAULT_ALLOWED_HOSTS) { listOf(InetAddress.getByName("142.250.0.1")) }
        assertThat(publicOnly.checkForSend("https://fcm.googleapis.com/fcm/send/abc")).isNull()
        val dev = WebPushEndpointPolicy(listOf("http://127.0.0.1:9100")) { error("must not resolve") }
        assertThat(dev.checkForSend("http://127.0.0.1:9100/x")).isNull()
    }

    @Test
    fun `entries are classified - malformed ones never match anything`() {
        assertThat(WebPushEndpointPolicy.invalidEntries(listOf("*", "*.com", "https://x.example", "a b", "", "fcm.googleapis.com", "*.push.apple.com", "http://127.0.0.1:9100", "http://127.0.0.1")))
            .containsExactly("*", "*.com", "https://x.example", "a b", "", "http://127.0.0.1")
        assertThat(WebPushEndpointPolicy.httpEntries(listOf("fcm.googleapis.com", "http://127.0.0.1:9100", "HTTP://localhost:80"))).hasSize(2)
        val star = WebPushEndpointPolicy(listOf("*", "*.com"))
        assertThat(star.check("https://anything.com/x")).isNotNull()
    }
}
