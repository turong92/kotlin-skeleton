package dev.sumin.skeleton.storage.image.imgproxy

import com.sun.net.httpserver.HttpServer
import dev.sumin.skeleton.storage.DetectedKind
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.UploadMagicBytes
import dev.sumin.skeleton.storage.image.VariantSpec
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.junit.jupiter.api.Assumptions.assumeTrue

/** 실제 imgproxy 컨테이너로 시험한다(Docker 가 없으면 건너뜀). 끝나면 컨테이너와 원본 서버를 내린다 */
class ImgproxyRendererTest {
    @BeforeTest
    fun requireDocker() = assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker is not available")

    private fun render(key: String, bytes: ByteArray, vararg specs: VariantSpec): List<dev.sumin.skeleton.storage.image.Rendered> {
        originals[key] = bytes
        val kind = UploadMagicBytes.detect(bytes.copyOf(minOf(bytes.size, UploadMagicBytes.HEAD_BYTES)))!!
        val info = renderer.probe(bytes.copyOf(minOf(bytes.size, UploadMagicBytes.HEAD_BYTES)), kind)
        return renderer.render(ObjectKey(key), { error("imgproxy reads the signed address, never the stream") }, info, specs.toList())
    }

    @Test
    fun `a heic photo with GPS becomes a jpeg and a webp that carry nothing from the input metadata`() {
        val photo = ImgproxyRendererTest::class.java.getResourceAsStream("/exif-gps.heic")!!.readBytes()
        val text = String(photo, Charsets.ISO_8859_1)
        assertThat(text).contains("TestCam").contains(GPS_IFD_POINTER_BE)   // 입력에는 정말 EXIF(기기 · GPS)가 있다

        val out = render("members/1/photo.heic", photo, VariantSpec("thumb", 48, 48, 80)).single()

        assertThat(out.contentType).isEqualTo("image/jpeg")
        assertThat(out.width to out.height).isEqualTo(48 to 32)
        val webp = out.alternates.single()
        assertThat(webp.contentType).isEqualTo("image/webp")
        assertThat(webp.width to webp.height).isEqualTo(48 to 32)
        listOf(out, webp).forEach { v ->
            val bytes = String(v.bytes, Charsets.ISO_8859_1)
            assertThat(bytes).doesNotContain("TestCam").doesNotContain("XMP ").doesNotContain("ICCP")
        }
        // libvips 가 WebP 에 입력과 무관한 뼈대 EXIF(해상도 · 버전)를 적는다: GPS 마디는 없다
        assertThat(String(exifChunk(webp.bytes), Charsets.ISO_8859_1)).doesNotContain(GPS_IFD_POINTER_LE)
        assertThat(String(jpegExif(out.bytes), Charsets.ISO_8859_1)).doesNotContain(GPS_IFD_POINTER_LE).doesNotContain(GPS_IFD_POINTER_BE)
    }

    /** JPEG 의 APP1(Exif) 마디 내용(없으면 빈 배열) - 압축 데이터 앞의 마디만 본다 */
    private fun jpegExif(jpeg: ByteArray): ByteArray {
        var i = 2
        while (i + 4 <= jpeg.size && jpeg[i] == 0xFF.toByte()) {
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xDA) break
            val length = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            if (marker == 0xE1) return jpeg.copyOfRange(i + 4, i + 2 + length)
            i += 2 + length
        }
        return ByteArray(0)
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


    private companion object {
        const val GPS_IFD_POINTER_LE = "%\u0088"   // 태그 0x8825 (리틀 엔디안)
        const val GPS_IFD_POINTER_BE = "\u0088%"   // 태그 0x8825 (빅 엔디안)
        const val IMAGE = "ghcr.io/imgproxy/imgproxy:v3.30.1"
        const val KEY = "943b421c9eb07c830af81030552c86009268de4e532ba2ee2eab8247c6da0881"
        const val SALT = "520f986b998545b4785e0defbc4f3c1203f22de2374a3d53cb7a7fe9fea309c5"

        val originals = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

        /** 원본을 내주는 서버 - JVM 당 하나(JVM 이 끝날 때 내린다) */
        val server: HttpServer by lazy {
            HttpServer.create(InetSocketAddress("0.0.0.0", 0), 0).apply {
                createContext("/orig/") { ex ->
                    val bytes = originals[ex.requestURI.path.removePrefix("/orig/")]
                    if (bytes == null) ex.sendResponseHeaders(404, -1) else { ex.sendResponseHeaders(200, bytes.size.toLong()); ex.responseBody.use { it.write(bytes) } }
                    ex.close()
                }
                start()
                Runtime.getRuntime().addShutdownHook(Thread { stop(0) })
            }
        }

        /** imgproxy 컨테이너 - JVM 당 하나, 테스트 인스턴스마다 띄우지 않는다. JVM 이 끝날 때 내린다(Testcontainers 의 정리 컨테이너도 거둔다) */
        val container: GenericContainer<*> by lazy {
            GenericContainer(DockerImageName.parse(IMAGE))
                .withEnv("IMGPROXY_KEY", KEY).withEnv("IMGPROXY_SALT", SALT)
                .withEnv("IMGPROXY_ALLOWED_SOURCES", "http://host.docker.internal:${server.address.port}/")   // 서명된 우리 원본 서버 말고는 가져오지 않는다
                .withEnv("IMGPROXY_MAX_SRC_RESOLUTION", "50")
                .withExtraHost("host.docker.internal", "host-gateway")
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/health").forStatusCode(200))
                .also {
                    it.start()
                    Runtime.getRuntime().addShutdownHook(Thread { it.stop() })
                }
        }

        val renderer: ImgproxyRenderer by lazy {
            ImgproxyRenderer(
                ImgproxyProperties(enabled = true, url = "http://${container.host}:${container.getMappedPort(8080)}", key = KEY, salt = SALT, timeout = Duration.ofSeconds(60)),
            ) { key, _ -> "http://host.docker.internal:${server.address.port}/orig/${key.value}" }
        }
    }
}
