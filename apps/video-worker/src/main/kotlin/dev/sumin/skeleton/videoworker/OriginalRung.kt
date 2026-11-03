package dev.sumin.skeleton.videoworker

import kotlin.math.ceil

/**
 * 마스터 목록(자동)에 원본 단을 끼우는 규칙. ffmpeg 가 쓴 마스터는 아랫단(H.264)뿐이고, 원본 단은 복사한 조각이라 ffmpeg 가 `CODECS` 를 안 써 준다 —
 * 일꾼이 프로브 · 서버가 기억한 코덱 문자열로 직접 써 넣는다. **짧은 변 1080 이하인 원본만** 자동에 넣는다: 4K 원본(35Mbps 안팎)을 ABR 이 빠른 망에서 골라
 * 대역(주인 전송 비용)과 디코드 부담이 폭주하지 않게 — 4K 는 화질 칩 「원본」으로 직접 고를 때만 받는다
 */
object OriginalRung {
    const val AUTO_MAX_SHORT_SIDE = 1080
    private const val AUDIO_CODEC = "mp4a.40.2"
    private val FOLDER = Regex("^o-[0-9a-f]{16}$")

    fun eligible(info: MediaInfo): Boolean = info.shortSide <= AUTO_MAX_SHORT_SIDE

    /** [origFolder] 는 `o-<해시>` — 마스터가 있는 `h-<빌드>/` 와 같은 `video/<영상ID>/` 아래라 상대 주소 `../o-<해시>/index.m3u8` */
    fun patch(master: String, origFolder: String, info: MediaInfo, codec: String?, origBytes: Long): String {
        require(FOLDER.matches(origFolder)) { "원본 폴더 이름이 아니다" }
        val seconds = maxOf(1.0, info.durationMs / 1000.0)
        val average = ceil(origBytes * 8 / seconds).toLong().coerceAtLeast(1)
        val peak = ceil(average * 1.2).toLong()
        val video = codec?.takeIf { Regex("^[A-Za-z0-9.]{3,40}$").matches(it) } ?: info.codecString
        val codecs = listOfNotNull(video, if (info.hasAudio) AUDIO_CODEC else null).joinToString(",")
        val attrs = buildList {
            add("BANDWIDTH=$peak")
            add("AVERAGE-BANDWIDTH=$average")
            add("RESOLUTION=${info.displayWidth}x${info.displayHeight}")
            if (codecs.isNotEmpty()) add("CODECS=\"$codecs\"")
        }.joinToString(",")
        return master.trimEnd() + "\n#EXT-X-STREAM-INF:$attrs\n../$origFolder/${HlsNames.PLAYLIST}\n"
    }
}
