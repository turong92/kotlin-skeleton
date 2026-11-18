package dev.sumin.skeleton.jobqueue.cloudtasks

import dev.sumin.skeleton.common.time.TimeProvider
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import tools.jackson.databind.json.JsonMapper

/**
 * 실행 계정의 접근 토큰 — 메타데이터 서버(`GET /computeMetadata/v1/instance/service-accounts/default/token`, `Metadata-Flavor: Google`).
 * `expires_in - 60초` 까지 캐시한다. 실패 메시지에는 상태 코드 · 예외 종류만 싣는다(토큰 · 응답 본문은 싣지 않는다).
 */
class MetadataTokenSource(
    metadataUrl: String,
    private val timeout: Duration,
    private val time: TimeProvider = TimeProvider.systemUtc(),
) {
    private val url = URI.create("${metadataUrl.trimEnd('/')}/computeMetadata/v1/instance/service-accounts/default/token")
    private val json = JsonMapper.builder().build()
    private val http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build()

    @Volatile private var cached: Cached? = null

    @Synchronized
    fun token(): String {
        val now = time.now()
        cached?.takeIf { now.isBefore(it.refreshAt) }?.let { return it.value }
        val request = HttpRequest.newBuilder(url).timeout(timeout).header("Metadata-Flavor", "Google").GET().build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("metadata token call was interrupted")
        } catch (e: IOException) {
            throw IOException("metadata token call did not get through: ${e.javaClass.simpleName}")
        }
        if (response.statusCode() !in 200..299) throw IOException("metadata token call was refused: HTTP ${response.statusCode()}")
        val tree = runCatching { json.readTree(response.body()) }.getOrNull()
        val value = tree?.get("access_token")?.asString()?.takeIf { it.isNotBlank() } ?: throw IOException("metadata token response could not be read")
        val seconds = tree["expires_in"]?.asLong()?.takeIf { it > 0 } ?: DEFAULT_EXPIRES_SECONDS
        return value.also { cached = Cached(it, now.plusSeconds(seconds).minusSeconds(EARLY_REFRESH_SECONDS)) }
    }

    private class Cached(val value: String, val refreshAt: Instant)

    private companion object {
        const val EARLY_REFRESH_SECONDS = 60L
        const val DEFAULT_EXPIRES_SECONDS = 300L
    }
}
