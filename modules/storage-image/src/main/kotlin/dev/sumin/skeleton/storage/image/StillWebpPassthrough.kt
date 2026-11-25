package dev.sumin.skeleton.storage.image

/**
 * `webp-passthrough` 의 내용 조건 - 멈춘 WebP 가 **그림 마디만** 가졌는지 본다(`VP8X` · `VP8 ` · `VP8L` · `ALPH` · `ICCP`).
 * EXIF · XMP · 모르는 마디 · RIFF 길이 뒤의 꼬리가 하나라도 있으면 통과하지 않는다 - 그런 파일은 지금처럼 다시 인코딩한다(거절이 아니다).
 * 그래서 통과한 표시본은 원본 바이트 그대로이고, 남에게 가는 것은 그림 데이터와 색 프로파일뿐이다. EXIF 가 없으니 방향 정보도 없다.
 */
object StillWebpPassthrough {
    private val IMAGE_CHUNKS = setOf("VP8X", "VP8 ", "VP8L", "ALPH", "ICCP")

    fun accepts(b: ByteArray): Boolean {
        if (b.size < 20 || String(b, 0, 4, Charsets.ISO_8859_1) != "RIFF" || String(b, 8, 4, Charsets.ISO_8859_1) != "WEBP") return false
        val end = 8L + le32(b, 4)
        if (end != b.size.toLong()) return false
        var i = 12L
        var image = false
        while (i < end) {
            if (i + 8 > end) return false
            val at = i.toInt()
            val id = String(b, at, 4, Charsets.ISO_8859_1)
            if (id !in IMAGE_CHUNKS) return false
            if (id == "VP8 " || id == "VP8L") image = true
            val size = le32(b, at + 4)
            i += 8 + size + (size and 1L)
        }
        return i == end && image
    }

    private fun le32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)
}
