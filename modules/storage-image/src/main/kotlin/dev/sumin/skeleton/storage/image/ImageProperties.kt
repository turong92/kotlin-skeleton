package dev.sumin.skeleton.storage.image

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `skeleton.storage.image.*` - 이미지 변환본. **기본 꺼짐**(앱 yml 이 켠다). 상한(`max-pixels` · `max-source-bytes` · `max-concurrent`)은 안전 장치라 모듈 기본으로 두고 설정으로 덮는다.
 * 변환본 이름 둘(`thumb` · `display`)은 board · review · 화면이 아는 계약이고 크기 · 품질만 앱이 정한다.
 */
@ConfigurationProperties("skeleton.storage.image")
data class ImageProperties(
    val enabled: Boolean = false,
    /** 변환본이 놓이는 저장소 접두사 - 업로드 `key-prefix` · 공개 `prefix` 와 겹치면 기동 실패 */
    val prefix: String = "variants",
    /** 이 픽셀 수(폭 x 높이)를 넘는 이미지는 디코드하지 않고 거절한다 */
    val maxPixels: Long = 100_000_000L,
    /** 원본 바이트 상한 */
    val maxSourceBytes: Long = 50L * 1024 * 1024,
    /** 인스턴스당 동시에 변환하는 작업 수 - 기다리는 동안 큐 일꾼 스레드를 잡는다 */
    val maxConcurrent: Int = 1,
    /** 변환본 서명 읽기 주소(`POST /api/v1/storage/images`)의 수명 */
    val accessTtl: Duration = Duration.ofMinutes(10),
    val variants: Variants = Variants(),
    val http: Http = Http(),
) {
    init {
        require(prefix.trim('/').isNotEmpty()) { "skeleton.storage.image.prefix must not be blank" }
        require(maxPixels > 0) { "skeleton.storage.image.max-pixels must be positive" }
        require(maxSourceBytes > 0) { "skeleton.storage.image.max-source-bytes must be positive" }
        require(maxConcurrent >= 1) { "skeleton.storage.image.max-concurrent must be at least 1" }
        require(!accessTtl.isZero && !accessTtl.isNegative) { "skeleton.storage.image.access-ttl must be positive" }
    }

    /** 긴 변을 상자에 맞춘다(키우지 않는다). [quality] 는 JPEG 품질 1..100 */
    data class Size(val maxWidth: Int, val maxHeight: Int, val quality: Int) {
        init {
            require(maxWidth >= 1 && maxHeight >= 1) { "an image variant needs a positive max-width and max-height" }
            require(quality in 1..100) { "an image variant quality must be 1..100" }
        }
    }

    data class Variants(
        val thumb: Size = Size(400, 400, 82),
        val display: Size = Size(1600, 1600, 82),
    )

    data class Http(
        /** false 면 `POST /api/v1/storage/images` 를 등록하지 않는다 */
        val enabled: Boolean = true,
    )

    /** 변환본 이름 -> 크기 (이름은 [dev.sumin.skeleton.common.upload.UploadImages.THUMB] · `DISPLAY`) */
    fun sizes(): Map<String, Size> = mapOf(
        dev.sumin.skeleton.common.upload.UploadImages.THUMB to variants.thumb,
        dev.sumin.skeleton.common.upload.UploadImages.DISPLAY to variants.display,
    )
}
