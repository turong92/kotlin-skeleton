package dev.sumin.skeleton.storage.image.imgproxy

import com.sun.net.httpserver.HttpServer
import dev.sumin.skeleton.storage.DetectedKind
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.UploadMagicBytes
import dev.sumin.skeleton.storage.image.ImageRejectedException
import dev.sumin.skeleton.storage.image.ImageReasons
import dev.sumin.skeleton.storage.image.VariantSpec
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.time.Duration
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.assertj.core.api.Assertions.assertThat
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.junit.jupiter.api.Assumptions.assumeTrue

/** 실제 imgproxy 컨테이너로 시험한다(Docker 가 없으면 건너뜀). 끝나면 컨테이너와 원본 서버를 내린다 */
class ImgproxyRendererTest {
    private val originals = HashMap<String, ByteArray>()
    private lateinit var server: HttpServer
    private var container: GenericContainer<*>? = null
    private lateinit var renderer: ImgproxyRenderer

    @BeforeTest
    fun start() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is not available")
        server = HttpServer.create(InetSocketAddress("0.0.0.0", 0), 0).apply {
            createContext("/orig/") { ex ->
                val bytes = originals[ex.requestURI.path.removePrefix("/orig/")]
                if (bytes == null) ex.sendResponseHeaders(404, -1) else { ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) } }
                ex.close()
            }
            start()
        }
        val port = server.address.port
        val c = GenericContainer(DockerImageName.parse(IMAGE))
            .withEnv("IMGPROXY_KEY", KEY).withEnv("IMGPROXY_SALT", SALT)
            .withEnv("IMGPROXY_ALLOWED_SOURCES", "http://host.docker.internal:$port/")   // 서명된 우리 원본 서버 말고는 가져오지 않는다
            .withEnv("IMGPROXY_MAX_SRC_RESOLUTION", "50")
            .withExtraHost("host.docker.internal", "host-gateway")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/health").forStatusCode(200))
        c.start()
        container = c
        renderer = ImgproxyRenderer(
            ImgproxyProperties(enabled = true, url = "http://${c.host}:${c.getMappedPort(8080)}", key = KEY, salt = SALT, timeout = Duration.ofSeconds(60)),
        ) { key, _ -> URI("http://host.docker.internal:$port/orig/${key.value}") }
    }

    @AfterTest
    fun stop() {
        container?.stop()
        if (::server.isInitialized) server.stop(0)
    }

    private fun render(key: String, bytes: ByteArray, vararg specs: VariantSpec): List<dev.sumin.skeleton.storage.image.Rendered> {
        originals[key] = bytes
        val kind = UploadMagicBytes.detect(bytes.copyOf(minOf(bytes.size, UploadMagicBytes.HEAD_BYTES)))!!
        val info = renderer.probe(bytes.copyOf(minOf(bytes.size, UploadMagicBytes.HEAD_BYTES)), kind)
        return renderer.render(ObjectKey(key), { error("imgproxy reads the signed address, never the stream") }, info, specs.toList())
    }

    @Test
    fun `a jpeg with GPS comes back as webp that carries nothing from the input metadata`() {
        val photo = jpegWithGps(800, 400)
        assertThat(String(photo, Charsets.ISO_8859_1)).contains("Exif")   // 입력에는 정말 EXIF(GPS) 가 있다
        val plain = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(800, 400, BufferedImage.TYPE_INT_RGB), "jpeg", it) }.toByteArray()

        val out = render("members/1/photo.jpg", photo, VariantSpec("thumb", 200, 200, 80)).single()
        val reference = render("members/1/plain.jpg", plain, VariantSpec("thumb", 200, 200, 80)).single()

        assertThat(out.contentType).isEqualTo("image/webp")
        assertThat(out.extension).isEqualTo("webp")
        assertThat(out.width to out.height).isEqualTo(200 to 100)
        val webp = String(out.bytes, Charsets.ISO_8859_1)
        assertThat(webp).startsWith("RIFF").contains("WEBP").doesNotContain("XMP ").doesNotContain("ICCP")
        // libvips 가 뼈대 EXIF(해상도 · 버전 - 입력과 무관)를 적는다: GPS 마디는 없고, 입력에 EXIF 가 있든 없든 똑같다
        assertThat(exifChunk(out.bytes)).isEqualTo(exifChunk(reference.bytes))
        assertThat(String(exifChunk(out.bytes), Charsets.ISO_8859_1)).doesNotContain(GPS_IFD_POINTER_LE)
    }

    @Test
    fun `an image over the source resolution limit is rejected by imgproxy for good`() {
        val gray = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(8_000, 8_000, BufferedImage.TYPE_BYTE_GRAY), "png", it) }.toByteArray()   // 64 MP, 한도 50 MP - 압축하면 작다
        assertThat(gray.size).isLessThan(1_000_000)

        val e = assertFailsWith<ImageRejectedException> { render("members/1/bomb.png", gray, VariantSpec("thumb", 200, 200, 80)) }

        assertThat(e.code).describedAs(e.message).isEqualTo(ImageReasons.NOT_AN_IMAGE)   // imgproxy 는 이유를 말하지 않는다 - 영구 거절이면 된다
    }

    /** RIFF 의 `EXIF` 마디 내용(없으면 빈 배열) */
    private fun exifChunk(webp: ByteArray): ByteArray {
        var i = 12
        while (i + 8 <= webp.size) {
            val size = ByteBuffer.wrap(webp, i + 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
            if (String(webp, i, 4, Charsets.ISO_8859_1) == "EXIF") return webp.copyOfRange(i + 8, i + 8 + size)
            i += 8 + size + (size and 1)
        }
        return ByteArray(0)
    }

    private fun jpegWithGps(width: Int, height: Int): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply { createGraphics().apply { color = Color(200, 40, 40); fillRect(0, 0, width, height); dispose() } }
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(img, "jpeg", it) }.toByteArray()
        val tiff = ByteBuffer.allocate(128)
        tiff.put("MM".toByteArray()).putShort(0x002A).putInt(8)
        tiff.putShort(1).putShort(0x8825.toShort()).putShort(4).putInt(1).putInt(26).putInt(0)   // IFD0: GPS IFD 포인터
        tiff.putShort(2)
        tiff.putShort(1).putShort(2).putInt(2).put('N'.code.toByte()).put(0).putShort(0)
        tiff.putShort(2).putShort(5).putInt(3).putInt(56)
        tiff.putInt(0)
        tiff.putInt(37).putInt(1).putInt(33).putInt(1).putInt(0).putInt(1)
        val body = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff.array().copyOf(tiff.position())
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte()) + ByteBuffer.allocate(2).putShort((body.size + 2).toShort()).array() + body
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    private companion object {
        const val GPS_IFD_POINTER_LE = "%\u0088"   // 태그 0x8825 (리틀 엔디안)
        const val IMAGE = "ghcr.io/imgproxy/imgproxy:v3.30.1"
        const val KEY = "943b421c9eb07c830af81030552c86009268de4e532ba2ee2eab8247c6da0881"
        const val SALT = "520f986b998545b4785e0defbc4f3c1203f22de2374a3d53cb7a7fe9fea309c5"
    }
}
