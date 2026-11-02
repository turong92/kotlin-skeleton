package dev.sumin.skeleton.storage.image.pdfbox

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `skeleton.storage.image.pdf.*` - PDF 1쪽 썸네일. **기본 꺼짐**(앱 yml 이 켠다). PDF 파서는 무겁고 공격면이 넓어 상한이 모두 안전 장치다 - 모듈 기본으로 두고 설정으로 덮는다.
 */
@ConfigurationProperties("skeleton.storage.image.pdf")
data class PdfProperties(
    val enabled: Boolean = false,
    /** 원본 바이트 상한 - 넘으면 읽지 않고 거절한다 (storage-image 의 `max-source-bytes` 와 따로, 더 좁게) */
    val maxSourceBytes: Long = 20L * 1024 * 1024,
    /** 쪽 수 상한 - 넘으면 거절한다 */
    val maxPages: Int = 500,
    /** 1쪽의 크기 상한 - 가로 x 세로를 **포인트(1/72 인치)** 로 곱한 값. A4 는 약 50만, 1미터 정사각형은 약 800만 */
    val maxPagePixels: Long = 25_000_000L,
    /** 쪽 안 이미지 하나의 픽셀 수(폭 x 높이) 상한 - 넘으면 디코드하기 전에 거절한다. 기본 1600만(디코드 약 64MB). 압축된 거대 이미지가 힙을 채우는 것을 막는다 */
    val maxImagePixels: Long = 16_000_000L,
    /** 1쪽을 그리는 시간 상한 - 넘으면 그리기를 중단하고 거절한다 */
    val renderTimeout: Duration = Duration.ofSeconds(20),
    /** PDFBox 가 한 문서에 메모리로 쓰는 상한 - 넘는 만큼은 임시 파일로 간다 */
    val maxMainMemoryBytes: Long = 16L * 1024 * 1024,
    /** PDFBox 가 한 문서에 임시 파일로 쓰는 상한 - 넘으면 거절한다 */
    val maxTempBytes: Long = 256L * 1024 * 1024,
) {
    init {
        require(maxSourceBytes > 0) { "skeleton.storage.image.pdf.max-source-bytes must be positive" }
        require(maxPages >= 1) { "skeleton.storage.image.pdf.max-pages must be at least 1" }
        require(maxPagePixels > 0) { "skeleton.storage.image.pdf.max-page-pixels must be positive" }
        require(maxImagePixels > 0) { "skeleton.storage.image.pdf.max-image-pixels must be positive" }
        require(!renderTimeout.isZero && !renderTimeout.isNegative) { "skeleton.storage.image.pdf.render-timeout must be positive" }
        require(maxMainMemoryBytes >= 0) { "skeleton.storage.image.pdf.max-main-memory-bytes must not be negative" }
        require(maxTempBytes > 0) { "skeleton.storage.image.pdf.max-temp-bytes must be positive" }
    }
}
