package dev.sumin.skeleton.storage.image.ffmpeg

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `skeleton.storage.image.ffmpeg.*` - 움직이는 GIF · WebP 표시본을 앱 안에서 ffmpeg 로 소리 없는 반복 MP4(투명이면 움직이는 WebP)로 바꾼다. **기본 꺼짐**(앱 yml 이 켠다).
 * 상한(`timeout` · `max-source-bytes` · 출력은 원본 크기까지)은 안전 장치라 모듈 기본으로 두고 설정으로 덮는다. 인코딩 값(`crf` · `preset` · `pixel-format`)만 취향이다.
 */
@ConfigurationProperties("skeleton.storage.image.ffmpeg")
data class FfmpegProperties(
    val enabled: Boolean = false,
    /** ffmpeg 실행 파일 - 이름(PATH 에서 찾는다)이나 절대 경로 */
    val path: String = "ffmpeg",
    /** ffmpeg 프로세스 하나의 시간 상한 - 넘으면 죽이고 실패(큐가 다시 한다). 큐의 `stale-lock-timeout` 보다 짧게 둔다 */
    val timeout: Duration = Duration.ofSeconds(60),
    /** 원본 바이트 상한 - 넘으면 읽지 않고 변환하지 않는다(사본이 쓰인다). storage-image 의 `max-source-bytes` 와 따로, 더 좁게 */
    val maxSourceBytes: Long = 20L * 1024 * 1024,
    /** libx264 `-crf` (0..51, 낮을수록 크고 고화질) */
    val crf: Int = 27,
    /** libx264 `-preset` */
    val preset: String = "veryfast",
    /** 출력 `-pix_fmt` - 브라우저 호환은 yuv420p */
    val pixelFormat: String = "yuv420p",
    /** 변환 작업의 시도 횟수 상한(큐의 `max-attempts` 대신) - 소진되면 DEAD 로 끝나고 사본이 계속 쓰인다 */
    val maxAttempts: Int = 3,
) {
    init {
        require(path.isNotBlank()) { "skeleton.storage.image.ffmpeg.path must not be blank" }
        require(!timeout.isZero && !timeout.isNegative) { "skeleton.storage.image.ffmpeg.timeout must be positive" }
        require(maxSourceBytes > 0) { "skeleton.storage.image.ffmpeg.max-source-bytes must be positive" }
        require(crf in 0..51) { "skeleton.storage.image.ffmpeg.crf must be 0..51" }
        require(SAFE_WORD.matches(preset)) { "skeleton.storage.image.ffmpeg.preset must be letters, digits, '_' or '-'" }
        require(SAFE_WORD.matches(pixelFormat)) { "skeleton.storage.image.ffmpeg.pixel-format must be letters, digits, '_' or '-'" }
        require(maxAttempts >= 1) { "skeleton.storage.image.ffmpeg.max-attempts must be at least 1" }
    }

    private companion object {
        val SAFE_WORD = Regex("[A-Za-z0-9_-]+")
    }
}
