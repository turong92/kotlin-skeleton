package dev.sumin.skeleton.ads.admob

import dev.sumin.skeleton.common.time.TimeProvider
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import tools.jackson.databind.json.JsonMapper

/** 구글 서명 공개키의 출처(`key_id` → 키). 교체 지점 — 시험은 자기 키 쌍을 돌려준다. 못 읽으면 [AdmobKeysUnavailableException]. */
fun interface AdmobKeySource {
    fun load(): Map<String, PublicKey>
}

/** `key_id` 의 공개키. 모르는 키면 null, 키 목록을 못 읽으면 [AdmobKeysUnavailableException]. */
fun interface AdmobVerifierKeys {
    fun find(keyId: String): PublicKey?
}

/**
 * 읽어 둔 키를 [ttl](구글 안내: 24시간 이하) 동안만 쥔다. 모르는 `key_id` 는 키 교체일 수 있어 목록을 한 번 다시 읽되,
 * 아무 `key_id` 나 보내 구글 쪽을 매 요청 두들기지 못하게 마지막으로 읽은 뒤 [MIN_RELOAD_INTERVAL] 안에는 다시 읽지 않는다.
 */
class CachingAdmobVerifierKeys(
    private val source: AdmobKeySource,
    private val time: TimeProvider,
    private val ttl: Duration,
) : AdmobVerifierKeys {
    private var keys: Map<String, PublicKey> = emptyMap()
    private var loadedAt: Instant? = null
    private var attemptedAt: Instant? = null   // 성공이든 실패든 마지막으로 읽어 본 시각 — 다시 읽기 바닥은 이걸 본다

    @Synchronized
    override fun find(keyId: String): PublicKey? {
        val now = time.now()
        val loaded = loadedAt
        val stale = loaded == null || !now.isBefore(loaded.plus(ttl))
        val unknown = !stale && keyId !in keys
        if (stale || unknown) {
            val tooSoon = attemptedAt?.let { now.isBefore(it.plus(MIN_RELOAD_INTERVAL)) } ?: false
            if (tooSoon) {
                if (stale) throw AdmobKeysUnavailableException()   // 방금 못 읽었다 — 구글을 두들기지 않고 바로 503
                return null
            }
            reload(now)
        }
        return keys[keyId]
    }

    private fun reload(now: Instant) {
        attemptedAt = now
        keys = try {
            source.load()
        } catch (e: AdmobKeysUnavailableException) {
            throw e
        } catch (e: Exception) {
            throw AdmobKeysUnavailableException(e)
        }
        loadedAt = now
    }

    companion object {
        val MIN_RELOAD_INTERVAL: Duration = Duration.ofMinutes(1)
    }
}

/** `{"keys":[{"keyId":123,"pem":"…","base64":"<DER X.509>"}]}` 형식의 구글 키 목록을 읽는다. */
class HttpAdmobKeySource(private val url: String, private val timeout: Duration) : AdmobKeySource {
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(timeout).build()
    private val mapper = JsonMapper.builder().build()

    override fun load(): Map<String, PublicKey> = try {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw AdmobKeysUnavailableException()
        val factory = KeyFactory.getInstance("EC")
        mapper.readTree(response.body()).path("keys").associate { node ->
            node.path("keyId").asString() to factory.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(node.path("base64").asString())))
        }
    } catch (e: AdmobKeysUnavailableException) {
        throw e
    } catch (e: Exception) {
        if (e is InterruptedException) Thread.currentThread().interrupt()
        throw AdmobKeysUnavailableException(e)
    }
}
