package dev.sumin.skeleton.storage.image

import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.upload.UploadImageState
import dev.sumin.skeleton.common.upload.UploadImages
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.ObjectStorage
import dev.sumin.skeleton.storage.UploadMagicBytes
import dev.sumin.skeleton.storage.UploadObjectRequest
import java.util.concurrent.Semaphore
import org.slf4j.LoggerFactory

/** 변환본 · 거절 사유 코드 - 상태 줄 `reason` 에 들어가고 사용자에게 보여도 된다 */
object ImageReasons {
    const val IMAGE_TOO_LARGE = "IMAGE_TOO_LARGE"
    const val NOT_AN_IMAGE = "NOT_AN_IMAGE"
    const val UNSUPPORTED_FORMAT = "UNSUPPORTED_FORMAT"
    const val RETRIES_EXHAUSTED = "RETRIES_EXHAUSTED"
}

/** 변환본의 저장소 키 `<prefix>/<해시 앞 32자>/<이름>.<확장자>` - 원본 키 없이는 계산할 수 없고 비밀이 필요 없다(비공개 영역이라 서명 주소로만 읽힌다) */
internal fun variantFolder(prefix: String, keyHash: String): String = prefix.trim('/') + "/" + keyHash.take(32) + "/"

/**
 * 변환 작업 하나(`storage.image.variants`) = 상태 줄 하나. 클라이언트도 완료 단계도 믿지 않고 **다시 검사**한다: 매직 바이트 -> 머리에서 크기 -> 픽셀 · 바이트 상한 -> (세마포어) -> 엔진.
 * 멱등이다 - 줄이 없거나 `PENDING` 이 아니면 아무것도 안 한다. 내용 때문에 못 만드는 것(`REJECTED` · `UNSUPPORTED`)은 줄에 적고 끝낸다 - **원본은 지우지 않는다**.
 * 저장소 · 일시 오류는 그대로 던져 큐가 다시 하게 한다(소진되면 [ImageStatusStore.markTerminal] 로 `FAILED` - `storage-image-jdbc` 의 죽은 작업 고리).
 */
class ImageVariantProcessor(
    private val storage: ObjectStorage,
    private val store: ImageStatusStore,
    private val renderers: ImageRenderers,
    private val properties: ImageProperties,
    private val time: TimeProvider,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val permits = Semaphore(properties.maxConcurrent)

    fun process(keyHash: String) {
        val row = store.find(keyHash)?.takeIf { it.state == UploadImageState.PENDING } ?: return
        val key = ObjectKey(row.objectKey)
        val meta = storage.metadata(key)
        if (meta == null) {
            store.delete(listOf(keyHash))   // 원본이 지워졌다
            return
        }
        val size = meta.sizeBytes ?: 0L
        if (size == 0L) return reject(keyHash, UploadImageState.REJECTED, ImageReasons.NOT_AN_IMAGE)

        val head = storage.read(key, 0L until minOf(size, UploadMagicBytes.HEAD_BYTES.toLong())).use { it.readNBytes(UploadMagicBytes.HEAD_BYTES) }
        val kind = UploadMagicBytes.detect(head)
        if (kind == null || !renderers.handles(kind)) return reject(keyHash, UploadImageState.REJECTED, ImageReasons.NOT_AN_IMAGE)
        // 바이트 · 픽셀 상한은 이미지 종류에만 - PDF 같은 엔진 전용 종류의 상한은 그 엔진이 지킨다
        val isImage = kind in ImageRenderers.IMAGE_KINDS
        if (isImage && size > properties.maxSourceBytes) return reject(keyHash, UploadImageState.REJECTED, ImageReasons.IMAGE_TOO_LARGE)
        val renderer = renderers.forKind(kind) ?: return reject(keyHash, UploadImageState.UNSUPPORTED, ImageReasons.UNSUPPORTED_FORMAT)

        val info = try {
            renderer.probe(head, kind)
        } catch (e: ImageRejectedException) {
            return reject(keyHash, UploadImageState.REJECTED, e.code, e)
        }
        if (isImage && info.width.toLong() * info.height > properties.maxPixels) {
            return reject(keyHash, UploadImageState.REJECTED, ImageReasons.IMAGE_TOO_LARGE, width = info.width, height = info.height)
        }

        // 움직이는 이미지는 썸네일만 첫 장으로 - 표시본은 움직임을 남기고 메타데이터를 걷어낸 사본이다(원본을 남에게 주지 않는다)
        val specs = properties.sizes().filterKeys { !info.animated || it == UploadImages.THUMB }
            .map { (name, s) -> VariantSpec(name, s.maxWidth, s.maxHeight, s.quality) }
        permits.acquireUninterruptibly()
        val rendered = try {
            renderer.render(key, { storage.read(key) }, info, specs)
        } catch (e: ImageRejectedException) {
            return reject(keyHash, UploadImageState.REJECTED, e.code, e, info.width, info.height)
        } finally {
            permits.release()
        }

        val folder = variantFolder(properties.prefix, keyHash)
        val files = rendered.flatMap { listOf(it) + it.alternates }.toMutableList()
        if (info.animated) {
            // 원본 전체를 메모리로 읽는다(최대 max-source-bytes) - 동시에 하나(max-concurrent)
            val clean = try {
                AnimatedImageCleaner.clean(info.contentType, storage.read(key).use { it.readAllBytes() })
            } catch (e: ImageRejectedException) {
                return reject(keyHash, UploadImageState.REJECTED, e.code, e, info.width, info.height)
            }
            files += Rendered(UploadImages.DISPLAY, clean, info.contentType, info.width, info.height)
        }
        files.forEach {
            storage.upload(UploadObjectRequest(ObjectKey("$folder${it.name}.${it.extension}"), it.bytes, it.contentType, cacheControl = CACHE_CONTROL))
        }
        val variants = rendered.associate { it.name to StoredVariant(it.width, it.height, it.extension, it.alternates.map { alt -> alt.extension }) } +
            if (info.animated) mapOf(UploadImages.DISPLAY to StoredVariant(info.width, info.height, extensionOf(info.contentType))) else emptyMap()
        val format = info.contentType.substringAfter('/')
        if (!store.markReady(keyHash, info.width, info.height, format, info.animated, variants) && store.find(keyHash) == null) {
            // 그 사이 상태 줄이 지워졌다(원본 회수) - 방금 올린 변환본이 고아가 되지 않게 치운다
            files.forEach { storage.delete(ObjectKey("$folder${it.name}.${it.extension}")) }
        }
    }

    private fun reject(keyHash: String, state: UploadImageState, reason: String, cause: Throwable? = null, width: Int? = null, height: Int? = null) {
        log.info("image {} {} ({}){}", keyHash.take(12), state, reason, cause?.let { ": " + it.message } ?: "")
        store.markTerminal(keyHash, state, reason, width, height)
    }

    companion object {
        /** 변환본은 내용이 바뀌지 않는다 - 비공개(서명 주소로만 읽힌다) 영역의 브라우저 캐시 */
        const val CACHE_CONTROL = "private, max-age=31536000, immutable"
    }
}
