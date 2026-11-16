package dev.sumin.skeleton.storage.image.ffmpeg

import dev.sumin.skeleton.common.upload.UploadImageState
import dev.sumin.skeleton.common.upload.UploadImages
import dev.sumin.skeleton.storage.CopyObjectRequest
import dev.sumin.skeleton.storage.ListObjectsRequest
import dev.sumin.skeleton.storage.ListedObjects
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.ObjectStorage
import dev.sumin.skeleton.storage.StoredObjectMetadata
import dev.sumin.skeleton.storage.StoredObjectWriteResult
import dev.sumin.skeleton.storage.UploadObjectRequest
import dev.sumin.skeleton.storage.image.ImageRow
import dev.sumin.skeleton.storage.image.ImageStatusStore
import dev.sumin.skeleton.storage.image.StoredVariant
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** 메모리 저장소 - 올린 것의 내용 종류 · 올린 횟수를 남긴다 */
class MemoryStorage : ObjectStorage {
    val objects = linkedMapOf<String, ByteArray>()
    val contentTypes = mutableMapOf<String, String?>()
    var uploads = 0

    override fun upload(request: UploadObjectRequest): StoredObjectWriteResult {
        uploads++
        objects[request.key.value] = request.content
        contentTypes[request.key.value] = request.contentType
        return StoredObjectWriteResult(request.key)
    }

    override fun read(key: ObjectKey, range: LongRange?): InputStream {
        val bytes = objects[key.value] ?: error("no such object ${key.value}")
        val part = if (range == null) bytes else bytes.copyOfRange(range.first.toInt(), minOf(bytes.size.toLong(), range.last + 1).toInt())
        return ByteArrayInputStream(part)
    }

    override fun metadata(key: ObjectKey): StoredObjectMetadata? = objects[key.value]?.let { StoredObjectMetadata(key, sizeBytes = it.size.toLong(), contentType = contentTypes[key.value]) }
    override fun delete(key: ObjectKey) { objects.remove(key.value) }
    override fun copy(request: CopyObjectRequest): StoredObjectWriteResult = throw UnsupportedOperationException()
    override fun list(request: ListObjectsRequest): ListedObjects = throw UnsupportedOperationException()
}

/** 상태 줄 하나짜리 저장소 - `replaceVariant` 만 진짜로 한다 */
class MemoryImageStore : ImageStatusStore {
    val rows = mutableMapOf<String, ImageRow>()

    override fun find(keyHash: String): ImageRow? = rows[keyHash]
    override fun findAll(keyHashes: Collection<String>): Map<String, ImageRow> = rows.filterKeys { it in keyHashes }
    override fun register(keyHash: String, objectKey: String, onCreated: () -> Unit): Boolean = throw UnsupportedOperationException()
    override fun markReady(keyHash: String, width: Int, height: Int, format: String, animated: Boolean, variants: Map<String, StoredVariant>): Boolean = throw UnsupportedOperationException()
    override fun markTerminal(keyHash: String, state: UploadImageState, reason: String, width: Int?, height: Int?): Boolean = throw UnsupportedOperationException()
    override fun delete(keyHashes: Collection<String>) { keyHashes.forEach(rows::remove) }

    override fun replaceVariant(keyHash: String, name: String, variant: StoredVariant): Boolean {
        val row = rows[keyHash]?.takeIf { it.state == UploadImageState.READY && name in it.variants } ?: return false
        rows[keyHash] = row.copy(variants = row.variants + (name to variant))
        return true
    }

    /** 사본 display 가 이미 `READY` 인 움직이는 이미지 한 줄 */
    fun readyAnimated(keyHash: String, objectKey: String, format: String, animated: Boolean = true) {
        rows[keyHash] = ImageRow(
            keyHash, objectKey, UploadImageState.READY, format = format, width = 10, height = 10, animated = animated,
            variants = mapOf(UploadImages.DISPLAY to StoredVariant(10, 10, format)),
        )
    }
}

/** 시험 그림 합성 - ffmpeg lavfi. 이 맥 · CI 에 ffmpeg 가 없으면 [available] 이 false 라 시험이 건너뛴다 */
object Synth {
    val available: Boolean by lazy {
        runCatching {
            val p = ProcessBuilder("ffmpeg", "-hide_banner", "-encoders").redirectErrorStream(true).start()
            val text = p.inputStream.readAllBytes().toString(Charsets.UTF_8)
            p.waitFor() == 0 && "libx264" in text && "libwebp_anim" in text
        }.getOrDefault(false)
    }

    private fun ffmpeg(vararg args: String) {
        val p = ProcessBuilder(listOf("ffmpeg", "-hide_banner", "-nostdin", "-v", "error", "-y") + args).redirectErrorStream(true).start()
        val out = p.inputStream.readAllBytes().toString(Charsets.UTF_8)
        check(p.waitFor(3, TimeUnit.MINUTES) && p.exitValue() == 0) { "ffmpeg 합성 실패: $out" }
    }

    /** 장면이 계속 바뀌는 불투명 GIF - 「앞 장면과 같은 부분」을 투명으로 적는 최적화(transdiff)를 켜서 파일에는 투명 표시가 있지만 합성한 화면은 불투명이다 */
    fun gifOpaque(dir: Path, name: String = "opaque.gif", w: Int = 240, h: Int = 180, sec: Int = 3): ByteArray {
        val out = dir.resolve(name)
        ffmpeg("-f", "lavfi", "-i", "testsrc2=size=${w}x$h:rate=10:duration=$sec", "-gifflags", "+offsetting+transdiff", out.toString())
        return Files.readAllBytes(out)
    }

    /** 실제 투명 배경 GIF - 투명한 바탕 위를 빨간 네모가 움직인다 */
    fun gifTransparent(dir: Path, name: String = "transparent.gif", sec: Int = 2): ByteArray {
        val out = dir.resolve(name)
        ffmpeg(
            "-f", "lavfi", "-i", "color=c=black@0:s=64x48:r=10:d=$sec,format=rgba[bg];color=c=red:s=16x16:r=10:d=$sec,format=rgba[fg];[bg][fg]overlay=x=n*3:y=10:format=auto,split[a][b];[a]palettegen=reserve_transparent=1[p];[b][p]paletteuse",
            out.toString(),
        )
        return Files.readAllBytes(out)
    }

    fun webpAnimated(dir: Path, name: String = "anim.webp", sec: Int = 2): ByteArray {
        val out = dir.resolve(name)
        ffmpeg("-f", "lavfi", "-i", "testsrc2=size=96x72:rate=30:duration=$sec", "-c:v", "libwebp_anim", "-lossless", "1", "-loop", "0", out.toString())
        return Files.readAllBytes(out)
    }

    /** 거절 시험용 PNG - 1x1 */
    val png: ByteArray = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")
}
