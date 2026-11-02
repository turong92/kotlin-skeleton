package dev.sumin.skeleton.storage.image

import dev.sumin.skeleton.common.upload.UploadImageState
import dev.sumin.skeleton.common.upload.UploadImageStatus
import dev.sumin.skeleton.common.upload.UploadImageVariant
import dev.sumin.skeleton.common.upload.UploadImages
import dev.sumin.skeleton.storage.ListObjectsRequest
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.PresignedDownloadRequest
import dev.sumin.skeleton.storage.PresignedStorage
import dev.sumin.skeleton.storage.UploadMagicBytes
import java.security.MessageDigest
import java.time.Duration

/** 원본 키의 상태 줄 열쇠 - sha256 hex(64). 긴 키를 유니크 색인에 넣을 수 있게 하고 변환본 경로에도 쓴다 */
fun imageKeyHash(objectKey: String): String =
    MessageDigest.getInstance("SHA-256").digest(objectKey.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** [UploadImages] 의 구현 - 상태 줄([ImageStatusStore])과 변환본 폴더([ImageProperties.prefix])를 다룬다 */
class StorageImages(
    private val storage: PresignedStorage,
    private val store: ImageStatusStore,
    private val tasks: ImageVariantTasks,
    private val renderers: ImageRenderers,
    private val properties: ImageProperties,
) : UploadImages {
    override val variantPrefix: String get() = properties.prefix.trim('/')

    override fun status(privateKey: String): UploadImageStatus? = store.find(imageKeyHash(privateKey))?.takeIf { it.state != UploadImageState.NOT_IMAGE }?.toStatus()

    override fun views(privateKeys: Collection<String>): Map<String, UploadImageStatus> {
        val hashes = privateKeys.associateBy { imageKeyHash(it) }
        return store.findAll(hashes.keys).mapNotNull { (hash, row) -> hashes[hash]?.takeIf { row.state != UploadImageState.NOT_IMAGE }?.let { it to row.toStatus() } }.toMap()
    }

    override fun signedVariantUrl(privateKey: String, variant: String, ttl: Duration): String? = signedVariantUrl(privateKey, variant, ttl, null)

    override fun signedVariantUrl(privateKey: String, variant: String, ttl: Duration, accept: String?): String? {
        val v = status(privateKey)?.takeIf { it.state == UploadImageState.READY }?.variants?.get(variant)?.forAccept(accept) ?: return null
        return storage.presignDownload(PresignedDownloadRequest(ObjectKey(v.key), expiresIn = ttl)).url.toString()
    }

    override fun ensure(privateKey: String) {
        val hash = imageKeyHash(privateKey)
        if (store.find(hash) != null) return
        val meta = storage.metadata(ObjectKey(privateKey)) ?: return   // 없는 객체는 등록하지 않는다
        val kinds = UploadMagicBytes.declaredKinds(meta.contentType)
        // `handles` 는 이미지 종류면 엔진이 못 읽어도 참이다 - HEIC 같은 것은 여기서 줄이 생기고 작업이 UNSUPPORTED 로 적는다(「일반 파일」로 보이지 않는다)
        if (kinds != null && kinds.any { renderers.handles(it) }) return register(privateKey)   // 이미지(또는 엔진이 읽는 종류)로 선언했다
        // 이미지로 선언하지 않은 모든 키는 선언을 믿지 않고 앞 바이트(매직 넘버)로 본다 - 이미지인데 줄이 없어 「일반 파일」로 남에게 원본이 가는 길을 막는다(완료 호출을 빼먹은 경우)
        if ((meta.sizeBytes ?: 0L) == 0L) return   // 빈 객체는 범위 읽기가 안 된다 - 아직 판정하지 않는다
        val head = storage.read(ObjectKey(privateKey), 0L until UploadMagicBytes.HEAD_BYTES.toLong()).use { it.readNBytes(UploadMagicBytes.HEAD_BYTES) }
        val kind = UploadMagicBytes.detect(head)
        if (kind != null && renderers.handles(kind)) return register(privateKey)
        // 이미지가 아니다 - 판정을 기억해 남이 볼 때마다 범위 읽기가 붙지 않게 한다(작업은 넣지 않는다). 그 사이 다른 호출이 이미 줄을 만들었으면 건드리지 않는다
        if (store.register(hash, privateKey) { }) store.markTerminal(hash, UploadImageState.NOT_IMAGE, ImageReasons.NOT_AN_IMAGE)
    }

    /** 완료 리스너 · [ensure] 가 부른다 - 줄이 없을 때만 줄 + 작업 (동시에 와도 하나) */
    internal fun register(privateKey: String) {
        val hash = imageKeyHash(privateKey)
        store.register(hash, privateKey) { tasks.enqueue(hash) }
    }

    override fun forget(privateKeys: Collection<String>) {
        if (privateKeys.isEmpty()) return
        val hashes = privateKeys.map { imageKeyHash(it) }
        store.delete(hashes)
        hashes.forEach { hash ->
            val folder = variantFolder(properties.prefix, hash)
            val keys = mutableListOf<ObjectKey>()
            var token: String? = null
            do {
                val page = storage.list(ListObjectsRequest(folder, token, 1000))
                keys += page.objects.map { it.key }
                token = page.nextContinuationToken.takeIf { page.truncated }
            } while (token != null)
            if (keys.isNotEmpty()) storage.deleteAll(keys)
        }
    }

    private fun ImageRow.toStatus(): UploadImageStatus {
        val folder = variantFolder(properties.prefix, keyHash)
        val mapped = variants.mapValues { (name, v) ->
            if (v.ext == StoredVariant.ORIGINAL) UploadImageVariant(objectKey, v.width, v.height, "image/" + (format ?: "jpeg"))
            else UploadImageVariant("$folder$name.${v.ext}", v.width, v.height, contentTypeOf(v.ext), v.alt.map { UploadImageVariant("$folder$name.$it", v.width, v.height, contentTypeOf(it)) })
        }
        return UploadImageStatus(state, width, height, mapped)
    }

    private fun contentTypeOf(ext: String) = if (ext == "jpg") "image/jpeg" else "image/$ext"
}
