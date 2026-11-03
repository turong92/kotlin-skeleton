package dev.sumin.skeleton.videoworker

/**
 * 호환판 · 사다리 결과가 원본판과 같은 영상인지 — 올리기 **전에** 본다(어긋나면 공개하지 않고 실패로 알려 다시 줄 선다). 순수 함수.
 * 길이는 원본 대비 2%(최소 0.5초) 안, 소리가 있던 영상은 소리 트랙 하나, 영상 트랙은 하나
 */
object ResultCheck {
    /** 사다리 조각 길이(초) — [LadderPlan] 의 `-hls_time` */
    const val SEGMENT_SECONDS = 6

    fun durationTolerance(sourceMs: Long): Long = maxOf(500L, sourceMs / 50)

    private fun durationOff(source: MediaInfo, made: MediaInfo): Boolean =
        kotlin.math.abs(made.durationMs - source.durationMs) > durationTolerance(source.durationMs)

    private fun tracks(source: MediaInfo, made: MediaInfo): String? {
        if (made.videoStreams != 1) return "영상 트랙이 ${made.videoStreams}개다"
        if (source.hasAudio && made.audioStreams != 1) return "소리 트랙이 ${made.audioStreams}개다(원본엔 있다)"
        if (!source.hasAudio && made.audioStreams != 0) return "원본에 없는 소리 트랙이 생겼다"
        return null
    }

    /** 호환판(H.264 · 짧은 변 720 이하) 검사. 어긋나면 이유, 맞으면 null */
    fun compat(source: MediaInfo, made: MediaInfo): String? {
        tracks(source, made)?.let { return "호환판: $it" }
        if (made.videoCodec != "h264") return "호환판 코덱이 ${made.videoCodec} 이다"
        if (made.shortSide > 720) return "호환판 짧은 변이 ${made.shortSide} 이다"
        if (durationOff(source, made)) return "호환판 길이가 다르다(${made.durationMs}ms ≠ 원본 ${source.durationMs}ms)"
        return null
    }

    /** 사다리 한 판(변형 목록을 ffprobe 로 읽은 결과) 검사 */
    fun ladderRung(source: MediaInfo, rung: Rung, made: MediaInfo): String? {
        tracks(source, made)?.let { return "사다리 ${rung.height}: $it" }
        if (made.shortSide != rung.height) return "사다리 ${rung.height}: 짧은 변이 ${made.shortSide} 이다"
        if (durationOff(source, made)) return "사다리 ${rung.height}: 길이가 다르다(${made.durationMs}ms ≠ 원본 ${source.durationMs}ms)"
        return null
    }

    /** 변형 목록 글: 끝 표시 · 조각 수(원본 길이 ÷ 6초 올림, ±1) · 가리키는 파일이 모두 있는지 */
    fun ladderPlaylist(text: String, sourceMs: Long, files: Set<String>): String? {
        if (!text.contains("#EXT-X-ENDLIST")) return "목록에 끝 표시가 없다"
        val segments = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val expected = ((sourceMs + SEGMENT_SECONDS * 1000 - 1) / (SEGMENT_SECONDS * 1000)).toInt()
        if (kotlin.math.abs(segments.size - expected) > 1) return "조각이 ${segments.size}개다(예상 $expected)"
        segments.firstOrNull { it !in files }?.let { return "조각 파일이 없다: $it" }
        Regex("URI=\"([^\"]+)\"").find(text)?.groupValues?.get(1)?.takeIf { it !in files }?.let { return "init 파일이 없다: $it" }
        return null
    }

    /**
     * 원본 단 · 호환 단 변형 목록: 끝 표시 · 조각 하나 이상 · 가리키는 파일이 모두 있는지 · 조각 길이 합이 원본과 2%(최소 0.5초) 안.
     * 원본 단은 복사라 조각 길이 = 키프레임 간격이다 — 개수는 안 본다
     */
    fun hlsPlaylist(text: String, sourceMs: Long, files: Set<String>): String? {
        if (!text.contains("#EXT-X-ENDLIST")) return "목록에 끝 표시가 없다"
        val segments = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (segments.isEmpty()) return "조각이 없다"
        segments.firstOrNull { it !in files }?.let { return "조각 파일이 없다: $it" }
        Regex("URI=\"([^\"]+)\"").find(text)?.groupValues?.get(1)?.takeIf { it !in files }?.let { return "init 파일이 없다: $it" }
        val total = Regex("#EXTINF:([0-9.]+)").findAll(text).sumOf { it.groupValues[1].toDouble() }
        if (kotlin.math.abs(total * 1000 - sourceMs) > durationTolerance(sourceMs)) return "조각 길이 합이 다르다(${(total * 1000).toLong()}ms ≠ 원본 ${sourceMs}ms)"
        return null
    }

    /** 가장 긴 조각(초) — 복사한 원본 단은 키프레임 간격이 정한다. 상한은 없고(설계 3.4) 로그에만 남긴다 */
    fun longestSegmentSeconds(text: String): Double = Regex("#EXTINF:([0-9.]+)").findAll(text).maxOfOrNull { it.groupValues[1].toDouble() } ?: 0.0

    /** 마스터 목록: 판 수만큼 STREAM-INF */
    fun ladderMaster(text: String, rungs: Int): String? {
        val n = Regex("#EXT-X-STREAM-INF").findAll(text).count()
        return if (n == rungs) null else "마스터 목록의 판이 ${n}개다(예상 $rungs)"
    }
}
