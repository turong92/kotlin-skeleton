package dev.sumin.skeleton.notification.webpush

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class WebPushHttpResponse(val status: Int, val retryAfter: String?)

/**
 * 푸시 서비스로 POST 한 번. 상태 코드는 있는 그대로 돌려주고(판정은 [WebPushSender]), 연결 · 시간 초과는 [java.io.IOException] 으로 던진다.
 * 구현은 **리다이렉트를 따라가지 않아야 한다**(끝점 검사를 우회하는 길이다).
 */
fun interface WebPushHttp {
    fun post(url: URI, headers: Map<String, String>, body: ByteArray, timeout: Duration): WebPushHttpResponse
}

/** JDK 클라이언트 - 리다이렉트 따라가지 않음, 응답 본문은 읽지 않고 버린다. 공용 `ExternalHttpClient` 는 본문 없는 201 응답에서 상태 코드를 돌려주지 못해 쓰지 않는다 */
class JdkWebPushHttp(connectTimeout: Duration = Duration.ofSeconds(5)) : WebPushHttp {
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(connectTimeout)
        .build()

    override fun post(url: URI, headers: Map<String, String>, body: ByteArray, timeout: Duration): WebPushHttpResponse {
        val request = HttpRequest.newBuilder(url).timeout(timeout).POST(HttpRequest.BodyPublishers.ofByteArray(body)).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        return WebPushHttpResponse(response.statusCode(), response.headers().firstValue("Retry-After").orElse(null))
    }
}
