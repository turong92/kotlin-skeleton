package dev.sumin.skeleton.storage.image.imageio

import dev.sumin.skeleton.common.upload.UploadImageState
import dev.sumin.skeleton.common.upload.UploadImages
import dev.sumin.skeleton.storage.image.ImageProperties
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `webp-passthrough` - 이미 표시본 상자 안의 멈춘 WebP 는 표시본을 다시 인코딩하지 않고 원본 그대로 쓴다(그림 마디만 있을 때) */
class WebpPassthroughTest {
    private val small = resource("still-120x80.webp")
    private val big = resource("still-2000x1000.webp")
    private val on = ImageProperties(enabled = true, webpPassthrough = true)

    @Test
    fun `a still WebP of image chunks only, inside the display box, becomes the display byte for byte - the thumb is still made`() {
        val h = Harness(on)

        val row = h.run("uploads/7/w/x.webp", small, "image/webp")

        assertEquals(UploadImageState.READY, row.state)
        val display = row.variants.getValue(UploadImages.DISPLAY)
        assertEquals("webp", display.ext)
        assertEquals(120 to 80, display.width to display.height)
        assertTrue(small.contentEquals(h.variantBytes(row, UploadImages.DISPLAY)))
        assertEquals("jpg", row.variants.getValue(UploadImages.THUMB).ext)
        assertFalse(row.animated)
    }

    @Test
    fun `EXIF, an unknown chunk or bytes after the RIFF length send the WebP back to re-encoding`() {
        val h = Harness(on)
        val cases = mapOf(
            "exif" to withChunk(small, "EXIF", "Exif\u0000\u0000GPS-SECRET".toByteArray(), vp8xFlags = 0x08),
            "unknown" to withChunk(small, "ABCD", "payload!".toByteArray()),
            "trailing" to small + "RIFFtail".toByteArray(),
        )
        cases.forEach { (name, bytes) ->
            val row = h.run("uploads/7/w/$name.webp", bytes, "image/webp")
            assertEquals(UploadImageState.READY, row.state, name)
            assertEquals("jpg", row.variants.getValue(UploadImages.DISPLAY).ext, name)
            assertFalse(String(h.variantBytes(row, UploadImages.DISPLAY), Charsets.ISO_8859_1).contains("GPS-SECRET"), name)
        }
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

    /** 단순 형식(VP8) WebP 를 VP8X + 그림 + [id] 마디로 감싼다 */
    private fun withChunk(simple: ByteArray, id: String, data: ByteArray, vp8xFlags: Int = 0): ByteArray {
        val image = simple.copyOfRange(12, simple.size)
        val vp8x = ByteArray(10).also { it[0] = vp8xFlags.toByte(); le24(it, 4, 120 - 1); le24(it, 7, 80 - 1) }
        val body = ByteArrayOutputStream().apply { write("WEBP".toByteArray()); chunk("VP8X", vp8x); write(image); chunk(id, data) }.toByteArray()
        return ByteArrayOutputStream().apply { write("RIFF".toByteArray()); le32(body.size); write(body) }.toByteArray()
    }

    private fun ByteArrayOutputStream.chunk(id: String, data: ByteArray) {
        write(id.toByteArray()); le32(data.size); write(data); if (data.size % 2 == 1) write(0)
    }

    private fun ByteArrayOutputStream.le32(v: Int) = repeat(4) { write((v shr (8 * it)) and 0xFF) }

    private fun le24(b: ByteArray, at: Int, v: Int) = repeat(3) { b[at + it] = (v shr (8 * it)).toByte() }
}
