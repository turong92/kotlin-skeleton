package dev.sumin.skeleton.storage.image.imgproxy

import dev.sumin.skeleton.storage.DetectedKind

/** 이미지 머리(앞 256KB)에서 폭 · 높이만 읽는다 - 디코드하지 않는다. 읽을 수 없으면 null. EXIF 회전은 반영하지 않는다(픽셀 수는 같다) */
internal object ImageHeaderSize {
    fun of(head: ByteArray, kind: DetectedKind): Pair<Int, Int>? = try {
        when (kind) {
            DetectedKind.PNG -> if (head.size >= 24) be32(head, 16) to be32(head, 20) else null
            DetectedKind.GIF -> if (head.size >= 10) le16(head, 6) to le16(head, 8) else null
            DetectedKind.JPEG -> jpeg(head)
            DetectedKind.WEBP -> webp(head)
            DetectedKind.AVIF, DetectedKind.HEIC, DetectedKind.HEIF -> isobmff(head)
            else -> null
        }?.takeIf { it.first > 0 && it.second > 0 }
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    /**
     * 움직일 수 있는 GIF · WebP 인가 - GIF 는 그림이 둘 이상이거나, **머리(256KB)에서 끝까지 확인하지 못했으면**(첫 장이 커서 두 번째 장을 못 만났다) 움직일 수 있는 것으로 본다.
     * 정지 GIF 라도 움직이는 경로(메타데이터를 걷어낸 사본)의 결과가 맞다. WebP 는 첫 마디 `VP8X` 의 움직임 비트로 정해진다
     */
    fun animated(head: ByteArray, kind: DetectedKind): Boolean = when (kind) {
        DetectedKind.GIF -> try { gifFrames(head).let { (frames, complete) -> frames > 1 || !complete } } catch (e: IndexOutOfBoundsException) { true }
        DetectedKind.WEBP -> try { head.size > 20 && String(head, 12, 4, Charsets.ISO_8859_1) == "VP8X" && (head[20].toInt() and 0x02) != 0 } catch (e: IndexOutOfBoundsException) { false }
        else -> false
    }

    /** (센 그림 수, 끝 표시 `;` 까지 읽었는가) - 두 번째 그림을 만나면 거기서 멈추고 완료로 친다 */
    private fun gifFrames(gif: ByteArray): Pair<Int, Boolean> {
        var i = 13 + if ((gif[10].toInt() and 0x80) != 0) 3 * (1 shl ((gif[10].toInt() and 0x07) + 1)) else 0
        var frames = 0
        fun skip(from: Int): Int {
            var p = from
            while (true) {
                val size = gif[p].toInt() and 0xFF
                p += 1 + size
                if (size == 0) return p
            }
        }
        while (frames < 2) {
            if (i >= gif.size) return frames to false
            when (gif[i].toInt() and 0xFF) {
                0x21 -> i = skip(i + 2)
                0x2C -> {
                    frames++
                    val lct = if ((gif[i + 9].toInt() and 0x80) != 0) 3 * (1 shl ((gif[i + 9].toInt() and 0x07) + 1)) else 0
                    i = skip(i + 10 + lct + 1)
                }
                0x3B -> return frames to true
                else -> return frames to true   // 알 수 없는 블록 - 변환기가 거절한다
            }
        }
        return frames to true
    }

    /** 투명한 픽셀이 있을 수 있나 - 머리에서 알 수 있는 만큼(모르면 false) */
    fun transparent(head: ByteArray, kind: DetectedKind): Boolean = try {
        val text by lazy { String(head, Charsets.ISO_8859_1) }
        when (kind) {
            DetectedKind.PNG -> head.size > 25 && (head[25].toInt() == 4 || head[25].toInt() == 6 || (head[25].toInt() == 3 && "tRNS" in text))
            DetectedKind.GIF -> (0 until head.size - 7).any { head[it] == 0x21.toByte() && head[it + 1] == 0xF9.toByte() && head[it + 2] == 4.toByte() && (head[it + 3].toInt() and 1) == 1 }
            DetectedKind.WEBP -> when (text.substring(12, 16)) {
                "VP8X" -> (head[20].toInt() and 0x10) != 0
                "VP8L" -> (((head[21].toInt() and 0xFF) or ((head[22].toInt() and 0xFF) shl 8) or ((head[23].toInt() and 0xFF) shl 16) or ((head[24].toInt() and 0xFF) shl 24)) ushr 28) and 1 == 1
                else -> false
            }
            DetectedKind.AVIF, DetectedKind.HEIC, DetectedKind.HEIF -> "auxiliary:alpha" in text
            else -> false
        }
    } catch (e: IndexOutOfBoundsException) {
        false
    }

    /** 변환본(WebP)의 크기 - imgproxy 는 크기를 알려 주지 않는다 */
    fun webp(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 30 || String(b, 0, 4, Charsets.ISO_8859_1) != "RIFF" || String(b, 8, 4, Charsets.ISO_8859_1) != "WEBP") return null
        return when (String(b, 12, 4, Charsets.ISO_8859_1)) {
            "VP8 " -> (le16(b, 26) and 0x3FFF) to (le16(b, 28) and 0x3FFF)
            "VP8L" -> {
                val bits = (b[21].toInt() and 0xFF) or ((b[22].toInt() and 0xFF) shl 8) or ((b[23].toInt() and 0xFF) shl 16) or ((b[24].toInt() and 0xFF) shl 24)
                ((bits and 0x3FFF) + 1) to (((bits shr 14) and 0x3FFF) + 1)
            }
            "VP8X" -> (le24(b, 24) + 1) to (le24(b, 27) + 1)
            else -> null
        }
    }

    private fun jpeg(b: ByteArray): Pair<Int, Int>? {
        var i = 2
        while (i + 9 < b.size) {
            if (b[i] != 0xFF.toByte()) return null
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == 0xFF) { i++; continue }
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) return be16(b, i + 7) to be16(b, i + 5)
            i += 2 + be16(b, i + 2)
        }
        return null
    }

    /** `ispe` 상자(이미지 공간 크기) 중 가장 큰 것 - 썸네일 상자가 앞에 올 수 있다 */
    private fun isobmff(b: ByteArray): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var i = 4
        while (i + 16 <= b.size) {
            if (b[i] == 'i'.code.toByte() && b[i + 1] == 's'.code.toByte() && b[i + 2] == 'p'.code.toByte() && b[i + 3] == 'e'.code.toByte()) {
                val size = be32(b, i + 8) to be32(b, i + 12)
                if (best == null || size.first.toLong() * size.second > best.first.toLong() * best.second) best = size
            }
            i++
        }
        return best
    }

    private fun be16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le24(b: ByteArray, o: Int) = le16(b, o) or ((b[o + 2].toInt() and 0xFF) shl 16)
    private fun be32(b: ByteArray, o: Int) = (be16(b, o) shl 16) or be16(b, o + 2)
}
