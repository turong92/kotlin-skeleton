package dev.sumin.skeleton.storage.image.imageio

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.upload.UploadImageState
import dev.sumin.skeleton.common.upload.UploadImages
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.StorageUploadCompletion
import dev.sumin.skeleton.storage.image.ImageHeaderInspector
import dev.sumin.skeleton.storage.image.ImageProperties
import dev.sumin.skeleton.storage.image.imageKeyHash
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ImageVariantsTest {
    private val key = "uploads/7/abc/photo.jpg"

    @Test
    fun `variants of a photo with GPS and a rotation tag carry no EXIF or GPS`() {
        val h = Harness()
        val original = Photos.jpeg(200, 100, orientation = 6, gps = true)
        assertTrue(Photos.jpegMarkers(original).contains(0xE1), "the test photo must really carry an APP1 segment")

        val row = h.run(key, original, "image/jpeg")

        assertEquals(UploadImageState.READY, row.state)
        listOf(UploadImages.THUMB, UploadImages.DISPLAY).forEach { name ->
            val bytes = h.variantBytes(row, name)
            val markers = Photos.jpegMarkers(bytes)
            assertFalse(markers.any { it == 0xE1 || it == 0xED }, "$name still has APP1/APP13 (EXIF, XMP, IPTC): $markers")
            assertFalse(String(bytes, Charsets.ISO_8859_1).contains("Exif"), "$name still names Exif")
        }
        assertEquals(1, h.storage.objects.count { it.key == key }, "the original is kept as it was")
    }

    @Test
    fun `a pixel bomb from a tiny file is rejected from its header and never decoded`() {
        val h = Harness()
        val bomb = Photos.pixelBomb()
        assertTrue(bomb.size < 200)

        val row = h.run("uploads/7/bomb/x.png", bomb, "image/png")

        assertEquals(UploadImageState.REJECTED, row.state)
        assertEquals("IMAGE_TOO_LARGE", row.reason)
        assertTrue(row.variants.isEmpty())
        assertTrue(h.storage.deleted.isEmpty(), "a rejected job leaves the original")
    }

    @Test
    fun `the completion inspector rejects the same bomb from its header and the upload is deleted`() {
        val h = Harness()
        val bomb = Photos.pixelBomb()
        h.storage.put("uploads/7/bomb/x.png", bomb, "image/png")
        val completion = StorageUploadCompletion(h.storage, inspectors = { listOf(ImageHeaderInspector(h.renderers, h.properties)) })

        assertFailsWith<ApplicationException> { completion.complete(ObjectKey("uploads/7/bomb/x.png"), Long.MAX_VALUE) }

        assertEquals(listOf("uploads/7/bomb/x.png"), h.storage.deleted)
    }

    @Test
    fun `a rejected or failed job never deletes the original`() {
        val h = Harness()
        val corrupt = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(200) { 7 }   // JPEG 서명만 맞는 깨진 파일
        val corruptRow = h.run("uploads/7/bad/x.jpg", corrupt, "image/jpeg")
        assertEquals(UploadImageState.REJECTED, corruptRow.state)

        val ok = h.upload("uploads/7/down/x.jpg", Photos.jpeg(40, 40), "image/jpeg")
        h.storage.failReads = true
        assertFailsWith<java.io.IOException> { h.processor.process(ok) }   // 저장소 오류는 던져서 큐가 다시 한다
        assertEquals(UploadImageState.PENDING, h.store.find(ok)!!.state)

        assertTrue(h.storage.deleted.isEmpty())
        assertEquals(setOf("uploads/7/bad/x.jpg", "uploads/7/down/x.jpg"), h.storage.objects.keys.toSet())
    }

    @Test
    fun `orientation 6 and 8 swap the width and the height`() {
        val h = Harness()
        listOf(6, 8).forEach { orientation ->
            val row = h.run("uploads/7/o$orientation/x.jpg", Photos.jpeg(200, 100, orientation), "image/jpeg")
            assertEquals(100 to 200, row.width to row.height, "stored original size is after rotation (o=$orientation)")
            assertEquals(100 to 200, Photos.size(h.variantBytes(row, UploadImages.DISPLAY)), "display (o=$orientation)")
        }
        val plain = h.run("uploads/7/plain/x.jpg", Photos.jpeg(200, 100), "image/jpeg")
        assertEquals(200 to 100, Photos.size(h.variantBytes(plain, UploadImages.DISPLAY)))
    }

    @Test
    fun `a large photo is shrunk to the configured boxes and never enlarged`() {
        val h = Harness(ImageProperties(enabled = true, variants = ImageProperties().variants.copy(thumb = ImageProperties.Size(80, 80, 82), display = ImageProperties.Size(150, 150, 82))))
        val big = h.run("uploads/7/big/x.jpg", Photos.jpeg(600, 300), "image/jpeg")
        assertEquals(80 to 40, Photos.size(h.variantBytes(big, UploadImages.THUMB)))
        assertEquals(150 to 75, Photos.size(h.variantBytes(big, UploadImages.DISPLAY)))
        val small = h.run("uploads/7/small/x.jpg", Photos.jpeg(60, 30), "image/jpeg")
        assertEquals(60 to 30, Photos.size(h.variantBytes(small, UploadImages.DISPLAY)))
    }

    @Test
    fun `a transparent PNG stays a PNG`() {
        val h = Harness()
        val row = h.run("uploads/7/t/x.png", Photos.png(50, 50, alpha = true), "image/png")
        assertEquals("png", row.variants.getValue(UploadImages.DISPLAY).ext)
        assertEquals("png", row.variants.getValue(UploadImages.THUMB).ext)
        val opaque = h.run("uploads/7/o/x.png", Photos.png(50, 50, alpha = false), "image/png")
        assertEquals("jpg", opaque.variants.getValue(UploadImages.DISPLAY).ext)
    }

    @Test
    fun `an animated GIF gets a first-frame thumbnail and a display copy that is not the original`() {
        val h = Harness()
        val row = h.run("uploads/7/g/x.gif", Photos.animatedGif(60, 40), "image/gif")

        assertEquals(UploadImageState.READY, row.state)
        assertTrue(row.animated)
        assertNotNull(row.variants[UploadImages.THUMB])
        val status = h.images.status("uploads/7/g/x.gif")!!
        assertTrue(status.variants.getValue(UploadImages.DISPLAY).key.startsWith("variants/"), "display is a copy in the variants folder, never the original")
        assertEquals("image/gif", status.variants.getValue(UploadImages.DISPLAY).contentType)
        assertTrue(status.variants.getValue(UploadImages.THUMB).key.startsWith("variants/"))
        assertEquals(3, h.storage.objects.size, "the original, one thumbnail and the metadata-free display copy")
    }

    @Test
    fun `the same job run twice leaves the same result`() {
        val h = Harness()
        val hash = h.upload(key, Photos.jpeg(120, 80), "image/jpeg")
        h.processor.process(hash)
        val first = h.store.find(hash)!!
        val objects = h.storage.objects.mapValues { it.value.first.contentHashCode() }

        h.processor.process(hash)

        assertEquals(first, h.store.find(hash))
        assertEquals(objects, h.storage.objects.mapValues { it.value.first.contentHashCode() })
    }

    @Test
    fun `forgetting a key removes its row and variants and leaves other keys alone`() {
        val h = Harness()
        val a = h.run("uploads/7/a/x.jpg", Photos.jpeg(50, 50), "image/jpeg")
        val b = h.run("uploads/7/b/x.jpg", Photos.jpeg(50, 50), "image/jpeg")

        h.images.forget(listOf("uploads/7/a/x.jpg"))

        assertEquals(null, h.store.find(a.keyHash))
        assertNotNull(h.store.find(b.keyHash))
        assertTrue(h.storage.objects.keys.none { it.contains(a.keyHash.take(32)) })
        assertTrue(h.storage.objects.keys.count { it.contains(b.keyHash.take(32)) } == 2)
        assertEquals(imageKeyHash("uploads/7/a/x.jpg"), a.keyHash)
    }
}
