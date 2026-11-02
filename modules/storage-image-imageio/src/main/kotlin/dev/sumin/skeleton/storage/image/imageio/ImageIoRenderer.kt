package dev.sumin.skeleton.storage.image.imageio

import dev.sumin.skeleton.storage.DetectedKind
import dev.sumin.skeleton.storage.image.ImageInfo
import dev.sumin.skeleton.storage.image.ImageRejectedException
import dev.sumin.skeleton.storage.image.ImageRenderer
import dev.sumin.skeleton.storage.image.Rendered
import dev.sumin.skeleton.storage.image.VariantSpec
import dev.sumin.skeleton.storage.image.ImageReasons
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.ImageWriteParam
import javax.imageio.stream.FileCacheImageInputStream
import javax.imageio.stream.ImageInputStream
import javax.imageio.stream.MemoryCacheImageInputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 앱 안 순수 자바 엔진(JDK ImageIO + TwelveMonkeys JPEG · WebP 읽기) - 추가 서버가 없다. JPEG · PNG · GIF · WebP 를 읽고 JPEG(투명이면 PNG)로 쓴다. HEIC · AVIF 는 못 읽는다.
 *
 * - **머리만 읽어 크기를 안다**([probe]): 디코드하지 않으므로 픽셀 폭탄은 여기서 크기로 걸린다.
 * - **디코드는 부분 표본 추출**(`ImageReadParam.setSourceSubsampling`)로 가장 큰 변환본의 크기 근처까지만 메모리에 올린다. 원본 바이트는 메모리가 아니라 임시 파일에 캐시한다.
 * - 큰 변환본에서 작은 변환본을 이어서 만든다(반씩 줄이는 단계 + 마지막 한 번) - 한 번에 많이 줄여 생기는 계단을 피한다.
 * - EXIF 방향을 픽셀에 반영하고, 색 공간은 sRGB 로 그려 넣고, **메타데이터 없이 다시 인코딩**한다(EXIF · GPS · ICC · XMP 가 변환본에 남지 않는다).
 * - 움직이는 GIF · WebP 는 첫 장으로 썸네일만 만든다(처리기가 표시본 요청을 빼 준다).
 *
 * JVM 전용이다 - headless AWT 를 쓰므로 네이티브 이미지에서는 뺀다. WebP 디코더는 표본 추출을 지키지 않을 수 있어 WebP 는 `max-pixels` 에 비례해 메모리를 쓴다(픽셀당 4바이트).
 */
class ImageIoRenderer(
    /** 표본 추출을 지키지 않아 전체를 디코드하는 형식(WebP)의 픽셀 상한 - 넘으면 읽기 전에 `IMAGE_TOO_LARGE` 로 거절. 기본 2400만(디코드 약 100MB) */
    private val maxFullDecodePixels: Long = 24_000_000L,
) : ImageRenderer {
    init {
        ImageIO.scanForPlugins()   // 파트 jar 안에서는 플러그인이 자동으로 안 보이는 환경(스프링 부트 중첩 jar)이 있다 - 문맥 클래스 로더로 다시 찾는다
    }

    override val kinds: Set<DetectedKind> = setOf(DetectedKind.JPEG, DetectedKind.PNG, DetectedKind.GIF, DetectedKind.WEBP)

    override fun probe(head: ByteArray, kind: DetectedKind): ImageInfo {
        val (width, height) = try {
            MemoryCacheImageInputStream(ByteArrayInputStream(head)).use { input ->
                withReader(input) { reader -> reader.getWidth(0) to reader.getHeight(0) }
            }
        } catch (e: ImageRejectedException) {
            throw e
        } catch (e: Exception) {
            throw ImageRejectedException(UNDECODABLE, "The image header cannot be read", e)
        }
        if (width < 1 || height < 1) throw ImageRejectedException(UNDECODABLE, "The image has no size")
        val orientation = if (kind == DetectedKind.JPEG) ExifOrientation.of(head) else 1
        val animated = when (kind) {
            DetectedKind.GIF -> GifScan.frames(head, limit = 2) > 1
            DetectedKind.WEBP -> animatedWebp(head)
            else -> false
        }
        val swap = orientation >= 5
        return ImageInfo(kind.mime, if (swap) height else width, if (swap) width else height, animated, orientation)
    }

    override fun render(source: () -> InputStream, info: ImageInfo, specs: List<VariantSpec>): List<Rendered> {
        if (specs.isEmpty()) return emptyList()
        val largest = specs.maxBy { it.maxWidth.toLong() * it.maxHeight }
        val (bw, bh) = fit(info.width, info.height, largest.maxWidth, largest.maxHeight)
        val step = sampleStep(info, bw, bh)
        // 표본 추출을 지키지 않는 WebP 는 원본 전체를 디코드한다 - 한 장 메모리 예산(픽셀)을 넘으면 읽기 전에 거절
        if (info.contentType == "image/webp" && info.width.toLong() * info.height > maxFullDecodePixels) {
            throw ImageRejectedException(ImageReasons.IMAGE_TOO_LARGE, "A ${info.width}x${info.height} WebP is decoded in full and exceeds the $maxFullDecodePixels pixel budget")
        }
        val guard = GuardedStream(source())
        val decoded = try {
            guard.use { decode(it, step) }
        } catch (e: Exception) {
            guard.failure?.let { throw it }   // 저장소가 읽다 끊겼다 - 내용이 아니라 일시 오류다. 큐가 다시 한다
            if (e is ImageRejectedException) throw e
            throw ImageRejectedException(UNDECODABLE, "The image cannot be decoded", e)
        }
        val alpha = decoded.colorModel.hasAlpha()
        val swap = info.orientation >= 5
        val orientedWidth = if (swap) decoded.height else decoded.width
        val orientedHeight = if (swap) decoded.width else decoded.height
        val made = HashMap<String, Rendered>()
        val chain = mutableListOf<BufferedImage>()
        specs.sortedByDescending { it.maxWidth.toLong() * it.maxHeight }.forEach { spec ->
            val (tw, th) = fit(orientedWidth, orientedHeight, spec.maxWidth, spec.maxHeight)
            val basis = chain.lastOrNull { it.width >= tw && it.height >= th }
            // 방향 반영은 줄인 뒤에 한다 - 디코드한 큰 그림의 회전 사본을 만들지 않는다
            val scaled = if (basis != null) scaleTo(basis, tw, th, alpha)
            else orient(scaleTo(decoded, if (swap) th else tw, if (swap) tw else th, alpha), info.orientation, alpha)
            chain += scaled
            made[spec.name] = encode(spec, scaled, alpha)
        }
        return specs.map { made.getValue(it.name) }
    }

    /**
     * 가장 큰 변환본([bw] x [bh])의 **두 배 이하**이면서 최대한 가까운 정수 표본 간격(올림) - 표본 추출은 점 표본이라 변환본과 같은 크기로 읽으면 가는 줄무늬에서
     * 무아레가 생긴다. 두 배 근처로 읽어 아래 단계가 평균내며 줄이게 한다. 디코드한 한 변이 변환본의 두 배를 넘지 않으므로 픽셀은 변환본(상자)의 네 배 이하다
     */
    private fun sampleStep(info: ImageInfo, bw: Int, bh: Int): Int =
        max(1, ceil(max(info.width / (2.0 * bw), info.height / (2.0 * bh))).toInt())

    private fun decode(stream: InputStream, step: Int): BufferedImage =
        FileCacheImageInputStream(stream, null).use { input ->
            withReader(input) { reader ->
                val param = reader.defaultReadParam.also { it.setSourceSubsampling(step, step, 0, 0) }
                reader.read(0, param)
            }
        }

    /** 입력을 읽을 수 있는 리더를 차례로 시도한다 - 한 리더(예: JDK JPEG)가 못 읽는 변형을 다음(TwelveMonkeys)이 읽을 수 있다 */
    private fun <T> withReader(input: ImageInputStream, block: (ImageReader) -> T): T {
        var failure: Exception? = null
        val readers = ImageIO.getImageReaders(input)
        while (readers.hasNext()) {
            val reader = readers.next()
            try {
                input.seek(0)
                reader.setInput(input, false, true)
                return block(reader)
            } catch (e: Exception) {
                failure = e
            } finally {
                reader.dispose()
            }
        }
        throw failure ?: ImageRejectedException(UNDECODABLE, "No image reader recognizes this content")
    }

    /** 긴 변을 [maxWidth] x [maxHeight] 상자에 맞춘 크기 - 키우지 않는다 */
    private fun fit(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        val scale = min(1.0, min(maxWidth.toDouble() / width, maxHeight.toDouble() / height))
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    private fun newImage(width: Int, height: Int, alpha: Boolean) =
        BufferedImage(width, height, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)

    private fun orient(src: BufferedImage, orientation: Int, alpha: Boolean): BufferedImage {
        if (orientation !in 2..8) return src
        val w = src.width.toDouble()
        val h = src.height.toDouble()
        val transform = when (orientation) {
            2 -> AffineTransform(-1.0, 0.0, 0.0, 1.0, w, 0.0)
            3 -> AffineTransform(-1.0, 0.0, 0.0, -1.0, w, h)
            4 -> AffineTransform(1.0, 0.0, 0.0, -1.0, 0.0, h)
            5 -> AffineTransform(0.0, 1.0, 1.0, 0.0, 0.0, 0.0)
            6 -> AffineTransform(0.0, 1.0, -1.0, 0.0, h, 0.0)
            7 -> AffineTransform(0.0, -1.0, -1.0, 0.0, h, w)
            else -> AffineTransform(0.0, -1.0, 1.0, 0.0, 0.0, w)
        }
        val swap = orientation >= 5
        val dest = newImage(if (swap) src.height else src.width, if (swap) src.width else src.height, alpha)
        val g = dest.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            g.drawImage(src, transform, null)
        } finally {
            g.dispose()
        }
        return dest
    }

    /** 반씩 줄이는 단계를 거쳐 마지막에 정확한 크기로 - sRGB 로 그리는 것이 색 공간 변환도 겸한다 */
    private fun scaleTo(src: BufferedImage, width: Int, height: Int, alpha: Boolean): BufferedImage {
        var current = src
        while (current.width >= 2 * width && current.height >= 2 * height) {
            current = draw(current, max(width, current.width / 2), max(height, current.height / 2), alpha)
        }
        val wanted = if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        return if (current.width == width && current.height == height && current.type == wanted) current else draw(current, width, height, alpha)
    }

    private fun draw(src: BufferedImage, width: Int, height: Int, alpha: Boolean): BufferedImage {
        val dest = newImage(width, height, alpha)
        val g = dest.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(src, 0, 0, width, height, null)
        } finally {
            g.dispose()
        }
        return dest
    }

    /** 메타데이터 없이 쓴다(`IIOImage` 의 메타데이터 null) - 투명하면 PNG, 아니면 progressive JPEG */
    private fun encode(spec: VariantSpec, image: BufferedImage, alpha: Boolean): Rendered {
        val out = ByteArrayOutputStream()
        if (alpha) {
            check(ImageIO.write(image, "png", out)) { "no PNG writer" }
            return Rendered(spec.name, out.toByteArray(), "image/png", image.width, image.height)
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            val param = writer.defaultWriteParam.also {
                it.compressionMode = ImageWriteParam.MODE_EXPLICIT
                it.compressionQuality = spec.quality / 100f
                it.progressiveMode = ImageWriteParam.MODE_DEFAULT
            }
            ImageIO.createImageOutputStream(out).use { ios ->
                writer.output = ios
                writer.write(null, IIOImage(image, null, null), param)
            }
        } finally {
            writer.dispose()
        }
        return Rendered(spec.name, out.toByteArray(), "image/jpeg", image.width, image.height)
    }

    /** `VP8X` 머리의 애니메이션 비트 */
    private fun animatedWebp(head: ByteArray): Boolean =
        head.size > 20 && String(head, 12, 4, Charsets.ISO_8859_1) == "VP8X" && (head[20].toInt() and 0x02) != 0

    private companion object {
        const val UNDECODABLE = "UNDECODABLE"
    }
}

/** 원본 스트림을 감싸 저장소가 던진 오류를 기억한다 - 디코더가 그것을 「깨진 이미지」 예외로 감싸도 일시 오류와 구분하려고 */
private class GuardedStream(private val delegate: InputStream) : InputStream() {
    var failure: Exception? = null
        private set

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        failure = e
        throw e
    }

    override fun read(): Int = guarded { delegate.read() }
    override fun read(b: ByteArray, off: Int, len: Int): Int = guarded { delegate.read(b, off, len) }
    override fun close() = delegate.close()
}
