package dev.sumin.skeleton.storage

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.upload.PublicUploadPurger
import java.time.Duration

/**
 * 공개 객체를 지운 뒤 Cloudflare CDN 캐시에서 그 주소를 비운다: `POST {api-url}/zones/{zone-id}/purge_cache` `{"files":[<절대 주소>…]}`
 * (한 번에 [BATCH] 개까지 - Cloudflare 의 파일별 비우기 한도). 인증은 `Authorization: Bearer <api-token>`. 실패하면 예외 - 호출한 쪽(board 의 조정자)이
 * 항목을 큐에 남겨 다시 시도한다(지우기가 먼저이고 이미 끝났다). 부를 때마다 지금 프로젝트의 설정을 읽는다.
 */
class CloudflarePublicUploadPurger(
    private val http: ExternalHttpClient,
    private val propertiesOf: () -> StoragePublicProperties,
) : PublicUploadPurger {
    override fun purge(publicUrls: Collection<String>) {
        if (publicUrls.isEmpty()) return
        val cf = propertiesOf().purge.cloudflare
        if (!cf.enabled) return
        for (batch in publicUrls.distinct().chunked(BATCH)) {
            @Suppress("UNCHECKED_CAST")
            val reply = http.post(
                CLIENT_NAME, "${cf.apiUrl.trimEnd('/')}/zones/${cf.zoneId}/purge_cache", mapOf("files" to batch), Map::class.java as Class<Map<String, Any?>>,
            ) {
                header("Authorization", "Bearer ${cf.apiToken}")
                timeout(cf.timeout.coerceAtLeast(Duration.ofMillis(100)))
            }.block(cf.timeout.coerceAtLeast(Duration.ofMillis(100)))
            check(reply?.get("success") == true) { "Cloudflare purge_cache did not succeed: ${reply?.get("errors")}" }
        }
    }

    companion object {
        /** 한 번의 요청에 담는 주소 수 */
        const val BATCH = 30

        /** platform outbound HTTP 클라이언트 이름 (`skeleton.http.clients.cloudflare-purge.*` 로 타임아웃 등 조정) */
        const val CLIENT_NAME = "cloudflare-purge"
    }
}
