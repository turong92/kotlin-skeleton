package dev.sumin.skeleton.storage.image.imgproxy

import com.sun.net.httpserver.HttpServer
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.upload.UploadImageState
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.ObjectStorage
import dev.sumin.skeleton.storage.StoredObjectMetadata
import dev.sumin.skeleton.storage.UploadObjectRequest
import dev.sumin.skeleton.storage.image.ImageProperties
import dev.sumin.skeleton.storage.image.ImageRenderers
import dev.sumin.skeleton.storage.image.ImageRow
import dev.sumin.skeleton.storage.image.ImageStatusStore
import dev.sumin.skeleton.storage.image.ImageVariantProcessor
import dev.sumin.skeleton.storage.image.StoredVariant
import dev.sumin.skeleton.storage.image.imageKeyHash
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat

/** imgproxy 를 켠 조립에서도 움직이는 GIF 는 움직임을 잃지 않는다 - display 는 메타데이터만 걷어낸 사본, imgproxy 는 썸네일(첫 장)만 */
class ImgproxyAnimatedTest {
    private val requested = CopyOnWriteArrayList<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            requested += ex.requestURI.rawPath
            val bytes = if (ex.requestURI.rawPath.endsWith(".webp")) webp(8, 8)
            else ByteArrayOutputStream().also { ImageIO.write(java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB), "jpeg", it) }.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
            ex.close()
        }
        start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private val objects = ConcurrentHashMap<String, Pair<ByteArray, String?>>()
    private val storage: ObjectStorage = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ObjectStorage::class.java)) { _, method, args ->
        when (method.name) {
            "metadata" -> objects[(args[0] as ObjectKey).value]?.let { StoredObjectMetadata(args[0] as ObjectKey, sizeBytes = it.first.size.toLong(), contentType = it.second) }
            "read" -> ByteArrayInputStream(objects.getValue((args[0] as ObjectKey).value).first)
            "upload" -> (args[0] as UploadObjectRequest).let { objects[it.key.value] = it.content to it.contentType }.let { null }
            else -> error("not used: ${method.name}")
        }
    } as ObjectStorage

    private val rows = ConcurrentHashMap<String, ImageRow>()
    private val store = object : ImageStatusStore {
        override fun find(keyHash: String) = rows[keyHash]
        override fun findAll(keyHashes: Collection<String>) = keyHashes.mapNotNull { h -> rows[h]?.let { h to it } }.toMap()
        override fun register(keyHash: String, objectKey: String, onCreated: () -> Unit): Boolean {
            if (rows.containsKey(keyHash)) return false
            rows[keyHash] = ImageRow(keyHash, objectKey, UploadImageState.PENDING)
            return true
        }
        override fun markReady(keyHash: String, width: Int, height: Int, format: String, animated: Boolean, variants: Map<String, StoredVariant>): Boolean {
            val r = rows[keyHash]?.takeIf { it.state == UploadImageState.PENDING } ?: return false
            rows[keyHash] = r.copy(state = UploadImageState.READY, width = width, height = height, format = format, animated = animated, variants = variants)
            return true
        }
        override fun markTerminal(keyHash: String, state: UploadImageState, reason: String, width: Int?, height: Int?): Boolean {
            val r = rows[keyHash]?.takeIf { it.state == UploadImageState.PENDING } ?: return false
            rows[keyHash] = r.copy(state = state, reason = reason)
            return true
        }
        override fun delete(keyHashes: Collection<String>) { keyHashes.forEach { rows.remove(it) } }
    }

    @Test
    fun `an animated gif keeps its frames in display and loses its XMP, only the thumbnail goes through imgproxy`() {
        fun ext(label: Int, id: String, data: ByteArray) = byteArrayOf(0x21, label.toByte(), 11) + id.toByteArray() + byteArrayOf(data.size.toByte()) + data + byteArrayOf(0)
        val frame = byteArrayOf(0x2C, 0, 0, 0, 0, 2, 0, 2, 0, 0, 2, 2, 0x44, 0x01, 0)
        val gif = "GIF89a".toByteArray() + byteArrayOf(2, 0, 2, 0, 0, 0, 0) + ext(0xFF, "NETSCAPE2.0", byteArrayOf(1, 0, 0)) +
            ext(0xFF, "XMP DataXMP", "<x:xmpmeta>secret place</x:xmpmeta>".toByteArray()) + frame + frame + frame + byteArrayOf(0x3B)
        val key = "members/1/move.gif"
        objects[key] = gif to "image/gif"
        val properties = ImageProperties(enabled = true)
        val renderers = ImageRenderers(listOf(ImgproxyRenderer(ImgproxyProperties(enabled = true, url = "http://127.0.0.1:${server.address.port}", key = "00ff", salt = "ab12"), sources = S3SourceResolver("photos"))))
        store.register(imageKeyHash(key), key) { }

        ImageVariantProcessor(storage, store, renderers, properties, TimeProvider.systemUtc()).process(imageKeyHash(key))

        val row = rows.getValue(imageKeyHash(key))
        assertThat(row.state).isEqualTo(UploadImageState.READY)
        assertThat(row.animated).isTrue()
        val displayBytes = objects.getValue("variants/${imageKeyHash(key).take(32)}/display.gif").first
        val display = String(displayBytes, Charsets.ISO_8859_1)
        assertThat(display).contains("NETSCAPE2.0").doesNotContain("xmpmeta")
        assertThat(displayBytes.toList().windowed(frame.size).count { it == frame.toList() }).describedAs("all three frames kept").isEqualTo(3)
        assertThat(requested).hasSize(2).allMatch { "/rs:fit:400:400/" in it }
        assertThat(requested.map { it.substringAfterLast('.') }).containsExactlyInAnyOrder("jpg", "webp")   // 썸네일의 기본 형식 + WebP 뿐 - display 는 imgproxy 를 거치지 않는다
    }

    /** 손으로 만든 최소 WebP(무손실) 머리 - 크기만 맞으면 된다 */
    private fun webp(w: Int, h: Int): ByteArray {
        fun le32(n: Int) = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(n).array()
        val chunk = byteArrayOf(0x2F) + le32((w - 1) or ((h - 1) shl 14))
        val payload = "WEBP".toByteArray() + "VP8L".toByteArray() + le32(chunk.size) + chunk + ByteArray(9)
        return "RIFF".toByteArray() + le32(payload.size) + payload
    }

    @Test
    fun `a gif whose first frame is bigger than the head is still treated as moving and both frames stay`() {
        val big = byteArrayOf(0x2C, 0, 0, 0, 0, 2, 0, 2, 0, 0, 2) + (0 until 1_100).flatMap { listOf(0xFF.toByte()) + List(255) { 1.toByte() } } + byteArrayOf(0)   // 약 280KB 한 장
        val gif = "GIF89a".toByteArray() + byteArrayOf(2, 0, 2, 0, 0, 0, 0) + big + big + byteArrayOf(0x3B)
        val key = "members/1/big.gif"
        objects[key] = gif to "image/gif"
        val properties = ImageProperties(enabled = true)
        val renderers = ImageRenderers(listOf(ImgproxyRenderer(ImgproxyProperties(enabled = true, url = "http://127.0.0.1:${server.address.port}", key = "00ff", salt = "ab12"), sources = S3SourceResolver("photos"))))
        store.register(imageKeyHash(key), key) { }

        ImageVariantProcessor(storage, store, renderers, properties, TimeProvider.systemUtc()).process(imageKeyHash(key))

        assertThat(rows.getValue(imageKeyHash(key)).animated).isTrue()
        val display = objects.getValue("variants/${imageKeyHash(key).take(32)}/display.gif").first.toList()
        val prefix = big.take(12)
        assertThat(display.windowed(12).count { it == prefix }).describedAs("both big frames kept").isEqualTo(2)
    }
}
