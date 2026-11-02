package dev.sumin.skeleton.storage.image.pdfbox

import dev.sumin.skeleton.storage.DetectedKind
import dev.sumin.skeleton.storage.image.ImageInfo
import dev.sumin.skeleton.storage.image.ImageReasons
import dev.sumin.skeleton.storage.image.ImageRejectedException
import dev.sumin.skeleton.storage.image.ImageRenderer
import dev.sumin.skeleton.storage.image.Rendered
import dev.sumin.skeleton.storage.image.VariantSpec
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.io.MemoryUsageSetting
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType3Font
import org.apache.pdfbox.pdmodel.graphics.image.PDImage
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.rendering.PageDrawer
import org.apache.pdfbox.rendering.PageDrawerParameters
import org.apache.pdfbox.util.Matrix
import org.apache.pdfbox.util.Vector

/** PDF 변환이 거절하는 이유 코드 - 상태 줄 `reason` 에 들어가고 사용자에게 보여도 된다(그 밖은 [ImageReasons]) */
object PdfReasons {
    const val ENCRYPTED = "PDF_ENCRYPTED"
    const val TOO_MANY_PAGES = "PDF_TOO_MANY_PAGES"
    const val RENDER_TIMEOUT = "PDF_RENDER_TIMEOUT"
}

/**
 * PDF 1쪽을 그려 JPEG 변환본(메타데이터 없음)으로 만드는 엔진 - PDFBox. 작업 큐의 변환 작업([dev.sumin.skeleton.storage.image.ImageVariantProcessor])에서만 돈다.
 *
 * 상한([PdfProperties]): 원본 바이트(읽다가 넘으면 거절) · 쪽 수 · 1쪽 크기 · 그리는 시간(넘으면 그리기를 중단) · PDFBox 메모리(큰 부분은 임시 파일로, 그 임시 파일도 상한).
 * 암호가 걸려 열 수 없는 PDF 와 깨진 PDF 는 [ImageRejectedException] - 작업이 `REJECTED` 로 적고 **원본은 지우지 않는다**. 저장소를 읽다 난 오류는 그대로 던져 큐가 다시 한다.
 * 변환본은 흰 바탕의 1쪽(출력 크기에 맞춰 그리고, 더 작은 변환본은 줄여서)이고 페이지 안의 링크 · 스크립트 · 첨부는 실행하지도 담지도 않는다.
 *
 * JVM 전용 - AWT 로 그리므로 **headless** 로 돌린다(`java.awt.headless=true` 를 정하지 않았으면 켠다). 폰트가 박히지 않은 PDF 는 시스템 폰트를 쓰므로 배포 이미지에 폰트(예: fontconfig + 기본 폰트)가 있어야 글자가 모양대로 그려진다. 네이티브 이미지에서는 뺀다.
 */
class PdfRenderer(private val properties: PdfProperties) : ImageRenderer {
    /** 실제로 그리고 있는 스레드 수의 상한 - 시간이 넘은 스레드가 끝나기 전에는 다음 그리기가 들어오지 못한다 */
    private val renders = Semaphore(1)

    init {
        if (System.getProperty("java.awt.headless") == null) System.setProperty("java.awt.headless", "true")
    }

    override val kinds: Set<DetectedKind> = setOf(DetectedKind.PDF)

    /** 머리에서 1쪽 크기(포인트)를 찾을 수 있으면 그것을, 아니면 0 x 0 으로 돌려준다 - 실제 상한은 [render] 가 연 문서로 확인한다 */
    override fun probe(head: ByteArray, kind: DetectedKind): ImageInfo {
        val match = MEDIA_BOX.find(String(head, Charsets.ISO_8859_1))
        val (width, height) = match?.destructured?.let { (a, b, c, d) ->
            runCatching { abs(c.toDouble() - a.toDouble()) to abs(d.toDouble() - b.toDouble()) }.getOrNull()
        }?.let { (w, h) -> min(w, MAX_REPORTED).roundToInt() to min(h, MAX_REPORTED).roundToInt() } ?: (0 to 0)
        return ImageInfo(kind.mime, width, height)
    }

    override fun render(source: () -> InputStream, info: ImageInfo, specs: List<VariantSpec>): List<Rendered> {
        if (specs.isEmpty()) return emptyList()
        val file = Files.createTempFile("skeleton-pdf-", ".pdf")
        try {
            copyLimited(source, file)
            val document = load(file)
            val handedOff = AtomicBoolean(false)   // 시간이 넘어 그리는 스레드가 아직 문서를 쓰고 있으면 문서 닫기는 그 스레드가 맡는다
            try {
                return renderFirstPage(document, specs, handedOff)
            } finally {
                if (!handedOff.get()) document.close()
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    /** 원본을 임시 파일로 받는다 - 바이트 상한을 넘으면 거절. 읽기 오류(저장소)는 그대로 던진다 */
    private fun copyLimited(source: () -> InputStream, target: Path) {
        var total = 0L
        source().use { input ->
            Files.newOutputStream(target).use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > properties.maxSourceBytes) throw ImageRejectedException(ImageReasons.IMAGE_TOO_LARGE, "The PDF is larger than ${properties.maxSourceBytes} bytes")
                    out.write(buffer, 0, n)
                }
            }
        }
    }

    private fun load(file: Path): PDDocument = try {
        Loader.loadPDF(file.toFile(), "", MemoryUsageSetting.setupMixed(properties.maxMainMemoryBytes, properties.maxTempBytes).streamCache)
    } catch (e: InvalidPasswordException) {
        throw ImageRejectedException(PdfReasons.ENCRYPTED, "The PDF needs a password", e)
    } catch (e: Exception) {
        throw ImageRejectedException(ImageReasons.NOT_AN_IMAGE, "The PDF cannot be read", e)   // 깨졌거나 PDFBox 의 메모리 · 임시 파일 상한을 넘었다
    }

    private fun renderFirstPage(document: PDDocument, specs: List<VariantSpec>, handedOff: AtomicBoolean): List<Rendered> {
        val pages = document.numberOfPages
        if (pages < 1) throw ImageRejectedException(ImageReasons.NOT_AN_IMAGE, "The PDF has no pages")
        if (pages > properties.maxPages) throw ImageRejectedException(PdfReasons.TOO_MANY_PAGES, "The PDF has $pages pages, more than the ${properties.maxPages} allowed")

        val page = document.getPage(0)
        val box = page.cropBox
        val turned = page.rotation % 180 != 0
        val width = (if (turned) box.height else box.width).toDouble()
        val height = (if (turned) box.width else box.height).toDouble()
        if (!(width > 0.0 && height > 0.0)) throw ImageRejectedException(ImageReasons.NOT_AN_IMAGE, "The first page has no size")
        if (width * height > properties.maxPagePixels) {
            throw ImageRejectedException(ImageReasons.IMAGE_TOO_LARGE, "The first page is ${width.roundToInt()}x${height.roundToInt()} points, more than the ${properties.maxPagePixels} allowed")
        }

        // 가장 큰 변환본에 맞춰 한 번 그리고 나머지는 줄인다(키우지 않는다, 단 72dpi 의 두 배까지는 선명하게)
        val largest = specs.maxBy { it.maxWidth.toLong() * it.maxHeight }
        val scale = min(MAX_SCALE, min(largest.maxWidth / width, largest.maxHeight / height)).toFloat()
        val drawn = draw(document, scale, handedOff)
        return specs.map { spec ->
            val (w, h) = fit(drawn.width, drawn.height, spec.maxWidth, spec.maxHeight)
            val image = scaleTo(drawn, w, h)
            Rendered(spec.name, encodeJpeg(image, spec.quality), "image/jpeg", image.width, image.height)
        }
    }

    /**
     * 별도 스레드에서 그리고 시간 상한까지만 기다린다 - 그리는 쪽도 연산마다 마감을 확인해 스스로 멈춘다([DeadlineRenderer]). 스레드를 죽일 수는 없으므로
     * **그리는 스레드가 정말 끝날 때까지 자리([renders])를 놓지 않는다** - 시간이 넘어 돌아간 작업 뒤에 새 작업이 겹쳐 그리지 못하고(자리가 날 때까지 기다리다 일시 오류), 문서는 그 스레드가 끝난 뒤 닫는다
     */
    private fun draw(document: PDDocument, scale: Float, handedOff: AtomicBoolean): BufferedImage {
        if (!renders.tryAcquire(properties.renderTimeout.toNanos(), TimeUnit.NANOSECONDS)) throw IOException("A previous PDF drawing is still running - try again later")
        val deadline = System.nanoTime() + properties.renderTimeout.toNanos()
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "pdf-render").apply { isDaemon = true } }
        try {
            val future = try {
                executor.submit<BufferedImage> {
                    try {
                        DeadlineRenderer(document, deadline, properties.maxImagePixels).renderImage(0, scale, ImageType.RGB)
                    } finally {
                        renders.release()
                        if (handedOff.get()) runCatching { document.close() }
                    }
                }
            } catch (e: RuntimeException) {
                renders.release()
                throw e
            }
            return try {
                future.get(properties.renderTimeout.toNanos(), TimeUnit.NANOSECONDS)
            } catch (e: TimeoutException) {
                handedOff.set(true)
                future.cancel(true)
                if (future.isDone) runCatching { document.close() }
                throw ImageRejectedException(PdfReasons.RENDER_TIMEOUT, "Drawing the first page took longer than ${properties.renderTimeout}")
            } catch (e: ExecutionException) {
                when (e.cause) {
                    is RenderDeadlineExceeded -> {
                        handedOff.set(false)
                        throw ImageRejectedException(PdfReasons.RENDER_TIMEOUT, "Drawing the first page took longer than ${properties.renderTimeout}")
                    }
                    is ImageTooLargeInPdf -> throw ImageRejectedException(ImageReasons.IMAGE_TOO_LARGE, e.cause!!.message ?: "An image in the PDF is too large")
                    else -> throw ImageRejectedException(ImageReasons.NOT_AN_IMAGE, "The first page cannot be drawn", e.cause)
                }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun fit(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        val scale = min(1.0, min(maxWidth.toDouble() / width, maxHeight.toDouble() / height))
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /** 반씩 줄이는 단계 + 마지막 한 번 - 한 번에 많이 줄여 생기는 계단을 피한다 */
    private fun scaleTo(src: BufferedImage, width: Int, height: Int): BufferedImage {
        var current = src
        while (current.width / 2 >= width && current.height / 2 >= height) current = resize(current, current.width / 2, current.height / 2)
        return if (current.width == width && current.height == height) current else resize(current, width, height)
    }

    private fun resize(src: BufferedImage, width: Int, height: Int): BufferedImage {
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(src, 0, 0, width, height, null)
        } finally {
            g.dispose()
        }
        return out
    }

    /** 메타데이터 없는 JPEG - 기본 쓰기 메타데이터(JFIF 머리)만 있다 */
    private fun encodeJpeg(image: BufferedImage, quality: Int): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality / 100f
            }
            val out = ByteArrayOutputStream()
            ImageIO.createImageOutputStream(out).use { ios ->
                writer.output = ios
                writer.write(null, IIOImage(image, null, null), param)
            }
            return out.toByteArray()
        } finally {
            writer.dispose()
        }
    }

    private companion object {
        val MEDIA_BOX = Regex("""/MediaBox\s*\[\s*([-+]?[\d.]+)\s+([-+]?[\d.]+)\s+([-+]?[\d.]+)\s+([-+]?[\d.]+)\s*]""")
        const val MAX_SCALE = 2.0
        const val MAX_REPORTED = 1_000_000.0
    }
}

private class RenderDeadlineExceeded : RuntimeException("PDF render deadline exceeded", null, false, false)

private class ImageTooLargeInPdf(message: String) : RuntimeException(message, null, false, false)

/**
 * 그리기 연산마다 마감을 확인한다 - 넘기면 [RenderDeadlineExceeded] 로 그리기를 끝낸다(스레드를 죽일 수 없으니 그리는 쪽이 멈추게 한다).
 * 쪽 안 이미지는 **디코드하기 전에** 픽셀 수를 확인하고([ImageTooLargeInPdf]), 표본 추출을 켜 큰 JPEG 가 줄여서 읽히게 한다.
 */
private class DeadlineRenderer(document: PDDocument, private val deadline: Long, private val maxImagePixels: Long) : PDFRenderer(document) {
    init {
        isSubsamplingAllowed = true
    }

    override fun createPageDrawer(parameters: PageDrawerParameters): PageDrawer = object : PageDrawer(parameters) {
        private fun abortIfLate() {
            if (System.nanoTime() - deadline > 0 || Thread.currentThread().isInterrupted) throw RenderDeadlineExceeded()
        }

        override fun fillPath(windingRule: Int) { abortIfLate(); super.fillPath(windingRule) }
        override fun strokePath() { abortIfLate(); super.strokePath() }
        override fun drawImage(pdImage: PDImage) {
            abortIfLate()
            val pixels = pdImage.width.toLong() * pdImage.height
            if (pixels > maxImagePixels) throw ImageTooLargeInPdf("An image in the PDF is ${pdImage.width}x${pdImage.height} pixels, more than the $maxImagePixels allowed")
            super.drawImage(pdImage)
        }
        override fun shadingFill(shadingName: COSName) { abortIfLate(); super.shadingFill(shadingName) }
        override fun showFontGlyph(textRenderingMatrix: Matrix, font: PDFont, code: Int, displacement: Vector) { abortIfLate(); super.showFontGlyph(textRenderingMatrix, font, code, displacement) }
        override fun showType3Glyph(textRenderingMatrix: Matrix, font: PDType3Font, code: Int, displacement: Vector) { abortIfLate(); super.showType3Glyph(textRenderingMatrix, font, code, displacement) }
    }
}
