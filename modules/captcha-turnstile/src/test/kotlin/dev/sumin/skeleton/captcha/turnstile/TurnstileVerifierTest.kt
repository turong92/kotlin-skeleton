package dev.sumin.skeleton.captcha.turnstile

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.ExternalHttpRequestSpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono

class TurnstileVerifierTest {
    /** postForm 만 기록하는 가짜 ExternalHttpClient */
    private class FakeHttp(private val reply: () -> TurnstileSiteverifyResponse) : ExternalHttpClient {
        var lastForm: Map<String, String>? = null
        var lastPath: String? = null

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> postForm(clientName: String, path: String, form: Map<String, String>, responseType: Class<T>, customize: ExternalHttpRequestSpec.() -> Unit): Mono<T> {
            lastForm = form; lastPath = path
            return Mono.fromCallable { reply() as T }
        }
        override fun <T : Any> get(clientName: String, path: String, responseType: Class<T>, customize: ExternalHttpRequestSpec.() -> Unit): Mono<T> = TODO()
        override fun <T : Any> post(clientName: String, path: String, body: Any?, responseType: Class<T>, customize: ExternalHttpRequestSpec.() -> Unit): Mono<T> = TODO()
        override fun <T : Any> put(clientName: String, path: String, body: Any?, responseType: Class<T>, customize: ExternalHttpRequestSpec.() -> Unit): Mono<T> = TODO()
        override fun <T : Any> patch(clientName: String, path: String, body: Any?, responseType: Class<T>, customize: ExternalHttpRequestSpec.() -> Unit): Mono<T> = TODO()
        override fun <T : Any> delete(clientName: String, path: String, responseType: Class<T>, customize: ExternalHttpRequestSpec.() -> Unit): Mono<T> = TODO()
    }

    private val props = TurnstileProperties(enabled = true, secretKey = "sk", expectedHostname = "ovation.example.com")

    @Test
    fun `secret, response, remoteip 를 form 으로 보내고 success 를 돌려준다`() {
        val http = FakeHttp { TurnstileSiteverifyResponse(success = true, hostname = "ovation.example.com", action = "submit") }
        val result = TurnstileVerifier(http, props).verify("tok", "1.2.3.4")
        assertTrue(result.success)
        assertEquals(mapOf("secret" to "sk", "response" to "tok", "remoteip" to "1.2.3.4"), http.lastForm)
        assertEquals(props.siteverifyUrl, http.lastPath)
    }

    @Test
    fun `hostname 이 다르면 실패, 토큰 없으면 호출 없이 실패, 네트워크 오류는 internal-error`() {
        val wrongHost = FakeHttp { TurnstileSiteverifyResponse(success = true, hostname = "evil.example.com") }
        assertEquals(listOf("hostname-mismatch"), TurnstileVerifier(wrongHost, props).verify("tok").errorCodes)

        val untouched = FakeHttp { error("must not be called") }
        val missing = TurnstileVerifier(untouched, props).verify("")
        assertFalse(missing.success)
        assertEquals(listOf("missing-input-response"), missing.errorCodes)
        assertEquals(null, untouched.lastForm)

        val down = FakeHttp { throw IllegalStateException("timeout") }
        assertEquals(listOf("internal-error"), TurnstileVerifier(down, props).verify("tok").errorCodes)
    }
}
