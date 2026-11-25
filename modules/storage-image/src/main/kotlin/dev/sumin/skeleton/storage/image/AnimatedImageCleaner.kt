package dev.sumin.skeleton.storage.image

import java.io.ByteArrayOutputStream

/**
 * 움직이는 GIF · WebP 의 **메타데이터를 걷어낸 사본**을 만든다 - 움직임(프레임)은 다시 인코딩하지 않고 그대로 둔다. 남에게 주는 `display` 가 원본 대신 이 사본이다.
 * - WebP: RIFF 의 `EXIF` · `XMP ` 마디를 빼고 `VP8X` 의 해당 비트(0x08 · 0x04)를 끈 뒤 RIFF 길이를 다시 쓴다. 나머지 마디(프레임 · `ICCP` · `ANIM` …)는 그대로.
 * - GIF: 주석 · 글자 · 모르는 응용 확장(`XMP DataXMP` 등)을 뺀다. 그림 · 제어 확장 · 반복 횟수(`NETSCAPE2.0` · `ANIMEXTS1.0`)는 남긴다.
 * 구조가 깨졌으면 [ImageRejectedException] (영구 실패).
 */
object AnimatedImageCleaner {
    fun clean(contentType: String, bytes: ByteArray): ByteArray = try {
        when (contentType) {
            "image/webp" -> webp(bytes)
            "image/gif" -> gif(bytes)
            else -> throw ImageRejectedException(ImageReasons.UNSUPPORTED_FORMAT, "Not an animated format: $contentType")
        }
    } catch (e: IndexOutOfBoundsException) {
        throw ImageRejectedException(ImageReasons.NOT_AN_IMAGE, "The animated image is truncated or malformed", e)
    }

    private fun webp(b: ByteArray): ByteArray {
        if (b.size < 12 || String(b, 0, 4, Charsets.ISO_8859_1) != "RIFF" || String(b, 8, 4, Charsets.ISO_8859_1) != "WEBP") throw ImageRejectedException(ImageReasons.NOT_AN_IMAGE, "Not a WebP file")
        val out = ByteArrayOutputStream(b.size)
        out.write(b, 0, 12)
        var i = 12
        while (i + 8 <= b.size) {
            val id = String(b, i, 4, Charsets.ISO_8859_1)
            val size = le32(b, i + 4)
            val end = i.toLong() + 8 + size + (size and 1)
            if (size < 0 || end > b.size + (size and 1)) throw ImageRejectedException(ImageReasons.NOT_AN_IMAGE, "A WebP chunk runs past the end of the file")
            val stop = minOf(end, b.size.toLong()).toInt()
            when (id) {
                "EXIF", "XMP " -> Unit
                "VP8X" -> {
                    out.write(b, i, 8)
                    out.write(b[i + 8].toInt() and (0x08 or 0x04).inv())
                    out.write(b, i + 9, stop - (i + 9))
                }
                else -> out.write(b, i, stop - i)
            }
            i = stop
        }
        val result = out.toByteArray()
        val riffSize = result.size - 8
        result[4] = riffSize.toByte(); result[5] = (riffSize shr 8).toByte(); result[6] = (riffSize shr 16).toByte(); result[7] = (riffSize shr 24).toByte()
        return result
    }

    private fun le32(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun gif(b: ByteArray): ByteArray {
        if (b.size < 13 || String(b, 0, 3, Charsets.ISO_8859_1) != "GIF") throw ImageRejectedException(ImageReasons.NOT_AN_IMAGE, "Not a GIF file")
        val out = ByteArrayOutputStream(b.size)
        var i = 13 + if ((b[10].toInt() and 0x80) != 0) 3 * (1 shl ((b[10].toInt() and 0x07) + 1)) else 0
        out.write(b, 0, i)
        while (true) {
            when (b[i].toInt() and 0xFF) {
                0x3B -> { out.write(0x3B); return out.toByteArray() }
                0x21 -> {
                    val label = b[i + 1].toInt() and 0xFF
                    val end = skipSubBlocks(b, i + 2)
                    val keep = when (label) {
                        0xF9 -> true
                        0xFF -> b[i + 2].toInt() == 11 && String(b, i + 3, 11, Charsets.ISO_8859_1).let { it == "NETSCAPE2.0" || it == "ANIMEXTS1.0" }
                        else -> false   // 주석(0xFE) · 글자(0x01) · 모르는 확장
                    }
                    if (keep) out.write(b, i, end - i)
                    i = end
                }
                0x2C -> {
                    var p = i + 10
                    if ((b[i + 9].toInt() and 0x80) != 0) p += 3 * (1 shl ((b[i + 9].toInt() and 0x07) + 1))
                    val end = skipSubBlocks(b, p + 1)
                    out.write(b, i, end - i)
                    i = end
                }
                else -> throw ImageRejectedException(ImageReasons.NOT_AN_IMAGE, "Unexpected GIF block")
            }
        }
    }

    /** [from] 부터 크기 0 인 블록이 나올 때까지 건너뛰고 그 다음 위치를 돌려준다 */
    private fun skipSubBlocks(b: ByteArray, from: Int): Int {
        var i = from
        while (true) {
            val size = b[i].toInt() and 0xFF
            i += 1 + size
            if (size == 0) return i
        }
    }
}
