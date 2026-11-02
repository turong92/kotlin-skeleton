package dev.sumin.skeleton.storage.image.imgproxy

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `skeleton.storage.image.imgproxy.*` - imgproxy 컨테이너로 변환하는 선택 엔진. **기본 꺼짐**(앱 yml 이 켠다). 켰는데 [url] · [key] · [salt] 중 하나라도 없으면 기동 실패.
 * [key] · [salt] 는 imgproxy 의 `IMGPROXY_KEY` · `IMGPROXY_SALT`(16진수)와 같은 값이다 - 비밀이라 환경변수/비밀 저장소로만 준다. 상한(`timeout` · `max-response-bytes` · `source-ttl`)은 안전 장치라 모듈 기본으로 둔다.
 */
@ConfigurationProperties("skeleton.storage.image.imgproxy")
data class ImgproxyProperties(
    val enabled: Boolean = false,
    /** imgproxy 의 주소(앱에서 닿는 곳) - 예 `http://imgproxy:8080` */
    val url: String = "",
    val key: String = "",
    val salt: String = "",
    /** 요청 하나(imgproxy 가 원본을 받아 변환해 돌려주기까지)의 시간 상한 - 넘으면 일시 오류로 보고 큐가 다시 한다 */
    val timeout: Duration = Duration.ofSeconds(30),
    /** 변환본 응답 바이트 상한 - 넘으면 거절한다 */
    val maxResponseBytes: Long = 20L * 1024 * 1024,
    /** imgproxy 에 주는 **원본 서명 주소**의 수명 - imgproxy 가 받아 가기에 충분한 만큼만 */
    val sourceTtl: Duration = Duration.ofMinutes(5),
) {
    init {
        if (enabled) {
            require(url.isNotBlank()) { "skeleton.storage.image.imgproxy.url is required when the imgproxy engine is enabled (the address of the imgproxy container)" }
            require(key.isNotBlank()) { "skeleton.storage.image.imgproxy.key is required when the imgproxy engine is enabled (IMGPROXY_KEY, hex) - an unsigned imgproxy is not allowed" }
            require(salt.isNotBlank()) { "skeleton.storage.image.imgproxy.salt is required when the imgproxy engine is enabled (IMGPROXY_SALT, hex) - an unsigned imgproxy is not allowed" }
            require(isHex(key)) { "skeleton.storage.image.imgproxy.key must be hex (same as IMGPROXY_KEY)" }
            require(isHex(salt)) { "skeleton.storage.image.imgproxy.salt must be hex (same as IMGPROXY_SALT)" }
        }
        require(!timeout.isZero && !timeout.isNegative) { "skeleton.storage.image.imgproxy.timeout must be positive" }
        require(maxResponseBytes > 0) { "skeleton.storage.image.imgproxy.max-response-bytes must be positive" }
        require(!sourceTtl.isZero && !sourceTtl.isNegative) { "skeleton.storage.image.imgproxy.source-ttl must be positive" }
    }

    private fun isHex(s: String) = s.length % 2 == 0 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
