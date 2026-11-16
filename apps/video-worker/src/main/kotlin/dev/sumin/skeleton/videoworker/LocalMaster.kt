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

    /**
     * 판의 `codec` — H.264 `avc1.PPCCLL`(ffprobe profile · level) + AAC `mp4a.40.2`. 일꾼이 만드는 소리는 항상 AAC 다.
     * 모르는 영상 코덱 · 레벨을 못 읽은 영상 · AAC 가 아닌 소리는 **null** — 문자열을 지어내면 재생기가 `canPlayType` 을 잘못 판단한다
     */
    fun codec(info: MediaInfo): String? {
        val video = if (info.videoStreams == 0) null else (info.codecString?.takeIf { (info.level ?: 0) > 0 } ?: return null)
        val audio = if (!info.hasAudio) null else (OriginalRung.AUDIO_CODEC.takeIf { info.audioCodec == "aac" } ?: return null)
        return listOfNotNull(video, audio).joinToString(",").ifEmpty { null }
    }

    /** (BANDWIDTH, AVERAGE-BANDWIDTH) 비트/초 — 앞은 가장 큰 조각의 비트레이트(최댓값), 뒤는 init 까지 합친 바이트 / 길이. 둘 다 올림 */
    fun bandwidth(tier: BuiltTier): Pair<Long, Long> {
        val sizes = tier.files.associate { it.fileName.toString() to it.fileSize() }
        val playlist = Files.readString(tier.files.first { it.fileName.toString() == HlsNames.PLAYLIST })
        var peak = 0L
        var seconds = 0.0
        var pending: Double? = null
        for (line in playlist.lineSequence().map { it.trim() }) {
            if (line.startsWith("#EXTINF:")) pending = line.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull()
            else if (line.isNotEmpty() && !line.startsWith("#") && pending != null && pending > 0) {
                peak = maxOf(peak, ceil((sizes[line] ?: 0L) * 8 / pending).toLong())
                seconds += pending
                pending = null
            }
        }
        val total = sizes.filterKeys { it != HlsNames.PLAYLIST }.values.sum()
        val average = ceil(total * 8 / maxOf(seconds, tier.info.durationMs / 1000.0, 0.001)).toLong().coerceAtLeast(1)
        return maxOf(peak, average) to average
    }

    /**
     * [tiers](영상 판들 — 폴더 이름이 곧 출력 폴더 기준 상대 경로)을 대역폭이 낮은 순으로 묶은 마스터 본문. 첫 줄 변형이 재생기의 시작 단이 된다.
     * 한 판이라도 [codec] 을 모르면 **null** — `CODECS` 없는 변형을 섞어 쓰면 재생기가 못 트는 단을 골라 멈출 수 있어, 마스터를 아예 만들지 않는다
     */
    fun text(tiers: List<BuiltTier>): String? {
        val variants = tiers.map { tier ->
            val codecs = codec(tier.info) ?: return null
            val (peak, average) = bandwidth(tier)
            Triple(tier, peak, "BANDWIDTH=$peak,AVERAGE-BANDWIDTH=$average,RESOLUTION=${tier.info.displayWidth}x${tier.info.displayHeight},CODECS=\"$codecs\"")
        }.sortedWith(compareBy({ it.second }, { it.first.info.displayWidth.toLong() * it.first.info.displayHeight }))
        return buildString {
            append("#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-INDEPENDENT-SEGMENTS\n")
            for ((tier, _, attrs) in variants) append("#EXT-X-STREAM-INF:$attrs\n${tier.folder}/${HlsNames.PLAYLIST}\n")
        }
    }
}
