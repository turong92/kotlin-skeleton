package dev.sumin.skeleton.storage.image.imageio

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.upload.UploadImageState
import dev.sumin.skeleton.storage.DetectedKind
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.image.ImageInfo
import dev.sumin.skeleton.storage.image.ImageProperties
import dev.sumin.skeleton.storage.image.ImageRenderer
import dev.sumin.skeleton.storage.image.ImageRenderers
import dev.sumin.skeleton.storage.image.ImageVariantProcessor
import dev.sumin.skeleton.storage.image.Rendered
import dev.sumin.skeleton.storage.image.StorageImages
import dev.sumin.skeleton.storage.image.VariantSpec
import dev.sumin.skeleton.storage.image.imageKeyHash
import java.io.InputStream
import java.time.Duration
import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat

/** 엔진이 같은 그림을 두 형식(JPEG + WebP)으로 만들 때 - 올리기 · 상태 줄 · Accept 로 고르기 · 지우기. 앱 안 엔진(형식 하나)에서도 같은 호출이 깨지지 않는다 */
class ImageAlternateFormatsTest {
    private val ttl = Duration.ofMinutes(1)

    /** 변환본마다 JPEG 와 WebP 를 내는 엔진 */
    private val dual = object : ImageRenderer {
        override val kinds = setOf(DetectedKind.JPEG)
        override fun probe(head: ByteArray, kind: DetectedKind) = ImageInfo("image/jpeg", 800, 400)
        override fun render(source: () -> InputStream, info: ImageInfo, specs: List<VariantSpec>) = specs.map {
            Rendered(it.name, "jpg-${it.name}".toByteArray(), "image/jpeg", 200, 100, listOf(Rendered(it.name, "webp-${it.name}".toByteArray(), "image/webp", 200, 100)))
        }
    }

    private class Wiring(engine: ImageRenderer) {
        val properties = ImageProperties(enabled = true)
        val storage = MemoryStorage()
        val store = MemoryStore()
        val renderers = ImageRenderers(listOf(engine))
        val images = StorageImages(storage, store, { }, renderers, properties)
        val processor = ImageVariantProcessor(storage, store, renderers, properties, TimeProvider.systemUtc())

        fun process(key: String, bytes: ByteArray) {
            storage.put(key, bytes, "image/jpeg")
            images.ensure(key)
            processor.process(imageKeyHash(key))
        }
    }

    @Test
    fun `both formats are stored and the Accept header picks between them`() {
        val w = Wiring(dual)
        w.process("members/1/a.jpg", Photos.jpeg(800, 400))

        val status = w.images.status("members/1/a.jpg")!!
        assertThat(status.state).isEqualTo(UploadImageState.READY)
        val thumb = status.variants.getValue("thumb")
        assertThat(thumb.contentType).isEqualTo("image/jpeg")
        assertThat(thumb.alternates.single().contentType).isEqualTo("image/webp")
        assertThat(thumb.alternates.single().key).endsWith("/thumb.webp").startsWith("variants/")
        assertThat(w.storage.objects.keys.filter { it.contains("/thumb.") }).hasSize(2)

        fun url(accept: String?) = w.images.signedVariantUrl("members/1/a.jpg", "thumb", ttl, accept)!!
        assertThat(url("image/avif,image/webp,image/apng,*/*;q=0.8")).contains("/thumb.webp")
        assertThat(url("image/webp;q=0.9, image/jpeg")).contains("/thumb.webp")
        assertThat(url("image/webp;q=0")).contains("/thumb.jpg")
        assertThat(url("image/*")).contains("/thumb.jpg")
        assertThat(url(null)).contains("/thumb.jpg")
        assertThat(w.images.signedVariantUrl("members/1/a.jpg", "thumb", ttl)!!).contains("/thumb.jpg")   // 옛 호출은 기본 형식
        assertThat(w.images.publicVariants("members/1/a.jpg").getValue("display").formats().map { it.contentType }).containsExactly("image/jpeg", "image/webp")
    }

    @Test
    fun `forgetting an image removes every format`() {
        val w = Wiring(dual)
        w.process("members/1/a.jpg", Photos.jpeg(800, 400))

        w.images.forget(listOf("members/1/a.jpg"))

        assertThat(w.storage.objects.keys.filter { it.startsWith("variants/") }).isEmpty()
    }

    @Test
    fun `the java engine makes one format and the same calls still work`() {
        val w = Wiring(ImageIoRenderer())
        w.process("members/1/a.jpg", Photos.jpeg(800, 400))

        val thumb = w.images.status("members/1/a.jpg")!!.variants.getValue("thumb")
        assertThat(thumb.alternates).isEmpty()
        assertThat(thumb.forAccept("image/webp")).isEqualTo(thumb)
        assertThat(w.images.signedVariantUrl("members/1/a.jpg", "thumb", ttl, "image/webp")!!).contains("/thumb.jpg")
        assertThat(ObjectKey(thumb.key).value).startsWith("variants/")
    }

    /** `ftyp` 상자에 `heic` 브랜드만 있는 머리 - 매직 바이트 판정이 HEIC 로 읽는다 */
    private val heic = byteArrayOf(0, 0, 0, 24) + "ftypheic".toByteArray() + ByteArray(4) + "mif1heic".toByteArray() + ByteArray(64)

    @Test
    fun `a heic original is converted when an engine reads heic and unsupported when none does`() {
        val engine = object : ImageRenderer {
            override val kinds = setOf(DetectedKind.HEIC, DetectedKind.HEIF)
            override fun probe(head: ByteArray, kind: DetectedKind) = ImageInfo("image/heic", 96, 64)
            override fun render(source: () -> InputStream, info: ImageInfo, specs: List<VariantSpec>) =
                specs.map { Rendered(it.name, "jpg".toByteArray(), "image/jpeg", 48, 32, listOf(Rendered(it.name, "webp".toByteArray(), "image/webp", 48, 32))) }
        }
        fun run(engines: ImageRenderer): UploadImageState {
            val w = Wiring(engines)
            w.storage.put("members/1/a.heic", heic, "image/heic")
            w.images.ensure("members/1/a.heic")
            w.processor.process(imageKeyHash("members/1/a.heic"))
            return w.images.status("members/1/a.heic")!!.state
        }

        assertThat(run(engine)).isEqualTo(UploadImageState.READY)
        assertThat(run(ImageIoRenderer())).isEqualTo(UploadImageState.UNSUPPORTED)
    }

    private fun le32(n: Int) = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(n).array()
    private fun chunk(id: String, payload: ByteArray) = id.toByteArray() + le32(payload.size) + payload + (if (payload.size % 2 == 1) byteArrayOf(0) else byteArrayOf())

    /** 움직임 + EXIF + XMP 비트가 켜진 WebP - EXIF 마디에 GPS 처럼 보이는 글자를 넣었다 */
    private val animatedWebp: ByteArray = run {
        val vp8x = byteArrayOf((0x02 or 0x08 or 0x04).toByte(), 0, 0, 0) + byteArrayOf(95, 0, 0, 63, 0, 0)
        val body = "WEBP".toByteArray() + chunk("VP8X", vp8x) + chunk("ANIM", ByteArray(6)) + chunk("EXIF", "Exif\u0000\u0000GPSLatitude=37.5".toByteArray()) +
            chunk("ANMF", ByteArray(16) { 7 }) + chunk("XMP ", "<x:xmpmeta>secret place</x:xmpmeta>".toByteArray())
        "RIFF".toByteArray() + le32(body.size) + body
    }

    @Test
    fun `an animated image's display variant is a metadata-free copy - never the original`() {
        val engine = object : ImageRenderer {
            override val kinds = setOf(DetectedKind.WEBP)
            override fun probe(head: ByteArray, kind: DetectedKind) = ImageInfo("image/webp", 96, 64, animated = true)
            override fun render(source: () -> InputStream, info: ImageInfo, specs: List<VariantSpec>) = specs.map { Rendered(it.name, "jpg".toByteArray(), "image/jpeg", 48, 32) }
        }
        val w = Wiring(engine)
        w.storage.put("members/1/a.webp", animatedWebp, "image/webp")
        w.images.ensure("members/1/a.webp")
        w.processor.process(imageKeyHash("members/1/a.webp"))

        val display = w.images.status("members/1/a.webp")!!.variants.getValue("display")
        assertThat(display.key).startsWith("variants/").endsWith("/display.webp")
        val copy = w.storage.objects.getValue(display.key).first
        val text = String(copy, Charsets.ISO_8859_1)
        assertThat(text).doesNotContain("Exif").doesNotContain("GPSLatitude").doesNotContain("xmpmeta").doesNotContain("EXIF").doesNotContain("XMP ")
        assertThat(text).contains("ANMF").contains("ANIM")   // 움직임은 남는다
        assertThat(java.nio.ByteBuffer.wrap(copy, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int).isEqualTo(copy.size - 8)   // RIFF 길이가 맞다
        assertThat(copy[20].toInt() and (0x08 or 0x04)).isZero()   // VP8X 의 EXIF · XMP 비트가 꺼졌다
        assertThat(copy[20].toInt() and 0x02).isNotZero()
    }

    @Test
    fun `an animated gif loses its XMP extension but keeps the loop count`() {
        fun ext(label: Int, id: String, data: ByteArray) = byteArrayOf(0x21, label.toByte(), 11) + id.toByteArray() + byteArrayOf(data.size.toByte()) + data + byteArrayOf(0)
        val frame = byteArrayOf(0x2C, 0, 0, 0, 0, 2, 0, 2, 0, 0, 2, 2, 0x44, 0x01, 0)
        val gif = "GIF89a".toByteArray() + byteArrayOf(2, 0, 2, 0, 0, 0, 0) + ext(0xFF, "NETSCAPE2.0", byteArrayOf(1, 0, 0)) +
            ext(0xFF, "XMP DataXMP", "<x:xmpmeta>secret place</x:xmpmeta>".toByteArray()) + byteArrayOf(0x21, 0xFE.toByte(), 3, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 0) + frame + frame + byteArrayOf(0x3B)

        val clean = dev.sumin.skeleton.storage.image.AnimatedImageCleaner.clean("image/gif", gif)

        val text = String(clean, Charsets.ISO_8859_1)
        assertThat(text).startsWith("GIF89a").contains("NETSCAPE2.0").doesNotContain("xmpmeta").doesNotContain("abc")
        assertThat(clean.last()).isEqualTo(0x3B.toByte())
        assertThat(clean.size).isEqualTo(gif.size - 51 - 7)   // XMP 확장 · 주석 확장이 빠진 만큼
    }

    @Test
    fun `a jpeg declared as octet-stream or nothing is still registered by its first bytes so the use sites never treat it as a plain file`() {
        listOf("application/octet-stream", null, "text/plain", "application/pdf").forEachIndexed { i, declared ->
            val w = Wiring(ImageIoRenderer())
            val key = "members/1/file$i"   // 이미지 확장자도 없다
            w.storage.put(key, Photos.jpeg(80, 40, gps = true), declared)

            w.images.ensure(key)

            assertThat(w.images.status(key)?.state).describedAs("declared as $declared").isEqualTo(UploadImageState.PENDING)
        }
        val w = Wiring(ImageIoRenderer())
        w.storage.put("members/1/notes", "just text".toByteArray(), "application/octet-stream")
        w.images.ensure("members/1/notes")
        assertThat(w.images.status("members/1/notes")).isNull()   // 이미지가 아니면 줄을 만들지 않는다
    }

    @Test
    fun `the verdict that a key is not an image is remembered so later views read nothing`() {
        val w = Wiring(ImageIoRenderer())
        w.storage.put("members/1/notes.pdf", "%PDF-1.7 not an image".toByteArray(), "application/pdf")
        w.images.ensure("members/1/notes.pdf")

        assertThat(w.store.rows.getValue(imageKeyHash("members/1/notes.pdf")).state).isEqualTo(UploadImageState.NOT_IMAGE)
        assertThat(w.images.status("members/1/notes.pdf")).isNull()   // 쓰는 곳에는 줄이 없는 것으로 보인다
        assertThat(w.images.views(listOf("members/1/notes.pdf"))).isEmpty()
        w.storage.failReads = true
        w.images.ensure("members/1/notes.pdf")   // 저장소를 읽으면 던진다 - 던지지 않으니 읽지 않았다
    }
}
