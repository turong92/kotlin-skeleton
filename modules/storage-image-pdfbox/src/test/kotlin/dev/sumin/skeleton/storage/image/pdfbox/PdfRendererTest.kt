package dev.sumin.skeleton.storage.image.pdfbox

import dev.sumin.skeleton.storage.DetectedKind
import dev.sumin.skeleton.storage.UploadMagicBytes
import dev.sumin.skeleton.storage.image.ImageReasons
import dev.sumin.skeleton.storage.image.ImageRejectedException
import dev.sumin.skeleton.storage.image.ImageRenderers
import dev.sumin.skeleton.storage.image.VariantSpec
import java.awt.Color
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Duration
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class PdfRendererTest {
    private val thumb = VariantSpec("thumb", 200, 200, 80)

    private fun pdf(pages: Int = 1, size: PDRectangle = PDRectangle.A4, encryptWith: String? = null): ByteArray = PDDocument().use { doc ->
        repeat(pages) {
            val page = PDPage(size).also { doc.addPage(it) }
            PDPageContentStream(doc, page).use { content ->
                content.setNonStrokingColor(Color(200, 40, 40))
                content.addRect(20f, 20f, 200f, 200f)
                content.fill()
            }
        }
        encryptWith?.let { password -> doc.protect(StandardProtectionPolicy(password, password, AccessPermission())) }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }

    private fun render(renderer: PdfRenderer, bytes: ByteArray, vararg specs: VariantSpec) =
        renderer.render({ ByteArrayInputStream(bytes) }, renderer.probe(bytes.copyOf(minOf(bytes.size, UploadMagicBytes.HEAD_BYTES)), DetectedKind.PDF), specs.toList())

    @Test
    fun `the first page of a small pdf becomes a jpeg thumbnail without metadata`() {
        val out = render(PdfRenderer(PdfProperties(enabled = true)), pdf(pages = 2), thumb).single()

        assertThat(out.contentType).isEqualTo("image/jpeg")
        assertThat(out.extension).isEqualTo("jpg")
        val image = ImageIO.read(ByteArrayInputStream(out.bytes))
        assertThat(image.width to image.height).isEqualTo(out.width to out.height)
        assertThat(out.width).isBetween(140, 142)    // A4 595 x 842 를 200 상자에 맞춘다: 높이 200, 폭 141 안팎
        assertThat(out.height).isBetween(199, 200)
        assertThat(image.getRGB(10, 190) and 0xFFFFFF).isNotEqualTo(0xFFFFFF)   // 흰 바탕이 아니라 그려졌다(왼쪽 아래의 빨간 사각형)
        val markers = String(out.bytes, Charsets.ISO_8859_1)
        assertThat(markers).doesNotContain("Exif").doesNotContain("http://ns.adobe.com/xap")
    }

    @Test
    fun `the pdf engine declares the pdf kind so the registration path picks it up`() {
        val renderers = ImageRenderers(listOf(PdfRenderer(PdfProperties(enabled = true))))

        assertThat(UploadMagicBytes.declaredKinds("application/pdf")).contains(DetectedKind.PDF)
        assertThat(renderers.handles(DetectedKind.PDF)).isTrue()
        assertThat(renderers.forKind(DetectedKind.PDF)).isNotNull()
        assertThat(renderers.forKind(DetectedKind.JPEG)).isNull()
    }

    @Test
    fun `too many pages is rejected`() {
        val e = assertFailsWith<ImageRejectedException> { render(PdfRenderer(PdfProperties(enabled = true, maxPages = 2)), pdf(pages = 3), thumb) }
        assertThat(e.code).isEqualTo(PdfReasons.TOO_MANY_PAGES)
    }

    @Test
    fun `a huge media box is rejected before anything is drawn`() {
        val e = assertFailsWith<ImageRejectedException> { render(PdfRenderer(PdfProperties(enabled = true)), pdf(size = PDRectangle(14_400f, 14_400f)), thumb) }
        assertThat(e.code).isEqualTo(ImageReasons.IMAGE_TOO_LARGE)
    }

    @Test
    fun `a pdf over the byte limit is rejected`() {
        val e = assertFailsWith<ImageRejectedException> { render(PdfRenderer(PdfProperties(enabled = true, maxSourceBytes = 100)), pdf(), thumb) }
        assertThat(e.code).isEqualTo(ImageReasons.IMAGE_TOO_LARGE)
    }

    @Test
    fun `a password protected pdf is rejected`() {
        val e = assertFailsWith<ImageRejectedException> { render(PdfRenderer(PdfProperties(enabled = true)), pdf(encryptWith = "secret"), thumb) }
        assertThat(e.code).isEqualTo(PdfReasons.ENCRYPTED)
    }

    @Test
    fun `a broken pdf is rejected`() {
        val e = assertFailsWith<ImageRejectedException> { render(PdfRenderer(PdfProperties(enabled = true)), "%PDF-1.7\nthis is not a pdf at all".toByteArray(), thumb) }
        assertThat(e.code).isEqualTo(ImageReasons.NOT_AN_IMAGE)
    }

    @Test
    fun `a render that runs past the time limit is stopped`() {
        val e = assertFailsWith<ImageRejectedException> { render(PdfRenderer(PdfProperties(enabled = true, renderTimeout = Duration.ofNanos(1))), pdf(), thumb) }
        assertThat(e.code).isEqualTo(PdfReasons.RENDER_TIMEOUT)
    }

    @Test
    fun `the engine is off by default and a bad limit fails startup`() {
        val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(PdfRendererAutoConfiguration::class.java))
        runner.run { assertThat(it).hasNotFailed().doesNotHaveBean(PdfRenderer::class.java) }
        runner.withPropertyValues("skeleton.storage.image.pdf.enabled=true").run { assertThat(it).hasNotFailed().hasSingleBean(PdfRenderer::class.java) }
        runner.withPropertyValues("skeleton.storage.image.pdf.enabled=true", "skeleton.storage.image.pdf.max-pages=0").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasStackTraceContaining("skeleton.storage.image.pdf.max-pages")
        }
    }
}
