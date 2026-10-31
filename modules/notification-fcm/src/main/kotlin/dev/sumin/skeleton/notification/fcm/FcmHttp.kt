package dev.sumin.skeleton.notification.fcm

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class FcmHttpResponse(val status: Int, val body: String?, val retryAfter: String? = null)

/**
 * 구글로 POST 한 번(토큰 발급 · 메시지 보내기). 상태 코드와 본문은 있는 그대로 돌려주고(판정은 [FcmSender]), 연결 · 시간 초과는 [IOException] 으로 던진다.
 * 구현은 **리다이렉트를 따라가지 않아야 한다**. 주소는 호출하는 쪽이 고정한 것만 온다.
 */
fun interface FcmHttp {
    fun post(url: URI, headers: Map<String, String>, body: ByteArray, timeout: Duration): FcmHttpResponse
}

/** JDK 클라이언트 - 리다이렉트 따라가지 않음, 응답 본문은 64KiB 까지만 읽는다 */
class JdkFcmHttp(connectTimeout: Duration = Duration.ofSeconds(5)) : FcmHttp {
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(connectTimeout)
        .build()

    override fun post(url: URI, headers: Map<String, String>, body: ByteArray, timeout: Duration): FcmHttpResponse {
        val request = HttpRequest.newBuilder(url).timeout(timeout).POST(HttpRequest.BodyPublishers.ofByteArray(body)).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val text = response.body().use { String(it.readNBytes(MAX_BODY_BYTES), Charsets.UTF_8) }
        return FcmHttpResponse(response.statusCode(), text, response.headers().firstValue("Retry-After").orElse(null))
    }

    private companion object {
        const val MAX_BODY_BYTES = 64 * 1024
    }
}
