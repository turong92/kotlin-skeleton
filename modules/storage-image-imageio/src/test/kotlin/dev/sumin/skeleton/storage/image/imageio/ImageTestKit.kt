package dev.sumin.skeleton.storage.image.imageio

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.upload.UploadImageState
import dev.sumin.skeleton.storage.*
import dev.sumin.skeleton.storage.image.*
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier

/** 메모리 저장소 - 지운 키를 기억한다(작업이 원본을 지우지 않는지 보려고) */
class MemoryStorage : PresignedStorage {
    val objects = ConcurrentHashMap<String, Pair<ByteArray, String?>>()
    val deleted = java.util.Collections.synchronizedList(mutableListOf<String>())
    var failReads = false

    fun put(key: String, bytes: ByteArray, contentType: String?) { objects[key] = bytes to contentType }

    override fun upload(request: UploadObjectRequest): StoredObjectWriteResult {
        objects[request.key.value] = request.content to request.contentType
        return StoredObjectWriteResult(request.key)
    }

    override fun copy(request: CopyObjectRequest): StoredObjectWriteResult = error("not used")
    override fun list(request: ListObjectsRequest) = ListedObjects(objects.keys.filter { it.startsWith(request.prefix) }.sorted().map { StoredObjectSummary(ObjectKey(it)) })
    override fun metadata(key: ObjectKey): StoredObjectMetadata? = objects[key.value]?.let { StoredObjectMetadata(key, sizeBytes = it.first.size.toLong(), contentType = it.second) }

    override fun read(key: ObjectKey, range: LongRange?): InputStream {
        if (failReads) throw java.io.IOException("storage is down")
        val bytes = objects[key.value]?.first ?: error("no such object: ${key.value}")
        return (if (range == null) bytes else bytes.copyOfRange(range.first.toInt(), minOf(bytes.size.toLong(), range.last + 1).toInt())).inputStream()
    }

    override fun delete(key: ObjectKey) {
        deleted += key.value
        objects.remove(key.value)
    }

    private val expires = Instant.parse("2030-01-01T00:00:00Z")
    override fun presignUpload(request: PresignedUploadRequest) = error("not used")
    override fun presignDownload(request: PresignedDownloadRequest) = PresignedUrl(request.key, "GET", URI("http://s3.test/${request.key}?down"), emptyMap(), expires)
    override fun startMultipartUpload(request: StartMultipartUploadRequest) = error("not used")
    override fun presignMultipartUploadPart(request: PresignedMultipartUploadPartRequest) = error("not used")
    override fun completeMultipartUpload(request: CompleteMultipartUploadRequest) = error("not used")
    override fun abortMultipartUpload(request: AbortMultipartUploadRequest) = error("not used")
}

/** 메모리 상태 표 - 줄을 넣으면 [onCreated] 를 부른다(작업 넣기) */
class MemoryStore : ImageStatusStore {
    val rows = ConcurrentHashMap<String, ImageRow>()

    @Synchronized
    override fun register(keyHash: String, objectKey: String, onCreated: () -> Unit): Boolean {
        if (rows.containsKey(keyHash)) return false
        rows[keyHash] = ImageRow(keyHash, objectKey, UploadImageState.PENDING)
        onCreated()
        return true
    }

    override fun find(keyHash: String) = rows[keyHash]
    override fun findAll(keyHashes: Collection<String>) = keyHashes.mapNotNull { h -> rows[h]?.let { h to it } }.toMap()

    @Synchronized
    override fun markReady(keyHash: String, width: Int, height: Int, format: String, animated: Boolean, variants: Map<String, StoredVariant>): Boolean {
        val row = rows[keyHash]?.takeIf { it.state == UploadImageState.PENDING } ?: return false
        rows[keyHash] = row.copy(state = UploadImageState.READY, width = width, height = height, format = format, animated = animated, variants = variants)
        return true
    }

    @Synchronized
    override fun markTerminal(keyHash: String, state: UploadImageState, reason: String, width: Int?, height: Int?): Boolean {
        val row = rows[keyHash]?.takeIf { it.state == UploadImageState.PENDING } ?: return false
        rows[keyHash] = row.copy(state = state, reason = reason, width = width, height = height)
        return true
    }

    @Synchronized
    override fun replaceVariant(keyHash: String, name: String, variant: StoredVariant): Boolean {
        val row = rows[keyHash]?.takeIf { it.state == UploadImageState.READY && name in it.variants } ?: return false
        rows[keyHash] = row.copy(variants = row.variants + (name to variant))
        return true
    }

    override fun delete(keyHashes: Collection<String>) { keyHashes.forEach { rows.remove(it) } }
}

/** 처리기 · 등록 · 검사기를 한 벌로 묶는다 - 큐 대신 넣은 작업(키 해시)을 [jobs] 에 모은다 */
class Harness(val properties: ImageProperties = ImageProperties(enabled = true), converter: AnimatedDisplayConverter = AnimatedDisplayConverter.NONE) {
    val storage = MemoryStorage()
    val store = MemoryStore()
    val jobs = java.util.Collections.synchronizedList(mutableListOf<String>())
    val renderers = ImageRenderers(listOf(ImageIoRenderer()))
    val images = StorageImages(storage, store, { jobs += it }, renderers, properties)
    val processor = ImageVariantProcessor(storage, store, renderers, properties, TimeProvider.systemUtc(), converter)
    val displays = AnimatedDisplays(store, properties)

    /** 올려 두고 등록해서 작업 하나를 돌린다 - 돌려주는 값은 상태 줄 */
    fun upload(key: String, bytes: ByteArray, contentType: String): String {
        storage.put(key, bytes, contentType)
        images.ensure(key)
        return imageKeyHash(key)
    }

    fun run(key: String, bytes: ByteArray, contentType: String): ImageRow {
        val hash = upload(key, bytes, contentType)
        processor.process(hash)
        return store.find(hash)!!
    }

    fun variantBytes(row: ImageRow, name: String): ByteArray {
        val v = row.variants.getValue(name)
        return storage.objects.getValue("${properties.prefix}/${row.keyHash.take(32)}/$name.${v.ext}").first
    }
}

// ---- 시험 사진 ------------------------------------------------------------------------------------------------

object Photos {
    fun rgb(width: Int, height: Int, color: Color = Color(200, 40, 40)): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { img -> img.createGraphics().apply { this.color = color; fillRect(0, 0, width, height); this.color = Color(30, 30, 200); fillRect(0, 0, width / 2, height / 2); dispose() } }

    fun jpeg(width: Int, height: Int, orientation: Int? = null, gps: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(rgb(width, height), "jpeg", out)
        val bytes = out.toByteArray()
        if (orientation == null && !gps) return bytes
        val exif = exifSegment(orientation, gps)
        return bytes.copyOfRange(0, 2) + exif + bytes.copyOfRange(2, bytes.size)   // SOI 바로 뒤에 APP1
    }

    fun png(width: Int, height: Int, alpha: Boolean): ByteArray {
        val img = BufferedImage(width, height, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply { color = Color(10, 120, 60, if (alpha) 128 else 255); fillRect(0, 0, width, height); dispose() }
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    /** 두 장짜리 움직이는 GIF */
    fun animatedGif(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            writer.prepareWriteSequence(null)
            listOf(Color.RED, Color.BLUE).forEach { c ->
                val frame = rgb(width, height, c)
                writer.writeToSequence(IIOImage(frame, null, writer.getDefaultImageMetadata(ImageTypeSpecifier(frame), null)), null)
            }
            writer.endWriteSequence()
        }
        writer.dispose()
        return out.toByteArray()
    }

    /** 머리에 3만 x 3만을 적은 작은 PNG - 디코드하면 3.6GB 다. IDAT 는 비어 있다 */
    fun pixelBomb(width: Int = 30_000, height: Int = 30_000): ByteArray {
        fun chunk(type: String, data: ByteArray): ByteArray {
            val crc = CRC32().apply { update(type.toByteArray()); update(data) }.value
            return ByteBuffer.allocate(12 + data.size).putInt(data.size).put(type.toByteArray()).put(data).putInt(crc.toInt()).array()
        }
        val ihdr = ByteBuffer.allocate(13).putInt(width).putInt(height).put(8).put(2).put(0).put(0).put(0).array()
        return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + chunk("IHDR", ihdr) + chunk("IDAT", ByteArray(0)) + chunk("IEND", ByteArray(0))
    }

    /** APP1 Exif 마디(빅엔디안) - 방향(0x0112)과 GPS IFD(위도 참조 · 위도 37/33/0) */
    private fun exifSegment(orientation: Int?, gps: Boolean): ByteArray {
        val tiff = ByteBuffer.allocate(256)
        tiff.put("MM".toByteArray()).putShort(0x002A).putInt(8)
        val entries = (if (orientation != null) 1 else 0) + (if (gps) 1 else 0)
        val gpsIfdOffset = 8 + 2 + entries * 12 + 4
        tiff.putShort(entries.toShort())
        if (orientation != null) tiff.putShort(0x0112).putShort(3).putInt(1).putShort(orientation.toShort()).putShort(0)
        if (gps) tiff.putShort(0x8825.toShort()).putShort(4).putInt(1).putInt(gpsIfdOffset)
        tiff.putInt(0)
        if (gps) {
            val rationalOffset = gpsIfdOffset + 2 + 2 * 12 + 4
            tiff.putShort(2)
            tiff.putShort(1).putShort(2).putInt(2).put('N'.code.toByte()).put(0).putShort(0)             // GPSLatitudeRef = "N"
            tiff.putShort(2).putShort(5).putInt(3).putInt(rationalOffset)                                // GPSLatitude
            tiff.putInt(0)
            tiff.putInt(37).putInt(1).putInt(33).putInt(1).putInt(0).putInt(1)
        }
        val body = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff.array().copyOf(tiff.position())
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte()) + ByteBuffer.allocate(2).putShort((body.size + 2).toShort()).array() + body
    }

    /** JPEG 마디 표지들(SOS 앞까지) - APP1(EXIF · XMP) · APP13(IPTC) 이 남았는지 본다 */
    fun jpegMarkers(jpeg: ByteArray): List<Int> {
        val markers = mutableListOf<Int>()
        var i = 2
        while (i + 4 <= jpeg.size) {
            check(jpeg[i] == 0xFF.toByte()) { "not a marker at $i" }
            val marker = jpeg[i + 1].toInt() and 0xFF
            markers += marker
            if (marker == 0xDA) break
            i += 2 + (((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF))
        }
        return markers
    }

    fun size(bytes: ByteArray): Pair<Int, Int> = ImageIO.read(bytes.inputStream()).let { it.width to it.height }
}
