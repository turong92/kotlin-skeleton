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
}
