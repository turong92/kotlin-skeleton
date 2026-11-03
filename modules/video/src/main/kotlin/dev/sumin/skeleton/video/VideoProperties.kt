package dev.sumin.skeleton.video

import dev.sumin.skeleton.storage.StorageWebProperties
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize

/**
 * ```yaml
 * skeleton:
 *   video:
 *     enabled: false          # 모듈 기본은 꺼짐 — 앱 yml 이 켠다. 켜면 VideoStore · VideoWorkerStore(video-jdbc) 와 PresignedStorage 가 있어야 기동한다
 *     prefix: videos          # 영상이 쓰는 저장소 키의 맨 앞 구간 — 업로드(key-prefix) · 공개(public.prefix) 구간과 겹치면 기동 실패
 *     max-bytes: 4GB          # 올리는 파일 하나의 상한
 *     part-size: 16MB         # 멀티파트 조각 크기(마지막 조각 말고는 5MB 이상)
 *     max-in-flight: 2        # 한 계정이 동시에 진행하는 영상 수(올리는 중 ~ 변환 중)
 *     stale-upload-age: 1h    # 이 시간 움직임이 없는 올리기는 청소가 중단한다
 *     http:
 *       enabled: true         # false 면 /api/v1/videos 를 등록하지 않는다
 * ```
 */
@ConfigurationProperties("skeleton.video")
data class VideoProperties(
    val enabled: Boolean = false,
    val prefix: String = "videos",
    val maxBytes: DataSize = DataSize.ofGigabytes(4),
    val partSize: DataSize = DataSize.ofMegabytes(16),
    val maxInFlight: Int = 2,
    val staleUploadAge: Duration = Duration.ofHours(1),
    val http: Http = Http(),
) {
    data class Http(val enabled: Boolean = true)

    init {
        require(root.isNotEmpty() && root.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "skeleton.video.prefix must be a plain relative key prefix (got '$prefix')" }
        require(partSize.toBytes() >= MIN_PART_BYTES) { "skeleton.video.part-size must be at least 5MB (an object store refuses smaller parts but the last)" }
        require(maxBytes.toBytes() > 0 && VideoLimits.partCount(maxBytes.toBytes(), partSize.toBytes()) <= VideoLimits.MAX_PARTS) {
            "skeleton.video.max-bytes needs at most ${VideoLimits.MAX_PARTS} parts of skeleton.video.part-size - raise part-size"
        }
        require(maxInFlight >= 1) { "skeleton.video.max-in-flight must be at least 1" }
        require(!staleUploadAge.isZero && !staleUploadAge.isNegative) { "skeleton.video.stale-upload-age must be positive" }
    }

    /** `/` 를 뗀 영상 키 구간 */
    val root: String get() = prefix.trim().trim('/')

    /** 영상 폴더가 남의 업로드로 읽히거나 공개 영역 정리가 영상을 지우지 않도록 두 구간과 겹치면 기동 실패 */
    fun requireDisjointFrom(web: StorageWebProperties) {
        fun overlaps(other: String) = root == other || root.startsWith("$other/") || other.startsWith("$root/")
        val up = web.keyPrefix.trim('/')
        require(!overlaps(up)) { "skeleton.video.prefix ('$root') must not overlap skeleton.storage.web.key-prefix ('$up') - videos are not plain uploads" }
        if (web.`public`.enabled) {
            val pub = web.`public`.prefix.trim('/')
            require(!overlaps(pub)) { "skeleton.video.prefix ('$root') must not overlap skeleton.storage.web.public.prefix ('$pub') - the public area is swept as a whole" }
        }
    }

    private companion object {
        const val MIN_PART_BYTES = 5L * 1024 * 1024
    }
}

/** 조각 나누기 — 순수 계산 */
object VideoLimits {
    /** S3 멀티파트 한 번의 조각 수 상한 */
    const val MAX_PARTS = 10_000

    private val CONTENT_TYPES = setOf("video/mp4", "video/quicktime")

    fun partCount(bytes: Long, partSize: Long): Int {
        require(bytes > 0 && partSize > 0) { "크기는 양수" }
        val count = (bytes + partSize - 1) / partSize
        require(count <= MAX_PARTS) { "조각이 $MAX_PARTS 개를 넘는다 — 조각 크기를 키운다" }
        return count.toInt()
    }

    /** 올릴 수 있는 형식 — 진짜 판정은 일꾼의 디코드다(헤더 · 확장자를 믿지 않는다) */
    fun allowedContentType(contentType: String): Boolean = contentType.trim().lowercase() in CONTENT_TYPES
}
