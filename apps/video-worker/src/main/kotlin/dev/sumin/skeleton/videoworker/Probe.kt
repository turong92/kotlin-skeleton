package dev.sumin.skeleton.videoworker

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import kotlin.math.abs

/** ffprobe 가 알려 준 영상 한 편의 모습 — 필요한 것만 */
data class MediaInfo(
    val container: String?,
    val videoCodec: String,
    val profile: String?,
    val pixFmt: String?,
    val level: Int?,
    val codecTag: String?,
    /** 코딩된 크기 — 회전 전 */
    val width: Int,
    val height: Int,
    /** display matrix 회전(도). 없으면 null */
    val rotation: Int?,
    val fps: Double?,
    val durationMs: Long,
    val bitrate: Long?,
    val audioCodec: String?,
    val videoStreams: Int,
    val audioStreams: Int,
    val otherStreams: Int,
    val hdr: Boolean,
    /** 표지 그림(attached picture) 스트림의 번호 — 없으면 null. 포스터로 쓴다 */
    val coverStream: Int? = null,
) {
    private val swapped: Boolean get() = rotation != null && abs(rotation) % 180 == 90

    /** 화면에 보이는 크기 — 회전을 적용한 */
    val displayWidth: Int get() = if (swapped) height else width
    val displayHeight: Int get() = if (swapped) width else height
    val shortSide: Int get() = minOf(displayWidth, displayHeight)
    val hasAudio: Boolean get() = audioCodec != null

    /** 브라우저가 `canPlayType` 에 쓰는 코덱 문자열(근사) — H.264 `avc1.PPCCLL` · HEVC `hvc1.<프로파일>.<호환>.L<레벨>.B0` */
    val codecString: String?
        get() = when (videoCodec) {
            "h264" -> "avc1." + "%02x%02x%02x".format(h264ProfileIdc(profile), 0, level ?: 0)
            "hevc" -> if (profile?.contains("10") == true) "hvc1.2.4.L${level ?: 0}.B0" else "hvc1.1.6.L${level ?: 0}.B0"
            else -> null
        }

    /** HEVC 는 `hev1` 이 아니라 `hvc1` 로 싸야 Safari 가 튼다 */
    val needsHvc1Tag: Boolean get() = videoCodec == "hevc"

    /**
     * 이미 호환 조건 — H.264 · High 이하 · 8bit · 긴 변 1920 이하 / 짧은 변 1080 이하 · 20Mbps 이하 · 오디오 없거나 AAC.
     * 이러면 호환판을 만들지 않고 원본판이 곧 호환판이다
     */
    val compatible: Boolean
        get() = videoCodec == "h264" && pixFmt == "yuv420p" && h264ProfileIdc(profile) <= 100 &&
            maxOf(displayWidth, displayHeight) <= 1920 && shortSide <= 1080 &&
            (bitrate ?: 0) <= 20_000_000 && (audioCodec == null || audioCodec == "aac")
}

private fun h264ProfileIdc(profile: String?): Int = when (profile?.lowercase()) {
    "baseline", "constrained baseline" -> 66
    "main" -> 77
    "extended" -> 88
    "high" -> 100
    "high 10", "high 10 intra" -> 110
    "high 4:2:2", "high 4:2:2 intra" -> 122
    "high 4:4:4 predictive", "high 4:4:4 intra" -> 244
    else -> 100
}

/** 일꾼이 영상을 거절하는 이유 — 사유 코드는 서버 표(`VideoFailCode`)에 있는 것 */
data class Rejection(val code: String, val detail: String)

object Probe {
    private val json = JsonMapper.builder().build()
    private val ALLOWED_CODECS = setOf("h264", "hevc")
    private val HDR_TRANSFERS = setOf("smpte2084", "arib-std-b67")

    const val MAX_LONG_SIDE = 8192
    const val MAX_PIXEL_RATE = 7680L * 4320 * 30
    const val MAX_DURATION_MS = 6L * 3600 * 1000

    fun parse(text: String): MediaInfo {
        val root = json.readTree(text)
        val streams = root["streams"]?.toList().orEmpty()
        val videos = streams.filter { it.text("codec_type") == "video" && it["disposition"]?.get("attached_pic")?.asInt(0) != 1 }
        val audios = streams.filter { it.text("codec_type") == "audio" }
        val v = videos.firstOrNull()
        val format = root["format"]
        val rotation = v?.get("side_data_list")?.toList()?.firstOrNull { it.has("rotation") }?.get("rotation")?.asInt()
        val durationSec = (format?.text("duration") ?: v?.text("duration"))?.toDoubleOrNull() ?: 0.0
        return MediaInfo(
            container = format?.text("format_name"),
            videoCodec = v?.text("codec_name").orEmpty(),
            profile = v?.text("profile"),
            pixFmt = v?.text("pix_fmt"),
            level = v?.get("level")?.asInt(),
            codecTag = v?.text("codec_tag_string"),
            width = v?.get("width")?.asInt(0) ?: 0,
            height = v?.get("height")?.asInt(0) ?: 0,
            rotation = rotation,
            fps = v?.let { rate(it.text("avg_frame_rate")) },
            durationMs = (durationSec * 1000).toLong(),
            bitrate = format?.text("bit_rate")?.toLongOrNull(),
            audioCodec = audios.firstOrNull()?.text("codec_name"),
            videoStreams = videos.size,
            audioStreams = audios.size,
            otherStreams = streams.size - videos.size - audios.size,
            hdr = v?.text("color_transfer") in HDR_TRANSFERS,
            coverStream = streams.firstOrNull { it.text("codec_type") == "video" && it["disposition"]?.get("attached_pic")?.asInt(0) == 1 }?.get("index")?.asInt(),
        )
    }

    /** 받을 수 없는 영상이면 이유, 받을 수 있으면 null */
    fun rejection(info: MediaInfo): Rejection? {
        val container = info.container.orEmpty()
        if (!container.split(',').contains("mp4") && !container.split(',').contains("mov")) {
            return Rejection(VideoFail.UNSUPPORTED_FORMAT, "container=$container")
        }
        if (info.videoStreams == 0) return Rejection(VideoFail.UNSUPPORTED_FORMAT, "영상 스트림 없음")
        if (info.videoCodec !in ALLOWED_CODECS) return Rejection(VideoFail.UNSUPPORTED_FORMAT, "codec=${info.videoCodec}")
        if (info.width <= 0 || info.height <= 0) return Rejection(VideoFail.CORRUPT, "크기를 못 읽었다")
        if (maxOf(info.width, info.height) > MAX_LONG_SIDE) return Rejection(VideoFail.LIMITS_EXCEEDED, "긴 변 ${maxOf(info.width, info.height)}")
        if (info.width.toLong() * info.height * (info.fps ?: 30.0) > MAX_PIXEL_RATE) return Rejection(VideoFail.LIMITS_EXCEEDED, "면적 × 프레임율")
        if (info.durationMs > MAX_DURATION_MS) return Rejection(VideoFail.LIMITS_EXCEEDED, "길이 ${info.durationMs}ms")
        if (info.durationMs <= 0) return Rejection(VideoFail.CORRUPT, "길이를 못 읽었다")
        return null
    }

    private fun rate(text: String?): Double? {
        val (n, d) = text?.split('/')?.takeIf { it.size == 2 }?.let { (it[0].toDoubleOrNull() ?: return null) to (it[1].toDoubleOrNull() ?: return null) } ?: return null
        return if (d == 0.0 || n == 0.0) null else n / d
    }

    private fun JsonNode.text(name: String): String? = get(name)?.takeIf { !it.isNull }?.asString()
}

/** 서버 `VideoFailCode` 의 복제 — 사유 이름이 계약이다 */
object VideoFail {
    const val UNSUPPORTED_FORMAT = "UNSUPPORTED_FORMAT"
    const val CORRUPT = "CORRUPT"
    const val LIMITS_EXCEEDED = "LIMITS_EXCEEDED"
    const val TOO_BIG_FOR_WORKER = "TOO_BIG_FOR_WORKER"
    const val PROCESSING_FAILED = "PROCESSING_FAILED"
}
