package dev.sumin.skeleton.videoworker

import java.nio.file.Files
import kotlin.io.path.fileSize
import kotlin.math.ceil

/**
 * 로컬 모드의 코덱 문자열(RFC 6381)과 마스터 재생 목록(`master.m3u8`) — 웹 재생기가 「자동」에서 낮은 단으로 시작해 위로 올라가게 원본 판과 호환 판을 묶는다.
 * 서버 모드의 마스터(사다리)는 ffmpeg 가 쓰고 원본 단만 [OriginalRung] 이 끼우지만, 로컬 모드는 두 판 모두 이미 만들어진 폴더라 여기서 직접 쓴다
 */
internal object LocalMaster {
    const val FILE = "master.m3u8"

    /** [Probe] 의 H.264 프로파일 이름 표와 같은 이름들 — 서버 쪽은 모르면 High 로 치지만(`else -> 100`) 로컬 `codec` 은 지어내지 않고 null 이다 */
    private val H264_PROFILES = setOf(
        "baseline", "constrained baseline", "main", "extended", "high", "high 10", "high 10 intra", "high 4:2:2", "high 4:2:2 intra", "high 4:4:4 predictive", "high 4:4:4 intra",
    )

    /**
     * 판의 `codec` — H.264 `avc1.PPCCLL`(ffprobe profile · level) + AAC-LC `mp4a.40.2`.
     * 모르는 영상 코덱 · 읽지 못한 프로파일/레벨 · AAC-LC 가 아닌 소리(HE-AAC 는 40.5 · 40.29)는 **null** — 문자열을 지어내면 재생기가 `canPlayType` 을 잘못 판단한다
     */
    fun codec(info: MediaInfo): String? {
        val video = if (info.videoStreams == 0) null else {
            if (info.videoCodec == "h264" && info.profile?.lowercase() !in H264_PROFILES) return null
            info.codecString?.takeIf { (info.level ?: 0) > 0 } ?: return null
        }
        val audio = if (!info.hasAudio) null else (OriginalRung.AUDIO_CODEC.takeIf { info.audioCodec == "aac" && info.audioProfile == "LC" } ?: return null)
        return listOfNotNull(video, audio).joinToString(",").ifEmpty { null }
    }

    /**
     * (BANDWIDTH, AVERAGE-BANDWIDTH) 비트/초 — 뒤는 init 까지 합친 바이트 / 길이. 앞은 피크: 조각 하나씩 재면 짧은 마지막 조각(I-프레임 하나)이 몇 배로 부풀어서,
     * RFC 8216bis 대로 연속한 조각을 길이 합이 목표 길이(TARGETDURATION)의 절반 이상이 될 때까지 묶어 잰 비트레이트 중 최댓값. 둘 다 올림
     */
    fun bandwidth(tier: BuiltTier): Pair<Long, Long> {
        val sizes = tier.files.associate { it.fileName.toString() to it.fileSize() }
        val playlist = Files.readString(tier.files.first { it.fileName.toString() == HlsNames.PLAYLIST })
        val segments = mutableListOf<Pair<Double, Long>>() // (초, 바이트)
        var target = 0.0
        var pending: Double? = null
        for (line in playlist.lineSequence().map { it.trim() }) {
            if (line.startsWith("#EXT-X-TARGETDURATION:")) target = line.substringAfter(':').toDoubleOrNull() ?: 0.0
            else if (line.startsWith("#EXTINF:")) pending = line.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull()
            else if (line.isNotEmpty() && !line.startsWith("#") && pending != null && pending > 0) {
                segments += pending to (sizes[line] ?: 0L)
                pending = null
            }
        }
        val seconds = segments.sumOf { it.first }
        val minWindow = (if (target > 0) target else segments.maxOfOrNull { it.first } ?: 0.0) / 2
        var peak = 0L
        for (start in segments.indices) {
            var sec = 0.0
            var bytes = 0L
            for (i in start until segments.size) {
                sec += segments[i].first
                bytes += segments[i].second
                if (sec >= minWindow) { peak = maxOf(peak, ceil(bytes * 8 / sec).toLong()); break }
            }
        }
        val total = sizes.filterKeys { it != HlsNames.PLAYLIST }.values.sum()
        val average = ceil(total * 8 / maxOf(seconds, tier.info.durationMs / 1000.0, 0.001)).toLong().coerceAtLeast(1)
        return maxOf(peak, average) to average
    }

    /**
     * 마스터 본문 — 첫 줄 변형이 재생기의 시작 단이다. 아래 중 하나라도 어긋나면 **null**(마스터를 만들지 않는다):
     * - 한 판이라도 [codec] 을 모른다: `CODECS` 없는 변형을 섞으면 재생기가 못 트는 단을 골라 멈출 수 있다.
     * - [original] 의 짧은 변이 1080 을 넘는다: 서버 마스터와 같은 규칙([OriginalRung.eligible]) — 4K 원본을 ABR 이 빠른 망에서 골라 대역 · 디코드 부담이 폭주하지 않게.
     * - 영상 코덱이 다르다(예: HEVC 원본 + H.264 호환): 재생기(hls.js)는 코덱 묶음 하나만 골라 ABR 이 안 될 수 있다 — 실제로 확인하지 않고 안전하게 뺀다.
     * - [compat] 가 더 작고(화면 면적) 더 가볍지(BANDWIDTH) 않다: 올라갈 이유가 없는 단을 섞으면 ABR 이 더 나쁜 재인코딩본을 고른다.
     */
    fun text(original: BuiltTier, compat: BuiltTier): String? {
        val originalCodecs = codec(original.info) ?: return null
        val compatCodecs = codec(compat.info) ?: return null
        if (!OriginalRung.eligible(original.info) || original.info.videoCodec != compat.info.videoCodec) return null
        val (originalPeak, originalAverage) = bandwidth(original)
        val (compatPeak, compatAverage) = bandwidth(compat)
        if (area(compat.info) >= area(original.info) || compatPeak >= originalPeak) return null
        return buildString {
            append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n")
            for ((tier, codecs, bw) in listOf(Triple(compat, compatCodecs, compatPeak to compatAverage), Triple(original, originalCodecs, originalPeak to originalAverage))) {
                append("#EXT-X-STREAM-INF:BANDWIDTH=${bw.first},AVERAGE-BANDWIDTH=${bw.second},RESOLUTION=${tier.info.displayWidth}x${tier.info.displayHeight},CODECS=\"$codecs\"\n")
                append("${tier.folder}/${HlsNames.PLAYLIST}\n")
            }
        }
    }

    private fun area(info: MediaInfo): Long = info.displayWidth.toLong() * info.displayHeight
}
