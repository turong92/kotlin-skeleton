package dev.sumin.skeleton.storage.image

/**
 * `webp-passthrough` 의 내용 조건 - 멈춘 WebP 가 **그림 마디만** 가졌는지 본다(`VP8X` · `VP8 ` · `VP8L` · `ALPH` · `ICCP`).
 * EXIF · XMP · 모르는 마디 · RIFF 길이 뒤의 꼬리가 하나라도 있으면 통과하지 않는다 - 그런 파일은 지금처럼 다시 인코딩한다(거절이 아니다).
 * 마디는 이름마다 한 번, `ICCP` 는 [MAX_ICC_BYTES] 까지, 파일 전체는 [maxBytes] 까지 - 그림에 비해 큰 파일(덧붙인 데이터)은 다시 인코딩한다.
 * 그래서 통과한 표시본은 원본 바이트 그대로이고, 남에게 가는 것은 그림 데이터와 색 프로파일뿐이다. EXIF 가 없으니 방향 정보도 없다.
 */
object StillWebpPassthrough {
    private val IMAGE_CHUNKS = setOf("VP8X", "VP8 ", "VP8L", "ALPH", "ICCP")
    const val MAX_ICC_BYTES = 1L shl 20

    /** 그림 크기에 비례한 파일 상한 - 무손실도 픽셀당 4바이트를 넘지 않는다 */
    fun maxBytes(width: Int, height: Int): Long = width.toLong() * height * 4 + 65_536

    fun accepts(b: ByteArray): Boolean {
        if (b.size < 20 || String(b, 0, 4, Charsets.ISO_8859_1) != "RIFF" || String(b, 8, 4, Charsets.ISO_8859_1) != "WEBP") return false
        val end = 8L + le32(b, 4)
        if (end != b.size.toLong()) return false
        var i = 12L
        var image = false
        val seen = HashSet<String>()
        while (i < end) {
            if (i + 8 > end) return false
            val at = i.toInt()
            val id = String(b, at, 4, Charsets.ISO_8859_1)
            if (id !in IMAGE_CHUNKS || !seen.add(id)) return false
            if (id == "VP8 " || id == "VP8L") image = true
            val size = le32(b, at + 4)
            if (id == "ICCP" && size > MAX_ICC_BYTES) return false
            i += 8 + size + (size and 1L)
        }
        return i == end && image
    }

    private fun le32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)
}
