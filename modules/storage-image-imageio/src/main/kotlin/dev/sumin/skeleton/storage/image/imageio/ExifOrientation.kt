package dev.sumin.skeleton.storage.image.imageio

/** JPEG 머리의 APP1 Exif 에서 방향(0x0112, 1..8)을 읽는다 - 없거나 읽을 수 없으면 1(그대로) */
internal object ExifOrientation {
    fun of(jpeg: ByteArray): Int {
        var i = 2
        while (i + 4 <= jpeg.size && jpeg[i] == 0xFF.toByte()) {
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) break   // 이미지 데이터 시작 - 마디 목록이 끝났다
            val length = u16(jpeg, i + 2, big = true)
            if (marker == 0xE1 && length >= 14 && jpeg.hasAscii(i + 4, "Exif") && jpeg[i + 8] == 0.toByte()) {
                return tiffOrientation(jpeg, i + 10, minOf(jpeg.size, i + 2 + length))
            }
            i += 2 + length
        }
        return 1
    }

    private fun tiffOrientation(b: ByteArray, start: Int, end: Int): Int {
        if (end - start < 8) return 1
        val big = when {
            b.hasAscii(start, "MM") -> true
            b.hasAscii(start, "II") -> false
            else -> return 1
        }
        val ifd = start + u32(b, start + 4, big)
        if (ifd < start || ifd + 2 > end) return 1
        val entries = u16(b, ifd, big)
        for (k in 0 until entries) {
            val entry = ifd + 2 + 12 * k
            if (entry + 12 > end) return 1
            if (u16(b, entry, big) == 0x0112) return u16(b, entry + 8, big).takeIf { it in 1..8 } ?: 1
        }
        return 1
    }

    private fun u16(b: ByteArray, at: Int, big: Boolean): Int {
        val x = b[at].toInt() and 0xFF
        val y = b[at + 1].toInt() and 0xFF
        return if (big) (x shl 8) or y else (y shl 8) or x
    }

    private fun u32(b: ByteArray, at: Int, big: Boolean): Int =
        if (big) (u16(b, at, true) shl 16) or u16(b, at + 2, true) else (u16(b, at + 2, false) shl 16) or u16(b, at, false)

    private fun ByteArray.hasAscii(at: Int, text: String) = size >= at + text.length && text.indices.all { this[at + it] == text[it].code.toByte() }
}

/** GIF 머리를 블록 단위로 훑어 이미지 수를 센다(디코드하지 않는다) - [limit] 장을 세면 멈춘다. 머리(256KB)에서 끊기면 센 만큼만 */
internal object GifScan {
    fun frames(gif: ByteArray, limit: Int): Int {
        if (gif.size < 13) return 0
        var i = 13
        if ((gif[10].toInt() and 0x80) != 0) i += 3 * (1 shl ((gif[10].toInt() and 0x07) + 1))
        var frames = 0
        while (i < gif.size && frames < limit) {
            when (gif[i].toInt() and 0xFF) {
                0x21 -> i = skipSubBlocks(gif, i + 2)
                0x2C -> {
                    frames++
                    if (i + 10 > gif.size) return frames
                    if ((gif[i + 9].toInt() and 0x80) != 0) i += 3 * (1 shl ((gif[i + 9].toInt() and 0x07) + 1))
                    i = skipSubBlocks(gif, i + 11)
                }
                else -> return frames   // 0x3B 끝 표시이거나 알 수 없는 블록
            }
        }
        return frames
    }

    private fun skipSubBlocks(gif: ByteArray, from: Int): Int {
        var i = from
        while (i < gif.size) {
            val size = gif[i].toInt() and 0xFF
            i += 1 + size
            if (size == 0) break
        }
        return i
    }
}
