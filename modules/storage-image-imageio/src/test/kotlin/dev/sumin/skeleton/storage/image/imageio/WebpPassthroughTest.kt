package dev.sumin.skeleton.storage.image.imageio

import dev.sumin.skeleton.common.upload.UploadImageState
import dev.sumin.skeleton.common.upload.UploadImages
import dev.sumin.skeleton.storage.image.ImageProperties
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `webp-passthrough` - 이미 표시본 상자 안의 멈춘 WebP 는 표시본을 다시 인코딩하지 않고 EXIF · XMP 만 걷어낸 사본으로 쓴다 */
class WebpPassthroughTest {
    private val small = resource("still-120x80.webp")
    private val big = resource("still-2000x1000.webp")
    private val on = ImageProperties(enabled = true, webpPassthrough = true)

    @Test
    fun `a still WebP inside the display box becomes the display as is, minus its EXIF - the thumb is still made`() {
        val h = Harness(on)
        val withExif = addExif(small)

        val row = h.run("uploads/7/w/x.webp", withExif, "image/webp")

        assertEquals(UploadImageState.READY, row.state)
        val display = row.variants.getValue(UploadImages.DISPLAY)
        assertEquals("webp", display.ext)
        assertEquals(120 to 80, display.width to display.height)
        assertTrue(small.contentEquals(stripVp8x(h.variantBytes(row, UploadImages.DISPLAY))), "그림 바이트는 그대로다")
        assertFalse(String(h.variantBytes(row, UploadImages.DISPLAY), Charsets.ISO_8859_1).contains("EXIF"))
        assertEquals("jpg", row.variants.getValue(UploadImages.THUMB).ext)
    }

    @Test
    fun `off by default, and a WebP larger than the display box is still re-encoded`() {
        assertEquals("jpg", Harness().run("uploads/7/w/x.webp", small, "image/webp").variants.getValue(UploadImages.DISPLAY).ext)

        val row = Harness(on).run("uploads/7/w/b.webp", big, "image/webp")
        val display = row.variants.getValue(UploadImages.DISPLAY)
        assertEquals("jpg", display.ext)
        assertEquals(1600 to 800, display.width to display.height)
    }

    @Test
    fun `a broken WebP is rejected even when it would pass through`() {
        val row = Harness(on).run("uploads/7/w/x.webp", small.copyOf(200), "image/webp")

        assertEquals(UploadImageState.REJECTED, row.state)
    }

    private fun resource(name: String): ByteArray = javaClass.getResourceAsStream("/webp/$name")!!.use { it.readAllBytes() }

    /** 단순 형식(VP8) WebP 를 VP8X(EXIF 표시) + 그림 + EXIF 마디로 감싼다 */
    private fun addExif(simple: ByteArray): ByteArray {
        val image = simple.copyOfRange(12, simple.size)
        val vp8x = ByteArray(10).also { it[0] = 0x08; le24(it, 4, 120 - 1); le24(it, 7, 80 - 1) }
        val body = ByteArrayOutputStream().apply {
            write("WEBP".toByteArray()); chunk("VP8X", vp8x); write(image); chunk("EXIF", "Exif\u0000\u0000GPS-SECRET".toByteArray())
        }.toByteArray()
        return ByteArrayOutputStream().apply { write("RIFF".toByteArray()); le32(body.size); write(body) }.toByteArray()
    }

    /** 걷어낸 사본에서 VP8X 마디를 빼 원래 단순 형식과 비교한다 */
    private fun stripVp8x(b: ByteArray): ByteArray {
        val image = b.copyOfRange(12 + 8 + 10, b.size)
        return ByteArrayOutputStream().apply { write("RIFF".toByteArray()); le32(image.size + 4); write("WEBP".toByteArray()); write(image) }.toByteArray()
    }

    private fun ByteArrayOutputStream.chunk(id: String, data: ByteArray) {
        write(id.toByteArray()); le32(data.size); write(data); if (data.size % 2 == 1) write(0)
    }

    private fun ByteArrayOutputStream.le32(v: Int) = repeat(4) { write((v shr (8 * it)) and 0xFF) }

    private fun le24(b: ByteArray, at: Int, v: Int) = repeat(3) { b[at + it] = (v shr (8 * it)).toByte() }
}
