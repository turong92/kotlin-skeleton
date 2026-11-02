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

    override fun status(privateKey: String): UploadImageStatus? = store.find(imageKeyHash(privateKey))?.toStatus()

    override fun views(privateKeys: Collection<String>): Map<String, UploadImageStatus> {
        val hashes = privateKeys.associateBy { imageKeyHash(it) }
        return store.findAll(hashes.keys).mapNotNull { (hash, row) -> hashes[hash]?.let { it to row.toStatus() } }.toMap()
    }

    override fun signedVariantUrl(privateKey: String, variant: String, ttl: Duration): String? {
        val v = status(privateKey)?.takeIf { it.state == UploadImageState.READY }?.variants?.get(variant) ?: return null
        return storage.presignDownload(PresignedDownloadRequest(ObjectKey(v.key), expiresIn = ttl)).url.toString()
    }

    override fun ensure(privateKey: String) {
        val hash = imageKeyHash(privateKey)
        if (store.find(hash) != null) return
        val declared = storage.metadata(ObjectKey(privateKey))?.contentType ?: return   // 없는 객체는 등록하지 않는다
        val kinds = UploadMagicBytes.declaredKinds(declared) ?: return
        if (kinds.none { renderers.handles(it) }) return
        register(privateKey)
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
            else UploadImageVariant("$folder$name.${v.ext}", v.width, v.height, if (v.ext == "jpg") "image/jpeg" else "image/${v.ext}")
        }
        return UploadImageStatus(state, width, height, mapped)
    }
}
