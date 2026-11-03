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
 *     ladder: true            # 화질 사다리(아랫단) 트랙을 만든다 — 끄면 NONE 으로 두고 켜질 때 청소가 줄 세운다
 *     verify-public: true     # 공개 영상은 일꾼이 올리기 전에 모든 프레임을 디코드해 본다(claim 응답의 verifyDecode)
 *     worker-token-sha256: "" # 로컬 전용 — 이 해시(SHA-256 16진수 64자)의 일꾼을 기동 때 등록한다. stage · prod 에서는 기동 실패
 *     cleanup-delay: 1h       # 원본 단 검증 뒤 이만큼 있다가 올린 원 파일을 지우고 중복 결과 폴더를 쓴다
 *     worker: { lease-duration: 10m, url-ttl: 6h }
 *     playback:
 *       secret: ""            # 비공개 영상 재생 출입증의 HMAC 비밀 — 켜면 32자 이상 필수(환경변수 <P>_VIDEO_PLAYBACK_SECRET, 선언의 secrets:). 무작위로 충분, 바꾸면 발급된 출입증이 모두 끊긴다
 *       ttl: 4h               # 재생 허가 한 번의 출입증 수명 — 조각 주소도 이 남은 수명으로 서명한다
 *     monitor: { worker-absent: 5m, compat-stale-after: 2h, failure-surge-count: 5, failure-window: 1h }   # 주인 경보 문턱
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
    val ladder: Boolean = true,
    val verifyPublic: Boolean = true,
    val workerTokenSha256: String = "",
    val cleanupDelay: Duration = Duration.ofHours(1),
    val worker: Worker = Worker(),
    val playback: Playback = Playback(),
    val monitor: Monitor = Monitor(),
    val http: Http = Http(),
) {
    data class Http(val enabled: Boolean = true)

    data class Worker(
        /** 한 번 집으면 이만큼 임대 — heartbeat 가 늘린다 */
        val leaseDuration: Duration = Duration.ofMinutes(10),
        /** 일꾼에게 주는 읽기 · 올리기 서명 주소의 수명 - 일꾼의 작업 한도(4h)보다 길어야 긴 영상의 입력 · 올리기가 도중에 만료되지 않는다 */
        val urlTtl: Duration = Duration.ofHours(6),
    )

    /** 비공개 영상 재생 — 출입증은 `v1.<영상 id>.<만료>.<mac>` 이고 경로에 든다 */
    data class Playback(
        val secret: String = "",
        val ttl: Duration = Duration.ofHours(4),
    ) {
        init {
            require(!ttl.isZero && !ttl.isNegative) { "skeleton.video.playback.ttl must be positive" }
        }

        override fun toString(): String = "Playback(secret=${if (secret.isEmpty()) "" else "****"}, ttl=$ttl)"
    }

    /** 주인 경보 문턱 */
    data class Monitor(
        /** remux 일꾼을 이만큼 못 봤고 대기가 있으면 `VIDEO_QUEUE_STALE` */
        val workerAbsent: Duration = Duration.ofMinutes(5),
        /** 호환 단이 이만큼 넘게 대기 중이면 `VIDEO_COMPAT_STALE` */
        val compatStaleAfter: Duration = Duration.ofHours(2),
        /** [failureWindow] 안에 실패(영상 · 호환 · 사다리)가 이만큼 이상이면 `VIDEO_FAILED_SURGE` */
        val failureSurgeCount: Int = 5,
        val failureWindow: Duration = Duration.ofHours(1),
    )

    init {
        require(root.isNotEmpty() && root.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "skeleton.video.prefix must be a plain relative key prefix (got '$prefix')" }
        require(partSize.toBytes() >= MIN_PART_BYTES) { "skeleton.video.part-size must be at least 5MB (an object store refuses smaller parts but the last)" }
        require(maxBytes.toBytes() > 0 && VideoLimits.partCount(maxBytes.toBytes(), partSize.toBytes()) <= VideoLimits.MAX_PARTS) {
            "skeleton.video.max-bytes needs at most ${VideoLimits.MAX_PARTS} parts of skeleton.video.part-size - raise part-size"
        }
        require(maxInFlight >= 1) { "skeleton.video.max-in-flight must be at least 1" }
        require(!staleUploadAge.isZero && !staleUploadAge.isNegative) { "skeleton.video.stale-upload-age must be positive" }
        require(workerTokenSha256.isBlank() || SHA256.matches(workerTokenSha256.trim())) { "skeleton.video.worker-token-sha256 must be a SHA-256 hash (64 hex characters), not the token itself" }
        require(!cleanupDelay.isNegative) { "skeleton.video.cleanup-delay must not be negative" }
        require(!worker.leaseDuration.isZero && !worker.leaseDuration.isNegative && !worker.urlTtl.isZero && !worker.urlTtl.isNegative) { "skeleton.video.worker durations must be positive" }
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
        val SHA256 = Regex("^[0-9a-fA-F]{64}$")
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
