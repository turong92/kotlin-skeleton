package dev.sumin.skeleton.storage.image.imgproxy

import com.sun.net.httpserver.HttpServer
import dev.sumin.skeleton.storage.DetectedKind
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.image.ImageReasons
import dev.sumin.skeleton.storage.image.ImageRejectedException
import dev.sumin.skeleton.storage.image.ImageInfo
import dev.sumin.skeleton.storage.image.VariantSpec
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.assertj.core.api.Assertions.assertThat

/** Docker 없이 imgproxy 를 흉내내는 서버로 요청 모양(서명 · 원본 주소 · 형식)과 응답 처리(상태 · 크기 상한)를 본다 */
class ImgproxyRequestsTest {
    private val paths = CopyOnWriteArrayList<String>()
    private var status = 200
    private var body: (String) -> ByteArray = { path -> if (path.endsWith(".webp")) webp(48, 32) else jpeg(48, 32) }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            paths += ex.requestURI.rawPath
            val bytes = if (status == 200) body(ex.requestURI.rawPath) else "Invalid source image".toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private val spec = VariantSpec("thumb", 48, 48, 80)
    private val info = ImageInfo("image/heic", 96, 64)
    private fun properties(output: ImgproxyProperties.Output = ImgproxyProperties.Output.BOTH, maxResponseBytes: Long = 1_000_000) =
        ImgproxyProperties(enabled = true, url = "http://127.0.0.1:${server.address.port}", key = KEY, salt = SALT, output = output, maxResponseBytes = maxResponseBytes)

    private fun renderer(p: ImgproxyProperties = properties()) = ImgproxyRenderer(p, sources = S3SourceResolver("photos"))

    private fun render(r: ImgproxyRenderer, i: ImageInfo = info) = r.render(ObjectKey("members/1/a b.heic"), { error("never read") }, i, listOf(spec))

    @Test
    fun `requests are signed and name the original by the resolver's address`() {
        render(renderer())

        val path = paths.first()
        val (signature, rest) = path.removePrefix("/").let { it.substringBefore('/') to "/" + it.substringAfter('/') }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(hex(KEY), "HmacSHA256")); update(hex(SALT)) }
        assertThat(signature).isEqualTo(Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(rest.toByteArray())))
        assertThat(rest).startsWith("/rs:fit:48:48/el:0/q:80/sm:1/kcr:0/scp:1/ar:1/")
        val source = rest.substringAfterLast('/').substringBeforeLast('.')
        assertThat(String(Base64.getUrlDecoder().decode(source))).isEqualTo("s3://photos/members/1/a b.heic")
    }

    @Test
    fun `both formats are asked for by default - the plain one first - and one or the other on request`() {
        val both = render(renderer()).single()
        assertThat(both.contentType).isEqualTo("image/jpeg")
        assertThat(both.alternates.single().contentType).isEqualTo("image/webp")
        assertThat(paths.map { it.substringAfterLast('.') }).containsExactly("jpg", "webp")

        paths.clear()
        val only = render(renderer(properties(ImgproxyProperties.Output.WEBP_ONLY))).single()
        assertThat(only.contentType).isEqualTo("image/webp")
        assertThat(only.alternates).isEmpty()
        assertThat(paths).hasSize(1)

        paths.clear()
        assertThat(render(renderer(properties(ImgproxyProperties.Output.FALLBACK_ONLY))).single().alternates).isEmpty()
        assertThat(paths.single()).endsWith(".jpg")
    }

    @Test
    fun `a transparent original gets png as the plain format`() {
        body = { path -> if (path.endsWith(".webp")) webp(48, 32) else png(48, 32) }

        val out = render(renderer(), info.copy(transparent = true)).single()

        assertThat(out.contentType).isEqualTo("image/png")
        assertThat(out.alternates.single().contentType).isEqualTo("image/webp")
    }

    @Test
    fun `an unreadable image is rejected for good and a signature mistake is a configuration error`() {
        status = 422
        assertFailsWith<ImageRejectedException> { render(renderer()) }

        status = 403
        val e = assertFailsWith<IllegalStateException> { render(renderer()) }
        assertThat(e.message).contains("IMGPROXY_KEY")
    }

    @Test
    fun `a server error is thrown as a transient error so the queue retries`() {
        status = 502
        assertFailsWith<java.io.IOException> { render(renderer()) }
    }

    @Test
    fun `an answer bigger than the cap is rejected`() {
        val e = assertFailsWith<ImageRejectedException> { render(renderer(properties(maxResponseBytes = 100))) }
        assertThat(e.code).isEqualTo(ImageReasons.IMAGE_TOO_LARGE)
    }

    @Test
    fun `heic size and transparency are read from the header without decoding`() {
        val heic = ImgproxyRequestsTest::class.java.getResourceAsStream("/exif-gps.heic")!!.readBytes()

        val probed = renderer().probe(heic, DetectedKind.HEIC)

        assertThat(probed.width to probed.height).isEqualTo(96 to 64)
        assertThat(probed.transparent).isFalse()
    }

    @Test
    fun `the s3 resolver names bucket and key and the prefix of a prefixed storage`() {
        assertThat(S3SourceResolver("photos").sourceUrl(ObjectKey("a/b.heic"), Duration.ofMinutes(1))).isEqualTo("s3://photos/a/b.heic")
        assertThat(S3SourceResolver("photos", "/tenant1/").sourceUrl(ObjectKey("a/b.heic"), Duration.ofMinutes(1))).isEqualTo("s3://photos/tenant1/a/b.heic")
        assertFailsWith<IllegalArgumentException> { S3SourceResolver("photos/evil") }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun jpeg(w: Int, h: Int) = ByteArrayOutputStream().also { ImageIO.write(java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpeg", it) }.toByteArray()
    private fun png(w: Int, h: Int) = ByteArrayOutputStream().also { ImageIO.write(java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB), "png", it) }.toByteArray()

    /** 손으로 만든 최소 WebP(무손실) 머리 - 크기만 맞으면 된다 */
    private fun webp(w: Int, h: Int): ByteArray {
        val bits = (w - 1) or ((h - 1) shl 14)
        val chunk = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).put(0x2F).putInt(bits).array()
        val payload = "WEBP".toByteArray() + "VP8L".toByteArray() + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(chunk.size).array() + chunk + byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0)
        return "RIFF".toByteArray() + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size).array() + payload
    }

    private companion object {
        const val KEY = "943b421c9eb07c830af81030552c86009268de4e532ba2ee2eab8247c6da0881"
        const val SALT = "520f986b998545b4785e0defbc4f3c1203f22de2374a3d53cb7a7fe9fea309c5"
    }
}
